package com.cineflow.payment;

import com.cineflow.contracts.payment.PaymentEvent;
import com.cineflow.contracts.payment.PaymentSucceeded;
import com.cineflow.contracts.payment.PaymentTopics;
import com.cineflow.contracts.payment.RefundCompleted;
import com.cineflow.payment.dto.CreateInvoiceRequest;
import com.cineflow.payment.outbox.OutboxWriter;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Relay: outbox → Kafka. Настоящий брокер, настоящая БД.
class OutboxRelayTest extends AbstractPaymentIntegrationTest {

    @Autowired TransactionTemplate transactionTemplate;
    @Autowired OutboxWriter outboxWriter;

    private static final UUID USER = UUID.fromString("0199c4f2-0000-7000-8000-0000000000aa");

    // Брокер, поставленный на паузу тестом, обязан вернуться к жизни даже при падении теста:
    // контейнер общий для всех тест-классов.
    @AfterEach
    void unpauseBroker() {
        try {
            KAFKA.getDockerClient().unpauseContainerCmd(KAFKA.getContainerId()).exec();
        } catch (Exception ignored) {
            // не на паузе
        }
    }

    private void writeInOneTransaction(PaymentEvent... events) {
        transactionTemplate.executeWithoutResult(tx -> {
            for (PaymentEvent event : events) {
                outboxWriter.write(PaymentTopics.EVENTS, event);
            }
        });
    }

    private PaymentSucceeded succeeded(UUID bookingId) {
        return new PaymentSucceeded(UUID.randomUUID(), Instant.now(), bookingId, UUID.randomUUID(),
                "1500.00", "KZT");
    }

    private RefundCompleted refunded(UUID bookingId, UUID paymentId) {
        return new RefundCompleted(UUID.randomUUID(), Instant.now(), bookingId, paymentId, "1500.00", "KZT");
    }

    @Test
    void publishesWithBookingIdKeyAndMarksPublished() throws Exception {
        UUID bookingId = UUID.randomUUID();
        PaymentSucceeded event = succeeded(bookingId);
        writeInOneTransaction(event);

        int published = outboxRelay.publishBatch();

        assertThat(published).isEqualTo(1);
        assertThat(unpublishedCount()).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT published_at IS NOT NULL FROM outbox WHERE id = ?", Boolean.class, event.eventId()))
                .isTrue();

        List<ConsumerRecord<String, String>> records = readByKey(PaymentTopics.EVENTS, bookingId, 1);
        assertThat(records).hasSize(1);
        ConsumerRecord<String, String> record = records.get(0);
        assertThat(record.key()).isEqualTo(bookingId.toString());
        // Тип — в теле сообщения, логическим именем.
        PaymentEvent parsed = jsonMapper.readValue(record.value(), PaymentEvent.class);
        assertThat(parsed).isEqualTo(event);
        assertThat(record.value()).contains("\"type\"").contains("PaymentSucceeded");
        // Заголовка __TypeId__ (полное имя Java-класса) нет: StringSerializer его не пишет.
        assertThat(record.headers().lastHeader("__TypeId__")).isNull();
        assertThat(record.value()).doesNotContain("com.cineflow");
    }

    @Test
    void secondRunDoesNotResendPublished() {
        UUID bookingId = UUID.randomUUID();
        writeInOneTransaction(succeeded(bookingId));

        assertThat(outboxRelay.publishBatch()).isEqualTo(1);
        assertThat(outboxRelay.publishBatch()).isZero();

        assertThat(readByKey(PaymentTopics.EVENTS, bookingId, 1)).hasSize(1);
    }

    @Test
    void preservesWriteOrderForOneKey() {
        UUID bookingId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        PaymentSucceeded paid = new PaymentSucceeded(UUID.randomUUID(), Instant.now(), bookingId, paymentId,
                "1500.00", "KZT");
        RefundCompleted refund = refunded(bookingId, paymentId);
        writeInOneTransaction(paid);
        writeInOneTransaction(refund);

        assertThat(outboxRelay.publishBatch()).isEqualTo(2);

        List<ConsumerRecord<String, String>> records = readByKey(PaymentTopics.EVENTS, bookingId, 2);
        assertThat(records).extracting(r -> jsonMapper.readValue(r.value(), PaymentEvent.class))
                .containsExactly(paid, refund);
    }

    // Брокер недоступен: published_at остаётся NULL; после восстановления сообщения уходят
    // все, по порядку и без дублей. Недоступность моделируем паузой контейнера (docker pause):
    // в отличие от stop/start порт на хосте при этом не меняется.
    @Test
    void brokerOutageKeepsMessagesUnpublishedThenDeliversInOrder() {
        UUID bookingId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        PaymentSucceeded first = new PaymentSucceeded(UUID.randomUUID(), Instant.now(), bookingId, paymentId,
                "1500.00", "KZT");
        RefundCompleted second = refunded(bookingId, paymentId);
        PaymentSucceeded third = new PaymentSucceeded(UUID.randomUUID(), Instant.now(), bookingId,
                UUID.randomUUID(), "700.00", "KZT");
        writeInOneTransaction(first);
        writeInOneTransaction(second);
        writeInOneTransaction(third);

        KAFKA.getDockerClient().pauseContainerCmd(KAFKA.getContainerId()).exec();
        int duringOutage = outboxRelay.publishBatch();
        KAFKA.getDockerClient().unpauseContainerCmd(KAFKA.getContainerId()).exec();

        // Первая же неудача прервала пачку: ничего не помечено опубликованным.
        assertThat(duringOutage).isZero();
        assertThat(unpublishedCount()).isEqualTo(3);

        // Брокер вернулся — всё уходит, порядок записи сохранён.
        assertThat(outboxRelay.publishBatch()).isEqualTo(3);
        assertThat(unpublishedCount()).isZero();

        List<ConsumerRecord<String, String>> records = readByKey(PaymentTopics.EVENTS, bookingId, 3);
        // Ровно три записи: ни потерь, ни дублей от «зависшей» отправки времён простоя.
        assertThat(records).extracting(r -> jsonMapper.readValue(r.value(), PaymentEvent.class))
                .containsExactly(first, second, third);
    }

    // Состояние БД после реальной смены статуса: цепочка «создать счёт → оплатить → relay».
    @Test
    void endToEndFromPaymentTransitionToKafka() {
        UUID bookingId = UUID.randomUUID();
        UUID paymentId = paymentService.createInvoice(USER,
                new CreateInvoiceRequest(bookingId, new BigDecimal("2000.00"), "+77011234567")).paymentId();
        paymentPersistence.markPaid(paymentId);

        outboxRelay.publishBatch();

        List<ConsumerRecord<String, String>> records = readByKey(PaymentTopics.EVENTS, bookingId, 1);
        assertThat(records).hasSize(1);
        PaymentEvent parsed = jsonMapper.readValue(records.get(0).value(), PaymentEvent.class);
        assertThat(parsed).isInstanceOfSatisfying(PaymentSucceeded.class, e -> {
            assertThat(e.paymentId()).isEqualTo(paymentId);
            assertThat(e.bookingId()).isEqualTo(bookingId);
            assertThat(e.amount()).isEqualTo("2000.00");
            assertThat(e.currency()).isEqualTo("KZT");
        });
    }
}

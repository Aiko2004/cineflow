package com.cineflow.payment;

import com.cineflow.contracts.payment.CancelInvoice;
import com.cineflow.contracts.payment.PaymentCommand;
import com.cineflow.contracts.payment.PaymentTopics;
import com.cineflow.contracts.payment.RefundPayment;
import com.cineflow.payment.dto.CreateInvoiceRequest;
import com.cineflow.payment.dto.InvoiceResponse;
import com.cineflow.payment.gateway.PaymentGateway;
import com.cineflow.payment.gateway.PaymentProviderException;
import com.cineflow.payment.service.PaymentPersistence;
import com.cineflow.payment.service.RefundService;
import com.github.f4b6a3.uuid.UuidCreator;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

// Потребитель payment.commands: настоящий листенер, сообщения кладутся в Kafka обычным продюсером
// (как их положил бы booking или Kafka UI). Единственный тест-класс с включённым листенером —
// см. AbstractPaymentIntegrationTest.
@TestPropertySource(properties = "spring.kafka.listener.auto-startup=true")
class PaymentCommandsConsumerTest extends AbstractPaymentIntegrationTest {

    @MockitoSpyBean PaymentGateway gateway;
    @MockitoSpyBean PaymentPersistence persistenceSpy;
    @Autowired RefundService refundService;

    private static final UUID USER = UUID.fromString("0199c4f2-0000-7000-8000-0000000000aa");
    private static final Duration WAIT = Duration.ofSeconds(20);

    private static KafkaProducer<String, String> producer;
    private static Admin admin;

    @BeforeAll
    static void clients() {
        producer = new KafkaProducer<>(Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()),
                new StringSerializer(), new StringSerializer());
        admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()));
    }

    @AfterAll
    static void closeClients() {
        producer.close();
        admin.close();
    }

    private InvoiceResponse paidPayment(UUID bookingId) {
        InvoiceResponse invoice = newInvoice(bookingId);
        paymentPersistence.markPaid(invoice.paymentId());
        jdbcTemplate.update("UPDATE sim_invoices SET status = 'PAID' WHERE provider_invoice_id = ?",
                invoice.providerInvoiceId());
        return invoice;
    }

    private InvoiceResponse newInvoice(UUID bookingId) {
        return paymentService.createInvoice(USER,
                new CreateInvoiceRequest(bookingId, new BigDecimal("1200.00"), "+77011234567"));
    }

    private RecordMetadata sendCommand(PaymentCommand command) throws Exception {
        String json = jsonMapper.writerFor(PaymentCommand.class).writeValueAsString(command);
        return sendRaw(command.bookingId(), json);
    }

    private RecordMetadata sendRaw(UUID key, String value) throws Exception {
        return producer.send(new ProducerRecord<>(PaymentTopics.COMMANDS, key.toString(), value)).get();
    }

    private static RefundPayment refund(InvoiceResponse invoice, UUID bookingId) {
        return new RefundPayment(UuidCreator.getTimeOrderedEpoch(), Instant.now(), bookingId, invoice.paymentId());
    }

    private static CancelInvoice cancel(InvoiceResponse invoice, UUID bookingId) {
        return new CancelInvoice(UuidCreator.getTimeOrderedEpoch(), Instant.now(), bookingId, invoice.paymentId());
    }

    // Ждёт, пока группа payment-service закоммитит offset ПОСЛЕ этого сообщения — то есть
    // сообщение обработано (или отправлено в DLT). Надёжнее, чем ждать по времени.
    private void awaitConsumed(RecordMetadata sent) {
        TopicPartition partition = new TopicPartition(sent.topic(), sent.partition());
        await().atMost(WAIT).until(() -> {
            OffsetAndMetadata committed = admin.listConsumerGroupOffsets("payment-service")
                    .partitionsToOffsetAndMetadata().get().get(partition);
            return committed != null && committed.offset() > sent.offset();
        });
    }

    @Test
    void refundPaymentTwiceCallsProviderOnceAndCompletesOnce() throws Exception {
        UUID bookingId = UUID.randomUUID();
        InvoiceResponse invoice = paidPayment(bookingId);

        sendCommand(refund(invoice, bookingId));
        awaitConsumed(sendCommand(refund(invoice, bookingId)));   // другая команда, тот же платёж

        assertThat(paymentStatus(invoice.paymentId())).isEqualTo("REFUNDED");
        assertThat(invoiceStatus(invoice.providerInvoiceId())).isEqualTo("REFUNDED");
        verify(gateway, times(1)).refund(invoice.providerInvoiceId(), invoice.paymentId());
        assertThat(outboxPayloads(bookingId, "RefundCompleted")).hasSize(1);

        outboxRelay.publishBatch();
        List<ConsumerRecord<String, String>> events = readByKey(PaymentTopics.EVENTS, bookingId, 2);
        assertThat(events).extracting(r -> jsonMapper.readTree(r.value()).get("type").asString())
                .containsExactly("PaymentSucceeded", "RefundCompleted");
    }

    // Провайдер недоступен: платёж остаётся REFUND_PENDING. Повтор КОМАНДЫ в этом состоянии
    // провайдера не вызывает — возврат доводит только фоновая задача.
    @Test
    void refundWhileProviderDownStaysPendingAndOnlyRetryJobFinishesIt() throws Exception {
        UUID bookingId = UUID.randomUUID();
        InvoiceResponse invoice = paidPayment(bookingId);
        doThrow(new PaymentProviderException("down", null)).when(gateway).refund(any(), any());

        awaitConsumed(sendCommand(refund(invoice, bookingId)));
        assertThat(paymentStatus(invoice.paymentId())).isEqualTo("REFUND_PENDING");
        verify(gateway, times(1)).refund(any(), any());

        // Повторная доставка / повторная команда, пока REFUND_PENDING.
        awaitConsumed(sendCommand(refund(invoice, bookingId)));
        verify(gateway, times(1)).refund(any(), any());
        assertThat(paymentStatus(invoice.paymentId())).isEqualTo("REFUND_PENDING");
        assertThat(outboxPayloads(bookingId, "RefundCompleted")).isEmpty();

        // Провайдер ожил — повтор делает фоновая задача.
        reset(gateway);
        assertThat(refundService.retryStaleRefunds()).isEqualTo(1);

        assertThat(paymentStatus(invoice.paymentId())).isEqualTo("REFUNDED");
        verify(gateway, times(1)).refund(invoice.providerInvoiceId(), invoice.paymentId());
        assertThat(outboxPayloads(bookingId, "RefundCompleted")).hasSize(1);
        // Ещё один проход задачи — делать нечего.
        assertThat(refundService.retryStaleRefunds()).isZero();
    }

    @Test
    void cancelInvoiceCancelsAtProviderAndEmitsPaymentFailedCancelled() throws Exception {
        UUID bookingId = UUID.randomUUID();
        InvoiceResponse invoice = newInvoice(bookingId);

        awaitConsumed(sendCommand(cancel(invoice, bookingId)));
        awaitConsumed(sendCommand(cancel(invoice, bookingId)));   // повтор — ничего не меняет

        assertThat(paymentStatus(invoice.paymentId())).isEqualTo("CANCELLED");
        assertThat(invoiceStatus(invoice.providerInvoiceId())).isEqualTo("CANCELLED");
        verify(gateway, times(1)).cancelInvoice(invoice.providerInvoiceId());
        assertThat(outboxPayloads(bookingId, "PaymentFailed")).singleElement()
                .satisfies(payload -> assertThat(payload).contains("\"reason\": \"CANCELLED\""));
    }

    // Пользователь оплатил, пока шла отмена (webhook ещё не дошёл). Провайдер отказывает в отмене —
    // платёж становится PAID, а не CANCELLED: деньги не теряются, booking попросит возврат.
    @Test
    void cancelLosingRaceToPaymentMarksPaid() throws Exception {
        UUID bookingId = UUID.randomUUID();
        InvoiceResponse invoice = newInvoice(bookingId);
        jdbcTemplate.update("UPDATE sim_invoices SET status = 'PAID' WHERE provider_invoice_id = ?",
                invoice.providerInvoiceId());

        awaitConsumed(sendCommand(cancel(invoice, bookingId)));

        assertThat(paymentStatus(invoice.paymentId())).isEqualTo("PAID");
        assertThat(outboxPayloads(bookingId, "PaymentSucceeded")).hasSize(1);
        assertThat(outboxPayloads(bookingId, "PaymentFailed")).isEmpty();
    }

    // Ядовитые сообщения уходят в .DLT, следующее сообщение той же партиции обрабатывается.
    @Test
    void poisonMessagesGoToDltAndDoNotBlockThePartition() throws Exception {
        UUID bookingId = UUID.randomUUID();
        InvoiceResponse invoice = newInvoice(bookingId);

        // Тот же ключ = та же партиция, что и у нормальной команды после них.
        sendRaw(bookingId, "not a json");
        sendRaw(bookingId, """
                {"type":"SomethingNew","eventId":"%s","occurredAt":"2026-10-23T10:00:00Z",
                 "bookingId":"%s","paymentId":"%s"}""".formatted(UUID.randomUUID(), bookingId, invoice.paymentId()));
        awaitConsumed(sendCommand(cancel(invoice, bookingId)));

        assertThat(paymentStatus(invoice.paymentId())).isEqualTo("CANCELLED");
        List<ConsumerRecord<String, String>> dead = readByKey(PaymentTopics.COMMANDS_DLT, bookingId, 2);
        assertThat(dead).hasSize(2);
        assertThat(dead.getFirst().value()).isEqualTo("not a json");
        assertThat(dead.get(1).value()).contains("SomethingNew");
        // DLT несёт причину в заголовках (DeadLetterPublishingRecoverer).
        assertThat(new String(dead.getFirst().headers().lastHeader("kafka_dlt-exception-cause-fqcn").value(),
                StandardCharsets.UTF_8)).isEqualTo("com.cineflow.payment.messaging.MalformedCommandException");
    }

    // «Платёж не найден» повтором не исправится — в DLT с первой попытки, без повторов.
    @Test
    void commandForUnknownPaymentGoesToDltWithoutRetries() throws Exception {
        UUID bookingId = UUID.randomUUID();
        UUID unknownPayment = UUID.randomUUID();

        awaitConsumed(sendCommand(new RefundPayment(UuidCreator.getTimeOrderedEpoch(), Instant.now(),
                bookingId, unknownPayment)));

        assertThat(readByKey(PaymentTopics.COMMANDS_DLT, bookingId, 1)).hasSize(1);
        verify(persistenceSpy, times(1)).get(unknownPayment);
    }

    // Временный сбой (провайдер недоступен при отмене): повторы с паузой, затем DLT.
    // max-retries=2 в тестах → всего 3 попытки.
    @Test
    void transientFailureIsRetriedThenDeadLettered() throws Exception {
        UUID bookingId = UUID.randomUUID();
        InvoiceResponse invoice = newInvoice(bookingId);
        doThrow(new PaymentProviderException("down", null)).when(gateway).cancelInvoice(any());

        awaitConsumed(sendCommand(cancel(invoice, bookingId)));

        verify(gateway, times(3)).cancelInvoice(eq(invoice.providerInvoiceId()));
        assertThat(readByKey(PaymentTopics.COMMANDS_DLT, bookingId, 1)).hasSize(1);
        assertThat(paymentStatus(invoice.paymentId())).isEqualTo("PENDING");
    }

    // bookingId в команде не совпадает с платежом — исполнять (особенно возврат) нельзя.
    @Test
    void commandWithForeignBookingIdIsRejected() throws Exception {
        UUID bookingId = UUID.randomUUID();
        InvoiceResponse invoice = paidPayment(bookingId);
        UUID otherBooking = UUID.randomUUID();

        awaitConsumed(sendCommand(new RefundPayment(UuidCreator.getTimeOrderedEpoch(), Instant.now(),
                otherBooking, invoice.paymentId())));

        assertThat(paymentStatus(invoice.paymentId())).isEqualTo("PAID");
        verify(gateway, times(0)).refund(any(), any());
        assertThat(readByKey(PaymentTopics.COMMANDS_DLT, otherBooking, 1)).hasSize(1);
    }
}

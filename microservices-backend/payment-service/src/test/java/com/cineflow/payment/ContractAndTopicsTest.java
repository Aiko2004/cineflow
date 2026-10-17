package com.cineflow.payment;

import com.cineflow.contracts.payment.CancelInvoice;
import com.cineflow.contracts.payment.PaymentCommand;
import com.cineflow.contracts.payment.PaymentEvent;
import com.cineflow.contracts.payment.PaymentFailed;
import com.cineflow.contracts.payment.PaymentFailureReason;
import com.cineflow.contracts.payment.PaymentSucceeded;
import com.cineflow.contracts.payment.PaymentTopics;
import com.cineflow.contracts.payment.RefundPayment;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.TopicDescription;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Формат на проводе (контракт) и топики. Контракт проверяется настоящим JsonMapper приложения —
// тем, что и сериализует события в outbox, — а не отдельно сконфигурированным.
class ContractAndTopicsTest extends AbstractPaymentIntegrationTest {

    private final UUID bookingId = UUID.fromString("0199c4f2-0000-7000-8000-000000000001");
    private final UUID paymentId = UUID.fromString("0199c4f2-0000-7000-8000-000000000002");

    @Test
    void eventCarriesLogicalTypeNameInBody() {
        PaymentFailed event = new PaymentFailed(UUID.randomUUID(), Instant.parse("2026-10-18T10:00:00Z"),
                bookingId, paymentId, PaymentFailureReason.EXPIRED);

        JsonNode json = jsonMapper.valueToTree(event);

        assertThat(json.get("type").asString()).isEqualTo("PaymentFailed");
        assertThat(json.get("bookingId").asString()).isEqualTo(bookingId.toString());
        assertThat(json.get("reason").asString()).isEqualTo("EXPIRED");
        // Время — строкой ISO-8601, не числом.
        assertThat(json.get("occurredAt").asString()).isEqualTo("2026-10-18T10:00:00Z");
        assertThat(jsonMapper.writeValueAsString(event)).doesNotContain("com.cineflow");
    }

    @Test
    void roundTripsEveryMessageType() {
        PaymentEvent succeeded = new PaymentSucceeded(UUID.randomUUID(), Instant.now(), bookingId, paymentId,
                "1500.00", "KZT");
        PaymentCommand refund = new RefundPayment(UUID.randomUUID(), Instant.now(), bookingId, paymentId);
        PaymentCommand cancel = new CancelInvoice(UUID.randomUUID(), Instant.now(), bookingId, paymentId);

        assertThat(jsonMapper.readValue(jsonMapper.writeValueAsString(succeeded), PaymentEvent.class))
                .isEqualTo(succeeded);
        assertThat(jsonMapper.readValue(jsonMapper.writeValueAsString(refund), PaymentCommand.class))
                .isEqualTo(refund);
        assertThat(jsonMapper.readValue(jsonMapper.writeValueAsString(cancel), PaymentCommand.class))
                .isEqualTo(cancel);
    }

    // Сообщение, положенное руками (Kafka UI), — без заголовков, только тело.
    @Test
    void handWrittenCommandIsReadable() {
        String json = """
                {"type":"RefundPayment","eventId":"0199c4f2-0000-7000-8000-000000000009",
                 "occurredAt":"2026-10-18T10:00:00Z","bookingId":"%s","paymentId":"%s"}
                """.formatted(bookingId, paymentId);

        PaymentCommand command = jsonMapper.readValue(json, PaymentCommand.class);

        assertThat(command).isInstanceOf(RefundPayment.class);
        assertThat(command.bookingId()).isEqualTo(bookingId);
    }

    @Test
    void newFieldsAndUnknownEnumValuesDoNotBreakOldConsumers() {
        String json = """
                {"type":"PaymentFailed","eventId":"0199c4f2-0000-7000-8000-000000000009",
                 "occurredAt":"2026-10-18T10:00:00Z","bookingId":"%s","paymentId":"%s",
                 "reason":"SOME_FUTURE_REASON","fieldAddedInTheFuture":42}
                """.formatted(bookingId, paymentId);

        PaymentEvent event = jsonMapper.readValue(json, PaymentEvent.class);

        assertThat(event).isInstanceOfSatisfying(PaymentFailed.class,
                e -> assertThat(e.reason()).isEqualTo(PaymentFailureReason.UNKNOWN));
    }

    // Незнакомый тип и «чужой» тип (команда в топике событий) — ошибка, а не молчаливый пропуск.
    @Test
    void unknownOrForeignTypeFailsLoudly() {
        String unknown = """
                {"type":"SomethingNew","eventId":"%s","occurredAt":"2026-10-18T10:00:00Z",
                 "bookingId":"%s","paymentId":"%s"}
                """.formatted(UUID.randomUUID(), bookingId, paymentId);
        String commandInEvents = jsonMapper.writeValueAsString(
                new RefundPayment(UUID.randomUUID(), Instant.now(), bookingId, paymentId));

        assertThatThrownBy(() -> jsonMapper.readValue(unknown, PaymentEvent.class))
                .isInstanceOf(JacksonException.class);
        assertThatThrownBy(() -> jsonMapper.readValue(commandInEvents, PaymentEvent.class))
                .isInstanceOf(JacksonException.class);
    }

    @Test
    void topicsExistWithThreePartitionsIncludingDeadLetterTopics() throws Exception {
        List<String> names = List.of(PaymentTopics.EVENTS, PaymentTopics.COMMANDS,
                PaymentTopics.EVENTS_DLT, PaymentTopics.COMMANDS_DLT);

        try (Admin admin = Admin.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            Map<String, TopicDescription> topics = admin.describeTopics(names).allTopicNames().get();

            assertThat(topics).containsOnlyKeys(names);
            assertThat(topics.values()).allSatisfy(t -> assertThat(t.partitions()).hasSize(3));
        }
    }
}

package com.cineflow.payment.outbox;

import java.util.UUID;

// Строка outbox, готовая к отправке. payload — JSON-текст, уже готовое тело Kafka-сообщения.
public record OutboxMessage(UUID id, UUID aggregateId, String topic, String eventType, String payload) {
}

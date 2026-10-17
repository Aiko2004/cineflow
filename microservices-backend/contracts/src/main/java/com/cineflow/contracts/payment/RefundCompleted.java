package com.cineflow.contracts.payment;

import java.time.Instant;
import java.util.UUID;

// Деньги возвращены (ответ на команду RefundPayment).
public record RefundCompleted(
        UUID eventId,
        Instant occurredAt,
        UUID bookingId,
        UUID paymentId,
        String amount,
        String currency
) implements PaymentEvent {
}

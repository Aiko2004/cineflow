package com.cineflow.contracts.payment;

import java.time.Instant;
import java.util.UUID;

// Платёж не состоялся (счёт истёк, отклонён или отменён).
public record PaymentFailed(
        UUID eventId,
        Instant occurredAt,
        UUID bookingId,
        UUID paymentId,
        PaymentFailureReason reason
) implements PaymentEvent {
}

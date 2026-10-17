package com.cineflow.contracts.payment;

import java.time.Instant;
import java.util.UUID;

// Платёж получен. Деньги — строкой ("1500.00"), как в gRPC-контракте: BigDecimal через
// double теряет точность, а строка читается любым языком.
public record PaymentSucceeded(
        UUID eventId,
        Instant occurredAt,
        UUID bookingId,
        UUID paymentId,
        String amount,
        String currency
) implements PaymentEvent {
}

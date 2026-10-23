package com.cineflow.contracts.payment;

import java.time.Instant;
import java.util.UUID;

// Команда «вернуть деньги». Повторная доставка безопасна: возврат идемпотентен по paymentId.
public record RefundPayment(
        UUID eventId,
        Instant occurredAt,
        UUID bookingId,
        UUID paymentId
) implements PaymentCommand {
}

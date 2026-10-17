package com.cineflow.contracts.payment;

import java.time.Instant;
import java.util.UUID;

// Команда «отменить неоплаченный счёт» (пользователь отменил бронь до оплаты).
public record CancelInvoice(
        UUID eventId,
        Instant occurredAt,
        UUID bookingId,
        UUID paymentId
) implements PaymentCommand {
}

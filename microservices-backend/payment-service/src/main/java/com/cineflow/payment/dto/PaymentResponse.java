package com.cineflow.payment.dto;

import com.cineflow.payment.model.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

// GET /payment/{id}: статус платежа владельцу. Телефон не отдаём — клиент его и так знает,
// а лишние персональные данные в ответе не нужны.
public record PaymentResponse(
        UUID id,
        UUID bookingId,
        BigDecimal amount,
        String currency,
        PaymentStatus status,
        Instant expiresAt,
        Instant paidAt
) {
}

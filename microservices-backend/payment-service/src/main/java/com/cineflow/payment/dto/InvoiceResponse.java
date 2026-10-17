package com.cineflow.payment.dto;

import com.cineflow.payment.model.PaymentStatus;

import java.time.Instant;
import java.util.UUID;

// Ответ на «выставить счёт». status добавлен к полям из дизайна: повтор запроса может вернуть
// уже оплаченный/просроченный платёж, и вызывающему важно это видеть, а не только срок.
public record InvoiceResponse(UUID paymentId, String providerInvoiceId, Instant expiresAt, PaymentStatus status) {
}

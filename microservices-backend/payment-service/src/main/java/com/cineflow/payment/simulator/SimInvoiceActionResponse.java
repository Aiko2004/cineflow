package com.cineflow.payment.simulator;

import java.time.Instant;

// Ответ dev-эндпоинта симулятора: что стало со счётом и когда уйдёт webhook.
public record SimInvoiceActionResponse(String invoiceId, String status, Instant webhookAt) {
}

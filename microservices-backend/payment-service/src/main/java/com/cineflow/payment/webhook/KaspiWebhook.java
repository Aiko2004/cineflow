package com.cineflow.payment.webhook;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.UUID;

// Тело webhook провайдера (протокол симулятора Kaspi, не реального API).
//
// paymentId — НАША ссылка, переданная провайдеру при createInvoice (merchant reference). По ней
// webhook находит платёж, даже если provider_invoice_id ещё не записан у нас (см.
// PaymentPersistence.applyProviderOutcome).
//
// ignoreUnknown: провайдер вправе добавлять поля, это не должно ломать приём оплаты.
@JsonIgnoreProperties(ignoreUnknown = true)
public record KaspiWebhook(
        String invoiceId,
        UUID paymentId,
        String status,          // PAID | DECLINED
        Instant occurredAt
) {
}

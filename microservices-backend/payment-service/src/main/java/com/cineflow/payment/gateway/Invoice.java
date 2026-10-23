package com.cineflow.payment.gateway;

import java.time.Instant;

// Счёт, выставленный у провайдера.
public record Invoice(String providerInvoiceId, Instant expiresAt) {
}

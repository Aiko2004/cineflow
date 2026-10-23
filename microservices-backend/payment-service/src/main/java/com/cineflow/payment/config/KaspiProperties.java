package com.cineflow.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// Интеграция с Kaspi (cineflow.payment.kaspi).
@ConfigurationProperties(prefix = "cineflow.payment.kaspi")
public record KaspiProperties(
        // Общий с провайдером секрет HMAC-SHA256 для подписи webhook. Из PAYMENT_KASPI_WEBHOOK_SECRET;
        // dev-дефолт — в application.yaml.
        String webhookSecret
) {
    public KaspiProperties {
        // Пустой секрет = HMAC с пустым ключом: подпись смог бы посчитать кто угодно. Лучше не
        // стартовать, чем принимать такие webhook'и.
        if (webhookSecret == null || webhookSecret.isBlank()) {
            throw new IllegalArgumentException("cineflow.payment.kaspi.webhook-secret must not be blank");
        }
    }
}

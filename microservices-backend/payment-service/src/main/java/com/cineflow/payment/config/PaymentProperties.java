package com.cineflow.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

// Параметры домена payment (application.yaml, cineflow.payment).
@ConfigurationProperties(prefix = "cineflow.payment")
public record PaymentProperties(
        // Срок жизни счёта; короче удержания брони (PAYMENT_DESIGN.md §3).
        Duration invoiceTtl,
        // Валюта счетов. Kaspi работает в тенге; поле в payments оставлено под будущее.
        String currency,
        // Фактор репликации топиков (1 в dev с одним брокером).
        int topicReplicas
) {
}

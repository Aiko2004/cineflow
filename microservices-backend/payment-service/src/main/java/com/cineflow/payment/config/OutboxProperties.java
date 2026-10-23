package com.cineflow.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

// Параметры relay (cineflow.payment.outbox). Интервал тика читается напрямую плейсхолдером
// в @Scheduled — здесь только то, что нужно самому relay.
@ConfigurationProperties(prefix = "cineflow.payment.outbox")
public record OutboxProperties(
        // Сколько строк берём за тик. Пока идёт отправка, строки заблокированы (FOR UPDATE),
        // поэтому пачка небольшая.
        int batchSize,
        // Сколько relay ждёт подтверждения брокера на ОДНО сообщение. Должен быть чуть больше
        // max.block.ms + delivery.timeout.ms продюсера (см. application.yaml): тогда к моменту,
        // когда relay сдаётся, продюсер уже тоже отказался от этого сообщения.
        Duration sendTimeout
) {
}

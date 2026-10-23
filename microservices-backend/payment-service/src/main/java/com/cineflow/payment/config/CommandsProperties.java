package com.cineflow.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

// Потребитель payment.commands (cineflow.payment.commands).
@ConfigurationProperties(prefix = "cineflow.payment.commands")
public record CommandsProperties(
        // Сколько раз ПОВТОРИТЬ обработку после первой неудачи, прежде чем отправить
        // сообщение в .DLT. Всего попыток = maxRetries + 1.
        int maxRetries,
        // Пауза между попытками.
        Duration retryBackoff
) {
}

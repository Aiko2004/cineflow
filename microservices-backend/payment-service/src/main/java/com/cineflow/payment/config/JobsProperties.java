package com.cineflow.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

// Фоновые задачи: сверка и повтор возвратов (cineflow.payment.jobs). Интервалы тиков читаются
// плейсхолдерами прямо в @Scheduled — здесь только то, что нужно самим задачам.
//
// Обе задачи рассчитаны на ОДИН экземпляр сервиса (как и outbox relay) — см. ReconciliationService.
@ConfigurationProperties(prefix = "cineflow.payment.jobs")
public record JobsProperties(
        // Сколько платежей задача берёт за тик.
        int batchSize,
        // Возврат повторяется, только если платёж висит в REFUND_PENDING дольше этого. Без
        // паузы задача могла бы вызвать refund одновременно с обработчиком команды, который
        // только что перевёл платёж в REFUND_PENDING и сам сейчас зовёт провайдера.
        Duration refundRetryMinAge
) {
}

-- Симулятор Kaspi: хранилище счетов «внешней системы» (профиль kaspi-sim).
--
-- Отдельной миграцией, а не частью V1: V1 — схема платёжного ДОМЕНА ровно по
-- PAYMENT_DESIGN.md §4, а sim_invoices — имитация чужой системы. Они живут и меняются
-- независимо; настоящий провайдер эту таблицу не использует.
-- Таблица создаётся и без профиля kaspi-sim (Flyway не знает о профилях Spring) —
-- в проде она просто останется пустой.
--
-- Связи с payments НЕТ намеренно: у настоящего Kaspi нет доступа к нашей БД.
-- payment_id — просто ключ идемпотентности создания счёта (как идемпотентный ключ у
-- реальных провайдеров).

CREATE TABLE sim_invoices (
    provider_invoice_id VARCHAR(64)   PRIMARY KEY,
    payment_id          UUID          NOT NULL,
    phone               VARCHAR(20)   NOT NULL,
    amount              NUMERIC(10,2) NOT NULL,
    status              VARCHAR(16)   NOT NULL,   -- PENDING / PAID / DECLINED / CANCELLED (EXPIRED выводится из expires_at)
    expires_at          TIMESTAMPTZ   NOT NULL,
    created_at          TIMESTAMPTZ   NOT NULL,
    updated_at          TIMESTAMPTZ   NOT NULL,
    CONSTRAINT uk_sim_invoices_payment UNIQUE (payment_id)
);

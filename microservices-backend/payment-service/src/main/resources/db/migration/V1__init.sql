-- payment-service: платежи и transactional outbox (PAYMENT_DESIGN.md §4).

CREATE TABLE payments (
    id                  UUID          PRIMARY KEY,
    booking_id          UUID          NOT NULL,         -- чужая БД (booking): без FK, граница сервиса
    user_id             UUID          NOT NULL,         -- из claim sub, не из тела
    amount              NUMERIC(10,2) NOT NULL,
    currency            VARCHAR(3)    NOT NULL,         -- KZT
    phone               VARCHAR(20)   NOT NULL,         -- на какой номер выставлен счёт
    status              VARCHAR(20)   NOT NULL,         -- PENDING / PAID / FAILED / CANCELLED / REFUND_PENDING / REFUNDED
    provider_invoice_id VARCHAR(64),                    -- NULL, пока провайдер не ответил
    expires_at          TIMESTAMPTZ   NOT NULL,
    paid_at             TIMESTAMPTZ,
    refunded_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ   NOT NULL,
    updated_at          TIMESTAMPTZ   NOT NULL,
    -- Идемпотентность создания счёта: повтор с тем же bookingId вернёт тот же платёж.
    CONSTRAINT uk_payments_booking UNIQUE (booking_id)
);

-- Webhook находит платёж по id счёта у провайдера. UNIQUE допускает много NULL (счёт ещё
-- не создан у провайдера) — в Postgres NULL'ы в уникальном индексе различны.
CREATE UNIQUE INDEX uk_payments_provider_invoice ON payments (provider_invoice_id);

-- Transactional outbox. Строка пишется в той же транзакции, что и смена статуса платежа;
-- relay потом доставляет её в Kafka.
CREATE TABLE outbox (
    id           UUID         PRIMARY KEY,   -- он же eventId в сообщении (идемпотентность потребителя)
    aggregate_id UUID         NOT NULL,      -- bookingId → ключ сообщения Kafka
    topic        VARCHAR(100) NOT NULL,
    event_type   VARCHAR(100) NOT NULL,
    payload      JSONB        NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL,
    published_at TIMESTAMPTZ                 -- NULL = ещё не опубликовано
);

-- Частичный индекс: relay читает только неопубликованное. Опубликованные строки (их
-- большинство) в индекс не попадают — он остаётся крошечным, как бы ни рос outbox.
CREATE INDEX idx_outbox_unpublished ON outbox (created_at, id) WHERE published_at IS NULL;

-- auth-service: аккаунты и refresh-токены.
-- Redis хранит OTP-коды и счётчики rate limit (эфемерное, с TTL) — здесь только Postgres.

-- Аккаунт. Колонки с паролем нет: вход только по одноразовому коду.
CREATE TABLE users (
    id         UUID         PRIMARY KEY,
    email      VARCHAR(320) NOT NULL UNIQUE,        -- 320 = максимум по RFC (64 local + @ + 255 domain)
    phone      VARCHAR(20)  UNIQUE,                 -- nullable: вход по телефону появится позже
    role       VARCHAR(32)  NOT NULL,               -- пока всегда 'USER'
    created_at TIMESTAMPTZ  NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL
);

-- Refresh-токены. Хранится SHA-256 от токена, не сам токен.
CREATE TABLE refresh_tokens (
    id         UUID        PRIMARY KEY,
    user_id    UUID        NOT NULL REFERENCES users(id),
    token_hash VARCHAR(64) NOT NULL UNIQUE,         -- SHA-256 hex = 64 символа; UNIQUE даёт свой индекс
    family_id  UUID        NOT NULL,                -- общий для цепочки ротаций одной сессии
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,                          -- NULL = активен
    created_at TIMESTAMPTZ NOT NULL
);

-- user_id участвует в REFERENCES — индексируем явно: Postgres не делает это автоматически,
-- и без индекса удаление в users вызывает последовательный скан refresh_tokens под блокировкой
-- (правило из CLAUDE.md).
CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens (user_id);

-- family_id — точка входа для отзыва всей цепочки при обнаружении кражи (revokeFamily).
CREATE INDEX idx_refresh_tokens_family_id ON refresh_tokens (family_id);

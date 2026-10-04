-- booking-service: брони и занятые места.
-- Redis держит мягкие удержания на этапе выбора мест — здесь только Postgres.

CREATE TABLE bookings (
    id              UUID          PRIMARY KEY,
    user_id         UUID          NOT NULL,          -- из claim sub токена, не из тела
    screening_id    UUID          NOT NULL,
    status          VARCHAR(16)   NOT NULL,          -- PENDING / CONFIRMED / CANCELLED / EXPIRED
    total_price     NUMERIC(10,2) NOT NULL,
    hold_expires_at TIMESTAMPTZ   NOT NULL,          -- до какого момента бронь держит места
    qr_code         VARCHAR(255),                     -- заполняется при подтверждении
    screening_date  DATE          NOT NULL,          -- денормализация под контракт фронтенда
    screening_time  TIME          NOT NULL,
    -- снапшоты: цена и данные фиксируются на момент покупки
    movie_id        VARCHAR(64)   NOT NULL,
    movie_title     VARCHAR(255)  NOT NULL,
    movie_slug      VARCHAR(255),
    movie_banner    VARCHAR(255),
    theater_id      VARCHAR(64)   NOT NULL,
    theater_name    VARCHAR(255)  NOT NULL,
    theater_address VARCHAR(512),
    hall_id         VARCHAR(64)   NOT NULL,
    hall_name       VARCHAR(255)  NOT NULL,
    idempotency_key VARCHAR(64),
    created_at      TIMESTAMPTZ   NOT NULL,
    updated_at      TIMESTAMPTZ   NOT NULL,
    -- Повтор POST /bookings с тем же ключом от того же пользователя вернёт ту же бронь.
    -- NULL-ключи в Postgres считаются различными → брони без ключа не конфликтуют.
    CONSTRAINT uk_booking_idempotency UNIQUE (user_id, idempotency_key)
);

CREATE INDEX idx_bookings_user ON bookings (user_id);
CREATE INDEX idx_bookings_screening ON bookings (screening_id);

CREATE TABLE booking_seats (
    id           UUID          PRIMARY KEY,
    booking_id   UUID          NOT NULL REFERENCES bookings(id),
    screening_id UUID          NOT NULL,             -- дублируется: оба столбца индекса ниже в одной таблице
    seat_id      BIGINT        NOT NULL,             -- из theater-service (там Long)
    row_number   INT           NOT NULL,
    seat_number  INT           NOT NULL,
    seat_type    VARCHAR(32)   NOT NULL,
    price        NUMERIC(10,2) NOT NULL,
    released_at  TIMESTAMPTZ                          -- заполнен → место освобождено
);

-- FK-колонку индексируем явно (правило из CLAUDE.md).
CREATE INDEX idx_booking_seats_booking ON booking_seats (booking_id);

-- ★ ЕДИНСТВЕННАЯ настоящая гарантия «одно место — один раз». (BOOKING_DESIGN.md §2, §4)
-- Частичный уникальный индекс: уникальность только среди АКТИВНЫХ записей (released_at IS NULL).
-- После отмены/истечения released_at заполняется → место снова можно продать, история сохраняется.
-- Обычный UNIQUE(screening_id, seat_id) запретил бы повторную продажу после отмены.
CREATE UNIQUE INDEX uk_seat_taken
    ON booking_seats (screening_id, seat_id)
    WHERE released_at IS NULL;

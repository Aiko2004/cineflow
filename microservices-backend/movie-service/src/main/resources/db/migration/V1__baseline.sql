-- Baseline: схема как есть до рефакторинга movie-service.
-- Flyway запустит это при первом старте на чистой БД.
-- На существующей БД (ddl-auto: update) — используй baseline-on-migrate: true.

CREATE TABLE movies (
    id          BIGSERIAL    PRIMARY KEY,
    title       VARCHAR(255) NOT NULL,
    description VARCHAR(1500),
    poster_url  VARCHAR(255),
    category    VARCHAR(255)
);

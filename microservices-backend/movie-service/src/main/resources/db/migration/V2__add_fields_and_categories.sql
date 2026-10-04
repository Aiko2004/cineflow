-- Рефакторинг movie-service: новые поля + вынос категорий в отдельную сущность.
--
-- Порядок шагов намеренный: сначала переносим данные, потом удаляем старую колонку.
-- ddl-auto: update этого не умеет — он не знает, что category нужно ПЕРЕНЕСТИ,
-- а не просто удалить. Без этого шага все существующие категории были бы потеряны.

-- 1. Добавляем новые колонки в movies (все nullable — существующие строки получат NULL).
ALTER TABLE movies
    ADD COLUMN slug         VARCHAR(255),
    ADD COLUMN banner       VARCHAR(255),
    ADD COLUMN rating_age   VARCHAR(10),
    ADD COLUMN trailer      VARCHAR(255),
    ADD COLUMN release_date DATE;

-- slug используется в URL (/movies/:slug) — должен быть уникальным.
ALTER TABLE movies ADD CONSTRAINT uk_movies_slug UNIQUE (slug);

-- 2. Переименовываем posterUrl → poster (хранится имя файла, не URL).
ALTER TABLE movies RENAME COLUMN poster_url TO poster;

-- 3. Создаём таблицу категорий.
CREATE TABLE categories (
    id   BIGSERIAL    PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    CONSTRAINT uk_categories_name UNIQUE (name)
);

-- 4. Таблица связи many-to-many.
CREATE TABLE movie_categories (
    movie_id    BIGINT NOT NULL REFERENCES movies(id),
    category_id BIGINT NOT NULL REFERENCES categories(id),
    PRIMARY KEY (movie_id, category_id)
);

-- 5. Миграция данных: перекладываем строковые категории в новую структуру.
--    INSERT DISTINCT — не создаём дубликаты, если несколько фильмов одной категории.
INSERT INTO categories (name)
SELECT DISTINCT category
FROM movies
WHERE category IS NOT NULL AND category <> '';

--    Связываем каждый фильм с его категорией через join-таблицу.
INSERT INTO movie_categories (movie_id, category_id)
SELECT m.id, c.id
FROM movies m
         JOIN categories c ON c.name = m.category
WHERE m.category IS NOT NULL AND m.category <> '';

-- 6. Удаляем старую колонку — данные уже в categories/movie_categories.
ALTER TABLE movies DROP COLUMN category;

-- V3: индекс на FK-сторону movie_categories + backfill slug + NOT NULL

-- 1. Индекс на category_id.
--    PostgreSQL не создаёт индекс на ссылающуюся сторону FK автоматически.
--    Без индекса DELETE FROM categories вызывает seq scan по movie_categories под блокировкой.
CREATE INDEX idx_movie_categories_category_id ON movie_categories (category_id);

-- 2. Backfill slug для строк, где он NULL (фильмы, созданные до V2).
--
--    Алгоритм:
--      lower(title)                        → нижний регистр
--      regexp_replace([^a-z0-9]+, '-')     → спецсимволы и пробелы → дефис
--      trim('-')                           → обрезать дефисы по краям
--
--    Первый проход: строка получает base_slug, если:
--      — в текущем batch нет других строк с таким же base_slug (batch_count = 1)
--      — ни одна существующая строка уже не имеет этот slug
--    Остальные строки остаются NULL и обрабатываются вторым проходом.
WITH candidates AS (
    SELECT
        id,
        trim(BOTH '-' FROM
            regexp_replace(lower(title), '[^a-z0-9]+', '-', 'g')
        )                                                                  AS base_slug,
        COUNT(*) OVER (PARTITION BY
            trim(BOTH '-' FROM
                regexp_replace(lower(title), '[^a-z0-9]+', '-', 'g')
            )
        )                                                                  AS batch_count
    FROM movies
    WHERE slug IS NULL
)
UPDATE movies
SET    slug = c.base_slug
FROM   candidates c
WHERE  movies.id = c.id
  AND  c.base_slug != ''
  AND  c.batch_count = 1
  AND  NOT EXISTS (
           SELECT 1 FROM movies m2 WHERE m2.slug = c.base_slug
       );

-- Второй проход: оставшиеся NULL (дубли в batch или коллизия с существующим).
--    Суффикс '-id' гарантирует уникальность, т.к. id уникален.
--    COALESCE(NULLIF(..., ''), 'movie') защищает от пустого base_slug
--    (если title состоит только из спецсимволов, например '!!!').
UPDATE movies
SET    slug = COALESCE(
                 NULLIF(
                     trim(BOTH '-' FROM
                         regexp_replace(lower(title), '[^a-z0-9]+', '-', 'g')
                     ),
                     ''
                 ),
                 'movie'
             ) || '-' || id::text
WHERE  slug IS NULL;

-- 3. После backfill все строки заполнены — можно ставить NOT NULL.
ALTER TABLE movies ALTER COLUMN slug SET NOT NULL;

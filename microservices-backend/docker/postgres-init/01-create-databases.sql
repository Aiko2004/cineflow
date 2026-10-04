-- Создаёт логические БД для сервисов на Postgres.
-- Запускается companion-сервисом postgres-init на КАЖДЫЙ `docker compose up` (не только
-- на свежем томе). Идемпотентно: CREATE DATABASE выполняется только если БД ещё нет.
-- (CREATE DATABASE IF NOT EXISTS в Postgres нет — используем SELECT ... WHERE NOT EXISTS + \gexec.)

SELECT 'CREATE DATABASE movies'  WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'movies')\gexec
SELECT 'CREATE DATABASE theater' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'theater')\gexec
SELECT 'CREATE DATABASE auth'    WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'auth')\gexec
SELECT 'CREATE DATABASE booking' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'booking')\gexec

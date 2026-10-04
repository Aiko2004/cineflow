# CineFlow — короткие команды для локальной разработки.
#
# Makefile — тонкая обёртка: вся логика сборки остаётся в Gradle, инфраструктура — в
# docker-compose.yml. Здесь только запоминаемые имена для длинных команд.
#
# Windows: запускать из Git Bash (или WSL). make ставится через `scoop install make`
# или `choco install make`. В PowerShell/cmd нет awk/sh, на которые опираются цели.

# .env подхватывается автоматически и экспортируется в окружение Gradle/Docker —
# сервисы при bootRun видят те же DB_PORT/REDIS_PORT, что и docker compose.
-include .env
export

# Если host-порты контейнеров переопределены (нативный Postgres/Redis на хосте),
# сервисам нужен тот же порт. Выводим его, чтобы не задавать дважды.
ifdef DB_HOST_PORT
DB_PORT ?= $(DB_HOST_PORT)
endif
ifdef REDIS_HOST_PORT
REDIS_PORT ?= $(REDIS_HOST_PORT)
endif

GRADLE  := ./gradlew
COMPOSE := docker compose

SERVICES := eureka-server api-gateway movie-service theater-service \
            screening-service auth-service booking-service

.DEFAULT_GOAL := help
.PHONY: help infra-up infra-down infra-reset infra-ps infra-logs \
        build test clean psql redis-cli mongo-shell

help: ## Список команд
	@awk 'BEGIN {FS = ":.*## "} /^[a-zA-Z_%-]+:.*## / {printf "  \033[36m%-18s\033[0m %s\n", $$1, $$2}' $(MAKEFILE_LIST)
	@echo ""
	@echo "  Сервисы: $(SERVICES)"
	@echo "  Порядок запуска: run-eureka-server -> остальные (каждый в своём терминале)"

# ---------- Инфраструктура ----------

infra-up: ## Поднять Postgres, Mongo, Redis (+ создание БД)
	$(COMPOSE) up -d

infra-down: ## Остановить контейнеры (данные сохраняются)
	$(COMPOSE) down

infra-reset: ## Снести контейнеры ВМЕСТЕ с томами и поднять заново (данные удаляются)
	@read -p "Удалить все локальные данные (Postgres/Mongo/Redis)? [y/N] " ans && [ "$$ans" = "y" ]
	$(COMPOSE) down -v
	$(COMPOSE) up -d

infra-ps: ## Статус контейнеров
	$(COMPOSE) ps

infra-logs: ## Логи инфраструктуры (Ctrl+C — выход)
	$(COMPOSE) logs -f

# ---------- Сборка и тесты ----------

build: ## Собрать все модули (с тестами)
	$(GRADLE) build

test: ## Прогнать все тесты (нужен запущенный Docker — Testcontainers)
	$(GRADLE) test

test-%: ## Тесты одного модуля: make test-booking-service
	$(GRADLE) :$*:test

clean: ## Очистить build/ во всех модулях
	$(GRADLE) clean

# ---------- Запуск сервисов ----------

run-%: ## Запустить сервис: make run-movie-service
	$(GRADLE) :$*:bootRun

# ---------- Доступ к БД ----------

DB ?= postgres
psql: ## Консоль Postgres: make psql DB=booking
	docker exec -it postgres-cineflow psql -U $${DB_USER:-postgres} -d $(DB)

redis-cli: ## Консоль Redis
	docker exec -it redis-cineflow redis-cli

mongo-shell: ## Консоль Mongo (БД screening)
	docker exec -it mongo-cineflow mongosh -u $${MONGO_USER:-root} -p $${MONGO_PASSWORD:-root} --authenticationDatabase admin screening

# CineFlow — текущее состояние

> Документ для передачи контекста в новый чат. Обновлять по ходу работы.
> Последнее обновление: 1 октября 2026 (закрыты 5 долгов: gRPC-версии, /scroll, идемпотентный init, theater на Flyway, решение по gRPC-порту; сквозной прогон на чистых томах).

## Как работаем

**Режим:** Claude пишет код с best practices и объясняет принятые решения. Айкын читает, докапывается, спрашивает где непонятно. System design обсуждаем совместно **до** реализации. Новые технологии (Kafka, Redis-локи, saga) — сначала концепция, потом код.

Предыдущий режим («пишу сам, Claude только подсказывает») не сработал: слишком медленно, Сократовские вопросы применялись в том числе к рутине вроде конфигов Gradle, где учиться нечему.

## Структура репозитория

```
D:\web-projects\CineFlow\     ← корень git-репозитория (git init делает пользователь)
├── README.md                ← портфолио-обзор (англ.), бейдж CI (плейсхолдер <owner>/<repo>)
├── LICENSE                  ← MIT (Aikyn, 2026)
├── .gitignore / .gitattributes  ← корневые, на весь монорепо
├── .github\
│   ├── workflows\backend-ci.yml ← CI: push/PR в main, paths-фильтр microservices-backend/**
│   └── dependabot.yml       ← gradle + github-actions, weekly
├── docs\
│   ├── PROJECT_PLAN.md      ← архитектура, roadmap, доменная модель
│   ├── AUTH_DESIGN.md / BOOKING_DESIGN.md / STUDY_GUIDE.md
│   └── CURRENT_STATE.md     ← этот файл
└── microservices-backend\   ← Gradle monorepo (rootProject.name = "microservices-backend")
    ├── build.gradle.kts     ← версии плагинов объявлены здесь один раз
    ├── settings.gradle.kts
    ├── docker-compose.yml
    ├── contracts\           ← общий модуль межсервисных контрактов (библиотека, не Boot-приложение)
    ├── eureka-server\       ← порт 8761
    ├── api-gateway\         ← порт 8080
    ├── movie-service\       ← порт 8081, Postgres (БД movies)
    ├── theater-service\     ← порт 8082, Postgres (БД theater)
    ├── screening-service\   ← порт 8083, MongoDB (+ gRPC-сервер 9083)
    ├── auth-service\        ← порт 8084, Postgres (БД auth) + Redis
    └── booking-service\     ← порт 8085, Postgres (БД booking) + Redis
```

Frontend пока не начат. Оригинальный Next.js-фронтенд: https://github.com/TeaCoder52/teacinema-public

## Что готово

**eureka-server** — работает, регистрирует сервисы.

**api-gateway** — работает, роутит через Eureka (`discovery.locator.enabled: true`). Использует `spring-cloud-starter-gateway-server-webmvc` (сервлетный, не реактивный).

**movie-service** — работает end-to-end (напрямую на 8081 и через Gateway). Есть `Movie` (id, title, description, posterUrl, category), `MovieRepository`, `MovieController` с GET/POST `/movies`.
⚠️ **Долг:** сущность не соответствует реальному контракту. Нужно добавить `slug`, `banner`, `ratingAge`, `trailer`, `releaseDate`; переименовать `posterUrl` → `poster` (хранится имя файла, не URL); категории вынести в отдельную сущность.

**theater-service** — написан полностью: `Theater`/`Hall`/`Seat` + repositories + DTO (records) + services + controllers + кастомные исключения + `GlobalExceptionHandler` + springdoc OpenAPI. Схема — на **Flyway + `ddl-auto: validate`** (V1 baseline снят с реальной БД через pg_dump; `baseline-on-migrate: true` → один конфиг работает и на пустой, и на существующей БД). Защищён как ресурсный сервис (GET публичны, запись под токен).

**contracts** — чистая библиотека (без Spring Boot plugin), содержит `SeatType` (NORMAL, VIP, UNKNOWN с `@JsonEnumDefaultValue`).

**screening-service** — реализован полностью: документ `Screening` + снапшоты (records), три `@CompoundIndex`, `ScreeningRepository`, DTO, сервис, контроллер, исключения + `GlobalExceptionHandler`, аудит через `@EnableMongoAuditing`. Feign-клиенты к `movie-service` и `theater-service` с Resilience4j (circuit breaker + semaphore bulkhead) через `ScreeningEnrichmentService`. Реализована валидация `endAt > startAt` и проверка пересечения сеансов в зале.

**auth-service** — реализован по `docs/AUTH_DESIGN.md`. Вход по OTP на email, RS256 JWT + JWKS, refresh-ротация с обнаружением кражи (`family_id`). Postgres (`users`, `refresh_tokens`, Flyway V1) + Redis (OTP-коды, rate limit). Эндпоинты: `/auth/otp/send` (202), `/auth/otp/verify`, `/auth/refresh`, `/auth/logout`, `/.well-known/jwks.json`. Слои как у остальных сервисов: DTO-records, кастомные исключения + `GlobalExceptionHandler`, `@RequiredArgsConstructor`, `@ConfigurationProperties` (`AuthProperties`). Подпись — Nimbus напрямую (не resource-server): сервис **выпускает** токены. ⚠️ Открытый вопрос: ключ генерируется при старте (эфемерный) — для прода нужен внешний источник (см. AUTH_DESIGN.md §9).

**Защита периметра и сервисов** — по AUTH_DESIGN.md §8. Gateway стал resource-server'ом: **явные маршруты под `spring.cloud.gateway.server.webmvc.routes`** (у сервлетного gateway это единственный рабочий namespace; discovery-locator в нём отсутствует), публично только `/auth/**`, `/.well-known/**` и GET-чтения; всё остальное требует токен. **Ресурсные сервисы закрыты все: movie, theater, screening, booking** (GET-чтения публичны, запись — под токен; рецепт в `CLAUDE.md`). Проверено вживую: POST на theater/screening без токена → 401, GET → 200. У screening защищён только HTTP-слой — gRPC-сервер (9083) внутренний, не затрагивается.

**booking-service** — реализован по `docs/BOOKING_DESIGN.md` (первый инкремент, без оплаты). Postgres (`bookings`, `booking_seats`, Flyway V1 с частичным индексом `uk_seat_taken`) + Redis (мягкие удержания). Продажа места один раз держится частичным уникальным индексом; переходы статусов — условным UPDATE; идемпотентность по `Idempotency-Key`; `user_id` из `sub`; чужая бронь → 404; QR — `SecureRandom`; фоновый уборщик просроченных PENDING. App-assigned UUID + коллекция → `Booking implements Persistable`. Эндпоинты: `/bookings/seats/hold`, `POST /bookings`, `/bookings/{id}/confirm|cancel`, `/bookings/@me`, `GET /bookings/{id}`.

**Первый gRPC в проекте** — `booking → screening` `GetScreening` (валидация сеанса + денормализация одним вызовом). `.proto` в `contracts`, стабы генерит `com.google.protobuf`-плагин; screening поднимает `io.grpc.Server` на 9083 через `SmartLifecycle`; booking зовёт blocking-стаб под CB/bulkhead/retry. Feign к theater (места) — по конвенции. Детали и грабли версий — в `CLAUDE.md` (раздел gRPC).

**Сборка** — `./gradlew build` проходит целиком, **включая Testcontainers-тесты (89 тестов, 0 падений: auth 13, movie 47, screening 19, booking 10)** — прогнано с живым Docker. Версии плагинов только в корне (добавлен `com.google.protobuf` apply false; Kotlin-плагин удалён — проект на чистой Java).

**Инфраструктура:** Postgres, MongoDB и Redis в Docker (`docker-compose.yml`). Логические БД (`movies`/`theater`/`auth`/`booking`) создаёт **companion-сервис `postgres-init`** на каждый `docker compose up` (depends_on healthcheck postgres, идемпотентный `\gexec`-скрипт) — работает и на свежем, и на существующем томе (прежний initdb.d отрабатывал только на свежем). Конфиг через `${VAR:default}` + `.env`, `.env-example` в репозитории. ⚠️ Нативные Postgres/Redis на 5432/6379 → поднимать с `DB_HOST_PORT=5433 REDIS_HOST_PORT=6380` + сервисам `DB_PORT=5433 REDIS_PORT=6380`.

## Принятые решения и их причины

| Решение | Причина |
|---|---|
| Монорепо (Gradle multi-module) | Один PR часто трогает несколько сервисов; CI с path-фильтрами всё равно собирает только изменённое. В проде обычно полирепо — это осознанный компромисс для соло-разработки |
| ID: `Long` в theater-service, UUID в screening-service | Контракт фронтенда требует `string` (UUID) везде, но theater-service уже написан на `Long`. Решили не переписывать сейчас — унифицируем, когда разнородность реально заболит на стыке с Booking Service |
| UUIDv7 (не v4) для screening | Time-ordered: глобально уникален без координации, но монотонно растёт → дружелюбен к индексам. Библиотека `com.github.f4b6a3:uuid-creator` |
| Screening в MongoDB как денормализованный документ | DDD-обоснование из курса: расписание — самый горячий read-путь, документ уже готов к отдаче без join'ов и синхронных вызовов в другие сервисы |
| Обогащение Screening: Feign при создании + Kafka на обновления | Снапшот нужен сразу (синхронно), но источник правды может измениться позже → нужен механизм догоняющего обновления |
| `SeatType` в `contracts`, не дублировать | Тип пересекает границы сервисов (theater → screening → booking) — это общий язык системы. В `contracts` идёт **только** то, что физически пересекает границу: enum'ы и DTO событий. Никаких `@Entity`, `@Component`, утилит — иначе получится распределённый монолит |
| `UNKNOWN` + `@JsonEnumDefaultValue` в enum | Сервисы деплоятся не одновременно. Без этого старый consumer упадёт на незнакомом значении и заблокирует партицию Kafka (poison pill) |
| FK-констрейнты внутри theater-service | Все три таблицы в одной БД одного сервиса — межсервисной границы нет, FK бесплатен. `@ManyToOne(LAZY)` даёт констрейнт без риска N+1 |
| `@Getter @Setter @NoArgsConstructor`, не `@Data` на entity | `@Data` генерит `equals`/`hashCode`/`toString` по всем полям → ломается на lazy-связях и на сравнении до/после сохранения |
| DTO (records) отдельно от entity | Иначе клиент может прислать `id` и переписать чужую запись (mass assignment), и схема БД жёстко связывается с контрактом API |
| REST/Feign по умолчанию, gRPC только там, где есть причина | gRPC — для Media Service (кросс-язык, Go) и Booking↔Screening (учебная практика). Везде подряд — усложнение без выигрыша |
| Media Service остаётся на Go | Нагрузка I/O-bound (S3, сеть), переписывание на C++/Rust ничего не даст. Интеграция с чужим языком через gRPC — сама по себе ценный опыт |
| auth-service на UUIDv7 (не `Long`) | Последовательные id аккаунтов утекают число пользователей и позволяют перебор чужих. Сервис новый, миграции нет — платить за UUID не приходится |
| Вход только по OTP, без пароля | Нечего утекать и перебирать, не нужна политика сложности и сброс. Код в Redis с TTL 5 мин, хранится хеш |
| Подпись RS256, не HS256 | При симметричном секрете любой сервис, умеющий проверять токен, умеет и подделать. RS256: приватный ключ только у auth, остальные берут публичный из JWKS |
| Refresh-ротация с `family_id` | Одноразовый refresh + обнаружение повторного использования. Предъявление отозванного токена = кража → отзыв всей family, а не всех сессий пользователя |
| Проверка токена и на Gateway, и в сервисе | Defense in depth. Только периметр — «твёрдая скорлупа при мягкой сердцевине»: любой внутри сети ходит куда угодно. Проверка в сервисе — три строки конфига |
| Gateway: явные маршруты вместо `discovery.locator` | За периметром безопасности нужно точно знать, какой путь публичный. Локатор отдавал бы `/{service-id}/**` для всех — список публичных путей не выразить чисто |
| Продажа места: частичный уникальный индекс `uk_seat_taken` | Единственная настоящая гарантия «одно место — один раз». `WHERE released_at IS NULL` разрешает перепродажу после отмены, чего обычный UNIQUE не даёт. Redis-удержания и уборщик — оптимизация, не гарантия |
| Переходы статусов брони — условным UPDATE | `WHERE id=? AND status=?`: два параллельных запроса не пройдут оба (в отличие от read-check-write). Правила — в enum `BookingStatus` |
| Первый gRPC (booking→screening), остальное Feign | Самый частый внутренний вызов + учебная практика с protobuf. gRPC везде — усложнение без выигрыша |
| gRPC-версии выровнены на BOM (1.83.1) **везде** | Spring Boot BOM тянет grpc-core 1.83.1. И `grpc-netty-shaded` в сервисах, и grpc-stub/protobuf/protoc-gen-grpc-java в `contracts` (BOM не применяется → пин явный) держим 1.83.1. Иначе генерённый код компилируется против одной версии, исполняется на другой (`AbstractMethodError`). protobuf-java рантайм (4.35.1) может быть старше generated (4.28.3) — forward-совместимо |
| gRPC-порт 9083 — внутренний, bind на loopback | Не за gateway, не публикуется в compose; по умолчанию `127.0.0.1` (`NettyServerBuilder.forAddress`) → недоступен из LAN. «Внутренний» — факт, не допущение. mTLS избыточен для этой модели угроз; шов в `GrpcServer`. Обоснование — BOOKING_DESIGN.md §5 |
| `Booking implements Persistable` | App-assigned UUID + коллекция: без `isNew()` `save()` уходит в `merge()` и ломает каскад. Persistable форсит `persist()` |

## Незакрытые вопросы и известные долги

**screening-service:**

- **`screeningDate` считается в UTC**, а расписание кинотеатр показывает по местному времени. Для UTC+5 сеансы с 00:00 до 05:00 попадут в предыдущую дату. Правильное решение — таймзона кинотеатра или явное поле от клиента. Текущее — осознанная заглушка.
- **slug/banner в MovieSnapshot — null**: movie-service пока не возвращает эти поля (технический долг в movie-service). Заполнятся после рефакторинга movie-service.

**Тесты:** ~~`./gradlew build` не выполняет ни одного теста~~ ✅ неактуально — сейчас 89 тестов на Testcontainers + слайсах (`@DataMongoTest`, `@WebMvcTest`, `@SpringBootTest`), все зелёные. theater-service пока без тестов (единственный модуль без покрытия).

**movie-service** (найдено на ревью миграции V2):

- **Нет индекса на `movie_categories.category_id`.** PK `(movie_id, category_id)` покрывает поиск по `movie_id`, но не по `category_id`. PostgreSQL не индексирует ссылающуюся сторону FK автоматически — без индекса удаление категории вызывает последовательный скан join-таблицы под блокировкой.
- ~~**`slug` = `NULL` у строк, существовавших до V2.**~~ ✅ **Закрыто.** Миграция V3: backfill slug из title, затем `NOT NULL`. Алгоритм Java (MovieService) приведён к поведению V3: тот же fallback «movie», те же суффиксы при коллизиях. Retry срабатывает только на `uk_movies_slug`; FK, NOT NULL и прочие нарушения пробрасываются немедленно. Slug ограничен 200 символами — запас до VARCHAR(255). Тесты: SlugTest (юнит), MovieServiceRetryTest (юнит, Mockito), MovieServiceCollisionTest (интеграция, Testcontainers), V3BackfillMigrationTest (programmatic Flyway).
- **V2 дропает `category` в той же миграции, где переносит данные** — откат приложения после её применения невозможен. Ретроспективно не чиним (миграция уже накатана, редактировать нельзя — checksum). Правило expand-contract зафиксировано в `CLAUDE.md` для будущих миграций.
- **`@ManyToMany`**: при изменении коллекции Hibernate удаляет все строки фильма из join-таблицы и вставляет заново, а не делает точечный DELETE. Если в связь понадобится атрибут (например, «основная категория») — придётся переделывать в отдельную сущность.

**Общее:**

- Каскады при удалении `Theater` с залами: сейчас FK просто отклонит удаление. Нужно ли каскадное удаление?
- Bean Validation (`@NotBlank`) на entity theater-service — сейчас только `@Column(nullable = false)`, что не ловит пустую строку.
- `@Transactional(readOnly = true)` на read-методах сервисов — пока нигде нет.
- Формат схемы мест в Angular: canvas (как в оригинале) или SVG/CSS-grid.
- RabbitMQ поднимать в compose сразу или к фазе 6.

## Принятые решения: Spring Boot 4 — переименования стартеров

| Spring Boot 3 | Spring Boot 4 | Статус |
|---|---|---|
| `spring-boot-starter-web` | `spring-boot-starter-webmvc` | применено |
| `spring-boot-starter-aop` | `spring-boot-starter-aspectj` | применено |
| тестовые стартеры не разделены | `spring-boot-starter-data-jpa-test` и т.д. | применено |

## Следующий шаг (актуальный)

**Подготовка к первому коммиту в публичный GitHub (5 окт).** `./gradlew build` зелёный после уборки (52 задачи executed). `git init` делает пользователь сам — корень репо `D:\web-projects\CineFlow` (не `microservices-backend`).

Что сделано:
1. **Мусор модулей-как-отдельных-проектов удалён** (35 объектов): per-module `.gitignore`/`.gitattributes`/`HELP.md`/`*.iml`/`.gradle\` в 6 модулях; `microservices-backend/.idea/` целиком; `screening-service/compose.yaml` (устаревший Initializr-скелет — `mongo:latest`, БД `mydatabase`, ничего уникального относительно корневого `docker-compose.yml`); `microservices-backend/.gitignore` и `README.md` (устаревший скелет чужого проекта «booking-marketplace» — не переносился). auth/booking таких файлов не имели.
2. **Корневые `.gitignore` и `.gitattributes`** в `CineFlow/`. gitignore: `.env` на любом уровне (но `!.env-example`), `build/`, `.gradle/`, `.idea/`, `*.iml`, `.vscode/`, `HELP.md`, логи, OS-мусор, задел под frontend (`node_modules/`, `dist/`, `.angular/`); `gradle-wrapper.jar` НЕ игнорируется. gitattributes: `* text=auto`, `gradlew`/`*.sh` → `eol=lf` (иначе Linux-CI падает на `sh\r`), `*.bat` → `eol=crlf`, `*.jar` binary.
3. **README.md** (англ., портфолио): что это, таблица сервисов, mermaid-диаграмма (target, с пометкой что не построено), стек, 8 ключевых инженерных решений со ссылками на `docs/*_DESIGN.md`, запуск локально, честный статус. **LICENSE** — MIT (Aikyn, 2026).
4. **CI** `.github/workflows/backend-ci.yml`: push/PR в `main`, paths-фильтр `microservices-backend/**` + сам workflow, concurrency+cancel, ubuntu-latest, timeout 30, `permissions: contents: read`, job `build` (стабильное имя под branch protection), `checkout@v7` + `setup-java@v6` (temurin 21) + `gradle/actions/setup-gradle@v6`, `./gradlew build --no-daemon` в `microservices-backend`, при падении `upload-artifact@v7` с `**/build/reports/tests/`. Секретов нет — Testcontainers на дефолтах. Версии actions сверены вживую (окт 2026).
5. **Dependabot** `.github/dependabot.yml`: gradle (`/microservices-backend`) + github-actions (`/`), weekly, Spring сгруппирован в один PR, limit 5.

Проверка: build зелёный; скан секретов — хардкода нет, все пароли через `${VAR:default}`, файлов `*.pem/*.jks/*.p12` нет; `microservices-backend/.env` (только `MONGO_USER=root`/`MONGO_PASSWORD=root`) покрыт `.gitignore`. YAML обоих workflow валиден (PyYAML; actionlint в окружении недоступен).

---

**Предыдущий шаг (завершён): уборка сборки и репозитория (4 окт).** `./gradlew build` зелёный — **89 тестов** (auth 13, movie 47, screening 19, booking 10), 0 ошибок.

Что сделано:
1. **Kotlin-плагин удалён** из корневого `build.gradle.kts` (`kotlin("jvm") version "2.3.21" apply false` и `apply(plugin = "org.jetbrains.kotlin.jvm")` из subprojects). Проект на чистой Java, `.kt`-файлов нет.
2. **Gradle wrapper'ы модулей удалены** из 6 модулей (eureka-server, api-gateway, movie-service, theater-service, screening-service, contracts): `gradlew`, `gradlew.bat`, `gradle/wrapper/`. auth-service и booking-service wrapper'ов не имели.
3. **Модульные docker-compose/env удалены**: `movie-service/{docker-compose.yml,.env,.env-example}`, `theater-service/{docker-compose.yml,.env,.env-example}`. Имена БД имеют дефолты в `application.yaml` каждого сервиса и в `.env-example` не добавляются (там объяснено почему). Переменные: `MOVIE_DB_NAME`, `THEATER_DB_NAME`, `AUTH_DB_NAME`, `BOOKING_DB_NAME` — с префиксом сервиса, так как корневой `.env` общий на весь монорепо.
4. **`eureka-server/.idea/` удалена** (вложенная IDE-конфигурация).
5. **`microservices-backend/.gitignore` создан** (корневого не было; `.env` теперь покрыт на уровне monorepo-root).
6. **Makefile на Windows:** `make` не входит в Git for Windows — нужно установить отдельно (`scoop install make` / `choco install make`). Пометка в `CLAUDE.md` обновлена.

---

**Предыдущий шаг (завершён): 5 долгов закрыты (1 окт), сквозной прогон на ЧИСТЫХ томах.**

1. **gRPC-версии.** `contracts` выровнен 1.68.1 → **1.83.1** (= версии BOM). Весь classpath однороден; генерённый код больше не компилируется против одной версии и исполняется на другой.
2. **/scroll 500 на пустой.** Оказался не реальным багом — симптом уже исправленного mongo-auth. Пустой случай отдаёт 200 `{content:[],nextCursor:null}`; подтверждено юнит-тестом (`emptyCollection_noCursor_returnsEmptyPage_notError`) и вживую по HTTP.
3. **Идемпотентный init БД.** companion-сервис `postgres-init` создаёт недостающие БД на каждый `up` (и на свежем, и на существующем томе). Проверено: удаление `booking` на существующем томе → `up` восстанавливает.
4. **theater на Flyway + validate.** V1 baseline снят с реальной БД (pg_dump). `baseline-on-migrate: true`: на существующей БД — baseline v1 (данные целы), на пустой — V1 создаёт схему. Оба сценария проверены.
5. **gRPC-порт 9083.** Решение: внутренний, bind на loopback (`grpc.server.host=127.0.0.1`), не за gateway, не публикуется. Зафиксировано в `CLAUDE.md` и `BOOKING_DESIGN.md §5` (mTLS — если пересечёт границу доверия).

**Сквозной прогон с нуля (`docker compose down -v` → `up`):** companion создал 4 БД → theater прогнал Flyway V1 на пустой БД → screening gRPC на `127.0.0.1:9083` → токен → фильм/кинотеатр/зал/место/сеанс → hold → бронь (gRPC+Feign) → confirm (+qrCode) → @me → cancel → rebook того же места (201). Проект поднимается с нуля по инструкции из `CLAUDE.md`.

---

**Предыдущий шаг (завершён): booking-service (первый инкремент) + первый gRPC.** См. `docs/BOOKING_DESIGN.md`, разделы «gRPC» и «Booking» в `CLAUDE.md`.

Тесты booking (Testcontainers, 10 шт.):
- **Гонка мест:** два параллельных бронирования одного места → ровно одно проходит, второе 409. Сначала показано, что БЕЗ индекса тест падает (`expected 1 but was 2` — двойная продажа), затем с индексом зелёный.
- Отмена освобождает место (rebook проходит); истечение удержания → EXPIRED + release (и уборщиком, и проверкой доступности до уборщика); идемпотентность по ключу; confirm отменённой → 409; чужая бронь → 404; Redis-удержание (NX + снятие только владельцем, откат частичного захвата).

**Сквозной прогон через Gateway (реальные gRPC + Feign):** токен → hold места → `POST /bookings` (201 PENDING, снапшоты из gRPC-сеанса + место из Feign, цена посчитана) → повтор с тем же `Idempotency-Key` (та же бронь) → без токена 401 → confirm (CONFIRMED + qrCode из SecureRandom) → `/bookings/@me` → cancel (CANCELLED) → rebook того же места (201, место освободилось). Всё вернулось как ожидалось.

**Пре-существовавшие баги, вскрытые живым прогоном и починенные** (тесты-слайсы их не ловили — падал только полный старт):
- **Boot 4 Mongo:** строка подключения — `spring.mongodb.uri`, а не `spring.data.mongodb.uri` (иначе `credential=null` → `Command requires authentication`). Исправлено в screening; проверено рестартом без env-override.
- **Boot 4 Jackson 3:** `read-unknown-enum-values-using-default-value` переехало в `spring.jackson.datatype.enum.*` (иначе старт падает `No enum constant … DeserializationFeature`). Исправлено в screening и theater.
- ~~theater-service `ddl-auto: update` против конвенции~~ ✅ **Закрыто** (1 окт): Flyway V1 baseline + `validate` + `baseline-on-migrate: true`.

**Предыдущий шаг (завершён): Безопасность (Фаза 1).** auth-service + защита gateway/movie-service. См. `docs/AUTH_DESIGN.md` и раздел «Аутентификация и защита сервисов» в `CLAUDE.md`.

Принятые решения и инварианты (не переобсуждать):
- Вход по OTP на email, без пароля. Код в Redis (`otp:{email}`, Hash `{codeHash, attempts}`, TTL 5 мин), хранится хеш.
- `/auth/otp/send` всегда 202 — защита от user enumeration. OTP одноразовый. Лимит попыток → удаление ключа. Два rate limit: email (5/15м) и IP (20/15м).
- JWT RS256, публичные ключи через `/.well-known/jwks.json`. access 15 мин, refresh 30 дней.
- Refresh-ротация с `family_id`: повторное использование отозванного токена отзывает всю family.
- Проверка токена и на Gateway, и в сервисе. Gateway — явные маршруты (`discovery.locator` выключен).

Тесты auth-service (**весь `./gradlew build` зелёный с Testcontainers: 78 тестов — auth 13, movie 47, screening 18**):
- `JwtServiceTest` (юнит) — валидация токена, отказ на просроченном и на подделанной подписи, JWKS без приватной части.
- `OtpIntegrationTest` (Testcontainers: Postgres + Redis) — одноразовость кода, исчерпание попыток, оба rate limit.
- `RefreshRotationIntegrationTest` (Testcontainers) — login, ротация цепочки, повтор отзывает family, logout, **конкурентность** (два параллельных `rotate()` одного токена).

### Hardening ротации/rate limit/OTP (сессия 26.09, вторая половина)

Прогон ранее написанных тестов вскрыл дефекты, которые никогда не выполнялись (Docker был недоступен). Что чинили:

**Инфраструктура тестов и сборки:**
- `@Container static` в общем базовом классе останавливал контейнеры в `afterAll` первого тест-класса → второй класс переиспользовал кешированный Spring-контекст и падал с `RedisCommandTimeoutException`. → **singleton-контейнеры** (старт в `static`-блоке, Ryuk чистит на выходе JVM).
- `org.testcontainers:mongodb` не резолвился: в Testcontainers 2.0 (тянет Boot 4.1.1) артефакты переименованы в `testcontainers-*`. → `testcontainers-mongodb`.

**Три правки в auth (первая критичная):**
1. **Гонка в `RefreshTokenService.rotate()`.** Было `read → isRevoked() → save` — два параллельных запроса с одним refresh-токеном оба успешно обновлялись (два валидных токена, кража не обнаруживалась). Тест `concurrentRotate_*` на старом коде падал: `expected 1 succeeded but was 2`. → отзыв через **атомарный условный UPDATE** `revokeIfActive(id, now)` (`WHERE id=:id AND revoked_at IS NULL`): 0 строк = кто-то уже отозвал = повтор/гонка → `revokeFamily` + 401. Арбитр — БД (Postgres EvalPlanQual при READ COMMITTED), а не Java-снимок. Ровно один rotate выигрывает.
   - Попутно: отзыв family выполнялся в той же `@Transactional`, что и `throw`, — откат отменял отзыв (family не отзывалась). → вынесено в `REQUIRES_NEW` (`TransactionTemplate`).
2. **`RateLimitService`.** INCR и EXPIRE двумя командами: обрыв между ними оставлял ключ без TTL — блок навсегда. → один **Lua-скрипт** (`DefaultRedisScript`): INCR + EXPIRE (EXPIRE только при первом обращении).
3. **`OtpService.send()`.** `putAll()` + `expire()` неатомарны (код мог остаться без TTL). → **Lua-скрипт** HSET+EXPIRE. Модель Hash сохранена осознанно: `attempts` инкрементируется атомарным HINCRBY (в одной строке был бы read-modify-write).
   - Сравнение хешей OTP — через `MessageDigest.isEqual()` (константное время) вместо `equals()` (timing attack).

**Сквозной прогон вживую (первый раз) — прошёл полностью.** `docker compose up` → eureka → auth → movie → gateway, весь путь через Gateway :8080:
- `POST /auth/otp/send` → `202 {expiresIn:300}`, код взят из лога;
- `POST /auth/otp/verify` → `200`, access (JWT `roles:[ROLE_USER]`) + refresh;
- `POST /movies` без токена → `401`, с токеном → `201`;
- `POST /auth/refresh` → новый refresh; повтор старого → `401` + в логе `reuse detected … revoked 1 active token(s)`; новый refresh той же family после этого тоже `401` (family отозвана).

**Что вскрылось на живом прогоне и починено:**
- **Gateway: неверный namespace маршрутов.** У сервлетного `gateway-server-webmvc` префикс — `spring.cloud.gateway.server.webmvc.routes`, а не `spring.cloud.gateway.routes` (реактивный). Маршруты молча игнорировались → 404 → dispatch на `/error` → security отдавал 401, маскируя причину. discovery-locator в этом варианте отсутствует как свойство — опираемся только на явные маршруты. **Это объясняет, почему прежняя запись «gateway роутит через discovery.locator» никогда не подтверждалась вживую.**
- **Конфликт портов с нативными службами.** На машине запущены нативные PostgreSQL 17 (0.0.0.0:5432) и Redis (0.0.0.0:6379), Docker слушал на IPv6 → `localhost` у приложений попадал в нативные службы (ошибка `password authentication failed`). → host-порты контейнеров параметризованы (`DB_HOST_PORT`/`REDIS_HOST_PORT`, дефолт прежний 5432/6379); для прогона контейнеры подняты на 5433/6380, сервисам передан `DB_PORT`/`REDIS_PORT`. Задокументировано в `.env-example`.

**Предыдущий шаг (завершён): пагинация** — реализована во всех трёх сервисах.

Принятые решения:
- Ограничение `max-page-size: 100` через `application.yaml` (не в коде) — применяется автоматически через `PageableHandlerMethodArgumentResolver`.
- `PageResponse<T>` — для movie-service и theater-service (клиенту нужен totalElements для навигации).
- `SliceResponse<T>` — для screening: расписание кинотеатра на дату и список сеансов фильма. Почему Slice: count-запрос избыточен — никто не рендерит «страница 3 из 17» для расписания на конкретный день.
- `CursorPageResponse<T>` + `/scroll` endpoint — keyset-пагинация для бесконечной прокрутки на фронте. Курсор = `base64url(startAt + "|" + id)`. id входит обязательно: `startAt` не уникален, без id порядок при одинаковом времени старта недетерминирован → дубли или пропуски на границе страниц.
- Keyset устойчив к вставкам: элемент, вставленный ДО курсора, не дублируется; вставленный ПОСЛЕ — появляется на следующей странице. Offset при вставке до позиции сдвигает всё — дубль гарантирован.
- Форма всех трёх типов ответов зафиксирована в `CLAUDE.md`.

Тесты: `MovieServiceTransactionalTest` (обновлён для PageResponse), `MovieControllerPaginationTest` (@WebMvcTest — проверяет cap на 100), `ScreeningKeysetTest` (@DataMongoTest + Testcontainers — 7 тестов: базовые сценарии, стабильность при вставке до/после курсора, невалидные курсоры).

**Предыдущий шаг (завершён): movie-service рефакторинг** — Flyway V1/V2/V3, категории, slug, retry. Все долги по slug закрыты.

Принятые решения по slug и коллизиям:
- `MovieService.create()` генерирует slug из title через `baseSlugFromTitle()` (тот же алгоритм, что V3-миграция: lowercase, спецсимволы → дефис, trim, fallback «movie», обрезка до 200 символов).
- Если slug задан явно — используется как есть (не суффиксируется при коллизии).
- Коллизии при авто-генерации разрешаются через retry: каждая попытка — отдельная REQUIRES_NEW транзакция.
- Retry срабатывает **только** на `uk_movies_slug` (проверяется через `ConstraintViolationException.getConstraintName()`). Остальные DIVE пробрасываются немедленно.
- `GlobalExceptionHandler` обрабатывает исчерпавший retry `DataIntegrityViolationException` → 409 Conflict.

Дальше по плану обучения:

1. **Блокировки** — частично затронуто в booking: частичный уникальный индекс (арбитр БД вместо блокировки), условный UPDATE переходов, Redis-удержания. Ещё не делали: оптимистичную (`@Version`) и пессимистичную (`SELECT FOR UPDATE`).
2. **Кэширование** — Redis, cache-aside, инвалидация, TTL, cache stampede.
3. **Kafka** — consumer groups, оффсеты, at-least-once vs exactly-once, идемпотентность, poison pill. Первое применение: обновление снапшотов в screening-service.
4. ~~**Безопасность** — JWT, Spring Security, проверка токена на gateway (Фаза 1 плана).~~ ✅ **Сделано** (auth-service + gateway; все ресурсные сервисы закрыты: movie/theater/screening/booking). Осталось: вынести ключ подписи во внешний источник; отправка кода через Notification Service; чистка протухших `refresh_tokens`; Keycloak для admin.
5. **Наблюдаемость** — структурные логи, correlation id сквозь сервисы, метрики, трассировка.

## Архив: предыдущий шаг (выполнено)

**Незавершённый рефакторинг сборки.** Причина была в том, что у каждого модуля был свой `settings.gradle.kts` — Gradle считает такую папку корнем отдельной сборки, поэтому IntelliJ импортировал модули как независимые проекты, и `:contracts` из `theater-service` не резолвился.

Конфиги уже переписаны (root объявляет версии один раз, модули применяют плагины без версий, Spring Boot не применяется к `contracts`). **Осталось удалить вручную:**

```
eureka-server\settings.gradle.kts
api-gateway\settings.gradle.kts
movie-service\settings.gradle.kts
theater-service\settings.gradle.kts
screening-service\settings.gradle.kts
contracts\settings.gradle.kts
contracts\src\main\resources\application.yaml
contracts\src\test\java\com\cineflow\contracts\ContractsApplicationTests.java
theater-service\src\main\java\com\cineflow\theater\model\SeatType.java
```

Затем в IntelliJ: панель Gradle → убрать все проекты кроме `microservices-backend` → Reload.

**После этого** — реализация `screening-service`. Структура документа уже спроектирована:

```
Screening (коллекция "screenings")
├── id: String                     UUIDv7
├── startAt / endAt: Instant
├── screeningDate: LocalDate       денормализация для поиска по дате
├── movieId / theaterId / hallId: String   плоские поля для поиска
├── movie: MovieSnapshot           { id, title, slug, banner }
├── theater: TheaterSnapshot       { id, name, address }
├── hall: HallSnapshot             { id, name }
├── seatTypes: List<SeatTypePrice> { type: SeatType, price: BigDecimal }
└── createdAt / updatedAt: Instant
```

Индексы: `{theaterId, screeningDate}` (главный read-путь), `{movieId}` (для `movie.updated`), `{hallId, startAt}` (проверка пересечения сеансов в зале).

Снапшоты — отдельные `record`-классы, не переиспользование Feign-DTO: это anti-corruption layer, чтобы изменения API чужого сервиса не текли в схему наших документов.

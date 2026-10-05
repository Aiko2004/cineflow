# CineFlow

Сервис продажи билетов в кино на микросервисной архитектуре. Учебно-портфолио проект.

**Общение с пользователем — на русском.**

## Обязательное чтение

Перед началом работы прочитай:

- `docs/CURRENT_STATE.md` — что уже готово, принятые решения с причинами, незакрытые вопросы, следующий шаг
- `docs/PROJECT_PLAN.md` — архитектура, доменная модель, roadmap, контракт фронтенда

Не переобсуждай решения, зафиксированные в `CURRENT_STATE.md` — они уже приняты осознанно, с разобранными trade-off'ами.

**Не фиксируй в документах непроверенное как факт.** Утверждения про конфигурацию (имена свойств, namespace, поведение автоконфигурации) записываются только после живого запуска. Непроверенное помечать явно: «предположительно», «не проверено вживую». Заметка про `discovery.locator` пролежала здесь как факт и попала в рецепт защиты сервисов — при копировании рецепта баг размножился бы по всем сервисам.

## Режим работы

Claude пишет код с best practices и **объясняет принятые решения**: почему выбран такой подход, какие есть альтернативы, чем они хуже/лучше. Пользователь читает, разбирается, задаёт вопросы.

System design обсуждается **до** реализации. Новые технологии (Kafka, Redis-локи, saga) — сначала концепция, потом код.

Не предлагай паттерны и абстракции ради самих паттернов. Всегда объясняй, какую конкретную проблему они решают здесь.

## Структура

```
CineFlow/                      корень git-репозитория
├── README.md                  портфолио-обзор (англ.), бейдж CI
├── LICENSE                    MIT
├── .gitignore / .gitattributes   корневые, на весь монорепо (eol=lf для gradlew/*.sh)
├── .github/
│   ├── workflows/backend-ci.yml  сборка на push в main и на каждый PR (без paths-фильтров — check `build` обязателен в ruleset)
│   └── dependabot.yml         gradle + github-actions, weekly, Spring сгруппирован
├── docs/                      план и текущее состояние
└── microservices-backend/     Gradle monorepo
    ├── build.gradle.kts       версии плагинов объявлены ЗДЕСЬ один раз (apply false)
    ├── contracts/             межсервисные контракты (библиотека, не Boot-приложение)
    ├── eureka-server/         8761
    ├── api-gateway/           8080
    ├── movie-service/         8081, Postgres
    ├── theater-service/       8082, Postgres
    ├── screening-service/     8083, MongoDB (+ gRPC-сервер 9083)
    ├── auth-service/          8084, Postgres + Redis
    └── booking-service/       8085, Postgres + Redis
```

Frontend (Angular) пока не начат.

## Стек

Java 21, Spring Boot 4.1.1, Spring Cloud 2025.1.3, Gradle (Kotlin DSL), Lombok, springdoc OpenAPI.
PostgreSQL, MongoDB, Redis, Kafka, RabbitMQ, Keycloak, ClickHouse, S3.

## Конвенции

**Gradle.** Версии плагинов — только в корневом `build.gradle.kts` с `apply false`. Модули применяют плагины **без версии**. Spring Boot не применяется ко всем subprojects скопом — `contracts` это библиотека. В модулях не дублировать `group`, `version`, `repositories`, `java{}`, `springCloudVersion`.

У модулей **не должно быть** своего `settings.gradle.kts` — Gradle считает такую папку корнем отдельной сборки, и межмодульные зависимости перестают резолвиться.

**Сущности.** `@Getter @Setter @NoArgsConstructor` — но не `@Data` (ломает lazy-связи и сравнение до/после сохранения). Enum'ы всегда `@Enumerated(EnumType.STRING)`.

**DTO.** Java `record`, отдельно от entity, в пакете `dto/<сущность>/`. Request-DTO никогда не содержит `id` (mass assignment). Валидация Bean Validation — на DTO, не на entity.

**Ошибки.** Кастомные исключения (`XxxNotFoundException`) + `GlobalExceptionHandler` с `@RestControllerAdvice`. Никаких голых `RuntimeException`.

**Сервисы.** `@RequiredArgsConstructor` + `final` поля (конструкторная инъекция, не `@Autowired` на поле). Маппинг entity↔DTO — приватными методами в сервисе.

**Конфиги.** Только плейсхолдеры `${VAR:default}`, никакого хардкода credentials. Секреты в `.env` (в `.gitignore`), `.env.example` — в репозитории. Переменные окружения, специфичные для конкретного сервиса, имеют префикс сервиса: `MOVIE_*`, `AUTH_*`, `BOOKING_*`, `THEATER_*` — потому что корневой `.env` экспортируется Makefile'ом во все сервисы сразу, и одно имя `DB_NAME` означало бы разные значения для разных сервисов.

**Миграции БД.** Только Flyway, `ddl-auto: validate`. Никогда не `update`.

Уже применённые миграции не редактировать — Flyway сверяет контрольные суммы, изменение накатанного файла ломает старт. Исправление оформляется новой версией (forward-only).

Деструктивные изменения разносятся по релизам — expand-contract:
1. миграция добавляет новую структуру и переносит данные, старая остаётся;
2. деплоится код, использующий новую структуру;
3. отдельной миграцией в следующем релизе удаляется старая.

Удаление колонки в той же миграции, что добавляет новую, делает откат приложения невозможным — старый код обратится к несуществующей колонке.

Колонку, участвующую в `REFERENCES`, всегда индексировать: PostgreSQL не делает это автоматически, и без индекса удаление в родительской таблице вызывает последовательный скан дочерней под блокировкой.

Миграция схемы и миграция данных — разные вещи. Добавил `NOT NULL`-поле в существующую таблицу — подумай, чем заполнить существующие строки.

**contracts.** Туда попадает **только** то, что физически пересекает границу сервиса: enum'ы и DTO событий. Никаких `@Entity`, `@Component`, репозиториев, утилит — иначе распределённый монолит.

Правила эволюции контрактов: добавлять значения в enum и поля в DTO можно, удалять и переименовывать — нельзя (старые сообщения в Kafka и записи в БД их содержат). Все enum'ы в `contracts` имеют значение `UNKNOWN` с `@JsonEnumDefaultValue`.

## Пагинация

Конфигурация (в `application.yaml` каждого сервиса):
```yaml
spring.data.web.pageable.max-page-size: 100
spring.data.web.pageable.default-page-size: 20
```

Контроллеры принимают `@ParameterObject @PageableDefault(size = 20, sort = "id") Pageable pageable`. Ограничение 100 применяется автоматически через `PageableHandlerMethodArgumentResolver` — добавлять проверку в код не нужно.

**PageResponse** — для offset-пагинации с totalElements (movie-service, theater-service):
```json
{ "content": [...], "page": 0, "size": 20, "totalElements": 47, "totalPages": 3, "last": false }
```
Используется там, где клиенту нужен счётчик и навигация по номерам страниц (каталоги, admin-списки).

**SliceResponse** — без totalElements (screening: расписание/фильм):
```json
{ "content": [...], "page": 1, "size": 20, "last": false }
```
Используется там, где нужно только «есть ли ещё» — count-запрос дорог и никем не используется.

**CursorPageResponse** — keyset-пагинация (screening: /scroll):
```json
{ "content": [...], "size": 20, "nextCursor": "base64..." }
```
`nextCursor: null` — последняя страница. Курсор кодирует `(startAt, id)`. Стабилен при вставках: нет дублей и пропусков. Почему `id` входит в курсор: `startAt` не уникален (два сеанса могут начинаться одновременно), без `id` граница страницы не детерминирована.

Сравнение: offset пагинация при вставке нового элемента до текущей позиции сдвигает все последующие — возникают дубли (страница 2 показывает последний элемент страницы 1). Keyset работает с позицией последнего виденного элемента и не зависит от вставок.

## Команды

```bash
cd microservices-backend

./gradlew build                      # сборка всех модулей
./gradlew :movie-service:bootRun     # запуск одного сервиса
docker compose up -d                 # инфраструктура (Postgres, MongoDB, Redis)
```

Порядок запуска: `eureka-server` → остальные сервисы → проверка на http://localhost:8761

Короткие алиасы — `Makefile` в `microservices-backend/` (`make` без аргументов — список целей): `make infra-up`, `make run-movie-service`, `make test-booking-service`, `make psql DB=booking`. Makefile подхватывает `.env` и экспортирует его в окружение `bootRun`; при заданном `DB_HOST_PORT`/`REDIS_HOST_PORT` выводит `DB_PORT`/`REDIS_PORT` из них. Логика сборки остаётся в Gradle — Makefile только обёртка. На Windows — из Git Bash; `make` не входит в стандартный Git for Windows, нужно установить отдельно: `scoop install make` или `choco install make`.

## Важные особенности

**Spring Boot 4** — не 3.x. Артефакты стартеров переименованы: `spring-boot-starter-webmvc` вместо `spring-boot-starter-web`, `spring-cloud-starter-gateway-server-webmvc` для Gateway. Тестовые стартеры тоже разделены (`spring-boot-starter-data-jpa-test` и т.д.). Jackson 3 (`tools.jackson.*`) в рантайме, аннотации остались `com.fasterxml.jackson.annotation`.

Тестовые слайс-аннотации в Boot 4 переехали в модуль-специфичные пакеты. `@WebMvcTest` теперь `org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest` (было `...test.autoconfigure.web.servlet`). Старый импорт из Boot 3 не компилируется.

Ещё две ловушки миграции Boot 4, найденные вживую (тесты-слайсы их не ловят — падает только полный старт):
- **Mongo:** строка подключения читается из `spring.mongodb.uri`, а НЕ `spring.data.mongodb.uri` (там остались только маппинг/репозитории). При старом ключе драйвер стартует с `credential=null`, коннектится к дефолтному localhost без auth и падает на `Command requires authentication`.
- **Jackson 3:** фича `read-unknown-enum-values-using-default-value` переехала из `DeserializationFeature` в `EnumFeature` → ключ `spring.jackson.datatype.enum.*`, а не `spring.jackson.deserialization.*`. Старый ключ роняет старт: `No enum constant ... DeserializationFeature`.

**ID.** `theater-service` и `movie-service` на `Long` auto-increment, `screening-service` на UUIDv7 (`com.github.f4b6a3:uuid-creator`). Контракт фронтенда требует `string` везде — унификация отложена осознанно, см. `CURRENT_STATE.md`.

**Screening — денормализованный документ.** Хранит снапшоты фильма/зала/кинотеатра внутри себя, не foreign key. Обогащение: Feign при создании + Kafka-события на последующие изменения. Снапшоты — отдельные `record`-классы, не переиспользование Feign-DTO (anti-corruption layer).

**Референс-проект.** Контракт API взят из реального фронтенда https://github.com/TeaCoder52/teacinema-public (Next.js, папка `frontend/src/api/generated/`). При проектировании нового эндпоинта — сверяйся с ним, а не выдумывай форму ответа.

## Аутентификация и защита сервисов

Полная схема — `docs/AUTH_DESIGN.md`. Кратко:

**auth-service (8084).** Вход по OTP на email. Postgres — аккаунты и refresh-токены; Redis — OTP-коды и rate limit. Пароля нет вообще. Токены: access (JWT, 15 мин), refresh (случайные 256 бит, 30 дней, ротация). Подпись **RS256**: приватный ключ только у auth-service, публичный раздаётся через `GET /.well-known/jwks.json`.

Ключевые инварианты (не переобсуждать без причины):
- `/auth/otp/send` **всегда 202**, независимо от существования аккаунта — иначе эндпоинт становится инструментом user enumeration.
- OTP **одноразовый**: при успехе ключ в Redis удаляется. Лимит попыток ввода — при исчерпании ключ удаляется целиком (не блокируется счётчик), иначе перебор продолжат новым кодом.
- Два независимых rate limit: по email и по IP.
- Refresh — **ротация с обнаружением повторного использования**: каждый refresh одноразовый; предъявление уже отозванного токена отзывает всю `family_id` (и вора, и владельца). `family_id` общий для цепочки ротаций одной сессии.
- В БД лежит **хеш** токена/кода (SHA-256 hex), не само значение.

**Как защитить ресурсный сервис** (образец — `movie-service`; по нему закрыты также theater, screening и booking):

1. Зависимости: `spring-boot-starter-security` + `spring-boot-starter-oauth2-resource-server` (в тестах — `spring-boot-starter-security-test`).
2. `application.yaml`:
   ```yaml
   spring.security.oauth2.resourceserver.jwt.jwk-set-uri: ${JWKS_URI:http://localhost:8084/.well-known/jwks.json}
   ```
   Spring сам забирает и кеширует ключи, перезапрашивает при неизвестном `kid` — ротация ключей не требует перезапуска сервиса.
3. `SecurityConfig` с `SecurityFilterChain`: `csrf.disable()`, `SessionCreationPolicy.STATELESS`, публичные GET-чтения `permitAll`, запись `authenticated`, `oauth2ResourceServer(jwt(...))`. Роли — из claim `roles`, префикс `""` (значения уже содержат `ROLE_`), через `JwtAuthenticationConverter` + `JwtGrantedAuthoritiesConverter` (задел под `@PreAuthorize`/admin-роли).
4. Проверка идёт **и на Gateway, и в самом сервисе** — defense in depth. Периметр — не единственная линия: любой, кто оказался внутри сети, иначе ходит куда угодно.
5. Тесты веб-слоя (`@WebMvcTest`): `@Import(SecurityConfig.class)` (слайс не подхватывает пользовательский фильтр сам) + `@MockitoBean JwtDecoder` (на permitAll-GET не вызывается, но бин нужен для `jwt()`).

**Gateway.** Явные маршруты под **`spring.cloud.gateway.server.webmvc.routes`** — за периметром безопасности нужно точно знать, какой путь публичный. ⚠️ У сервлетного `gateway-server-webmvc` префикс свойств именно `spring.cloud.gateway.server.webmvc.*`, а НЕ `spring.cloud.gateway.*` (реактивный namespace). Маршруты под неверным префиксом молча игнорируются: запрос не находит маршрут → 404 → dispatch на `/error` → security отдаёт 401, маскируя причину. discovery-locator в этом варианте отсутствует. Публично: `/auth/**`, `/.well-known/**`, GET-чтения каталогов/расписания; всё остальное — только с токеном. `iss` в токене — точка различения токенов CineFlow и будущего Keycloak.

**Порты инфраструктуры.** Если на хосте уже есть нативные PostgreSQL/Redis на 5432/6379, `localhost` у приложения попадёт в них, а не в контейнер (`password authentication failed`). host-порты контейнеров переопределяемы: `DB_HOST_PORT`/`REDIS_HOST_PORT` (+ `DB_PORT`/`REDIS_PORT` сервисам). См. `.env-example`.

## gRPC (первый в проекте)

Единственный gRPC-вызов: `booking-service` → `screening-service` `GetScreening(screeningId)` (валидация сеанса + данные для денормализации брони одним вызовом). Всё остальное межсервисное — REST/Feign.

**`.proto` в `contracts`** (`src/main/proto/screening.proto`). Сгенерированный код — такой же контракт, как enum'ы, его импортируют и сервер (screening), и клиент (booking). Instant/дата/decimal передаём строками (без well-known-типов проще, `BigDecimal` через string не округляется как double).

**Что генерирует плагин** (`com.google.protobuf`): из `.proto` — Java-классы сообщений (protobuf-java, immutable + builder) и `ScreeningServiceGrpc` (protoc-gen-grpc-java): `ScreeningServiceImplBase` для сервера и `newBlockingStub()`/`newStub()` для клиента.

**Что добавили в сборку:**
- корневой `build.gradle.kts`: `id("com.google.protobuf") version "0.9.4" apply false`;
- `contracts`: плагин + `java-library`, `api(grpc-protobuf, grpc-stub, protobuf-java)` (сгенерированный код ссылается на них → нужны потребителям), `protoc`/`protoc-gen-grpc-java` в блоке `protobuf{}`. Плагин докачивает нативные бинарники `protoc` и grpc-плагина при первой сборке;
- сервисы: `io.grpc:grpc-netty-shaded` — транспорт (свой шейдед netty). **Версия должна совпадать с `grpc-core`, который тянет Spring Boot BOM (сейчас 1.83.1).** Иначе `AbstractMethodError` от рассинхрона внутренних интерфейсов сервера. contracts BOM не применяет → там версии закреплены явно; сервисы применяют BOM → он поднимает grpc-* до 1.83.1, поэтому и `grpc-netty-shaded` пинуем в 1.83.1.

**Отличие вызова от Feign на уровне кода** (см. `ScreeningGrpcClient` vs `SeatCatalogClient`):
- Feign: интерфейс с `@GetMapping`, прокси генерирует Spring, вызов — обычный метод, ошибка — `FeignException` с HTTP-кодом.
- gRPC: вызываешь метод сгенерированного стаба, аргумент/ответ — protobuf-builder'ы (не POJO), ошибка — `StatusRuntimeException` со `Status.Code` (NOT_FOUND/UNAVAILABLE), `withDeadlineAfter` = дедлайн на весь вызов.
- Оба одинаково оборачиваются в `@CircuitBreaker/@Bulkhead/@Retry` (resilience работает на уровне метода, транспорт не важен).

**Сервер gRPC** живёт рядом с HTTP на отдельном порту (не внутри Tomcat): `io.grpc.Server` поднимается через `SmartLifecycle` (см. `screening-service` `GrpcServer`). Реализация — наследник `*ImplBase`, ответ отдаётся через `StreamObserver` (`onNext`+`onCompleted`), ошибка — `onError(Status...)`, метод ничего не возвращает.

**Порт 9083 без auth — осознанное решение (не допущение).** Порт строго внутренний: не за gateway, не публикуется в docker-compose, по умолчанию биндится на **loopback** (`grpc.server.host=127.0.0.1`, `NettyServerBuilder.forAddress`, не `forPort`/0.0.0.0) → недоступен из LAN. Поэтому app-level auth/mTLS не нужен. В контейнерах — `GRPC_HOST=0.0.0.0` + порт не публикуется. Если вызов пересечёт границу доверия → `TlsServerCredentials` (mTLS), шов в `GrpcServer`. Обоснование — `docs/BOOKING_DESIGN.md §5`.

**Версии gRPC — держать равными версии из Spring Boot BOM** (сейчас 1.83.1) во ВСЕХ местах: `contracts` (grpc-stub/protobuf/protoc-gen-grpc-java, там BOM не применяется → пин явный) и сервисы (grpc-netty-shaded). Иначе сгенерированный код компилируется против одной версии, исполняется на другой (`AbstractMethodError` / несоответствие внутренних интерфейсов). protobuf-java может быть старше (генерённый код forward-совместим с более новым рантаймом).

## Booking (продажа мест)

Полная схема — `docs/BOOKING_DESIGN.md`. Порт 8085, Postgres (брони, занятые места) + Redis (мягкие удержания). Первый инкремент — без оплаты, подтверждение заглушкой.

Ключевые инварианты (не переобсуждать без причины):
- **Гарантия «одно место — один раз» — частичный уникальный индекс** `uk_seat_taken ON booking_seats (screening_id, seat_id) WHERE released_at IS NULL`, а не проверка в коде. Redis-удержания и уборщик — оптимизация вокруг него; при потере Redis корректность не страдает.
- **Переходы статусов — условным `UPDATE ... WHERE id=? AND status=?`** (0 строк = статус уже сменился → 409). Правила переходов — в enum `BookingStatus`, из CONFIRMED в PENDING нельзя.
- **Redis-удержание:** `SET seat:{screeningId}:{seatId} {userId} NX EX ttl`; снятие — Lua compare-and-delete (только владельцем).
- **Идемпотентность** `POST /bookings` по заголовку `Idempotency-Key`, `UNIQUE (user_id, idempotency_key)`; повтор возвращает ту же бронь.
- **`user_id` — только из claim `sub`**, никогда из тела. Чужая бронь в `GET /bookings/{id}` → 404 (не 403).
- **Внешние вызовы (gRPC + Feign) — ДО транзакции.** Запись — в отдельном бине (`BookingPersistence`, `@Transactional`): `@Transactional`-метод того же класса, вызванный изнутри, минует прокси; поэтому оркестрация и запись разнесены по бинам.
- **Доступность места учитывает `hold_expires_at`**, не только существование записи: перед вставкой освобождаются просроченные удержания этого сеанса; фоновый уборщик переводит просроченные PENDING в EXPIRED.
- **QR при подтверждении — случайный токен из `SecureRandom`**, не производная от `bookingId`.
- **App-assigned UUID + JPA:** сущность с присвоенным id и коллекцией должна реализовать `Persistable` (`isNew()`), иначе `repository.save()` уходит в `merge()` и ломает каскад (`ObjectNotFoundException`). См. `Booking`.

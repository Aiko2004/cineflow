package com.cineflow.payment;

import com.cineflow.payment.outbox.OutboxRelay;
import com.cineflow.payment.service.PaymentPersistence;
import com.cineflow.payment.service.PaymentService;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// База интеграционных тестов payment: настоящие Postgres и Kafka (Testcontainers).
//
// Образ Kafka — тот же apache/kafka:4.3.1, что и в docker-compose.yml: тесты идут против
// того же брокера, что и локальная разработка. Класс контейнера — org.testcontainers.kafka.
// KafkaContainer (Testcontainers 2.x; org.testcontainers.containers.KafkaContainer — старый,
// под образ confluentinc/cp-kafka).
//
// Singleton-контейнеры (static-блок): обычный @Container останавливался бы между тест-классами,
// а Spring-контекст кешируется и ссылался бы на мёртвый брокер (тот же приём, что в auth/booking).
//
// webEnvironment = MOCK: payment — ресурсный сервер, его SecurityConfig требует сервлетный
// контекст; порт не слушается, сервисы вызываем как бины.
//
// Потребитель payment.commands по умолчанию ВЫКЛЮЧЕН (auto-startup=false). Spring кеширует
// контексты: каждый тест-класс с другим набором бинов (spy, веб-окружение) — отдельный живой
// контекст. Будь листенер включён везде, все они были бы потребителями ОДНОЙ группы
// payment-service и делили бы партиции — команду теста со spy обработал бы чужой контекст.
// Включает листенер только PaymentCommandsConsumerTest (свойство подкласса перекрывает это).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@TestPropertySource(properties = "spring.kafka.listener.auto-startup=false")
abstract class AbstractPaymentIntegrationTest {

    // Секрет подписи webhook в тестах; тесты считают подпись сами, независимо от сервиса.
    protected static final String WEBHOOK_SECRET = "test-webhook-secret";

    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16").withDatabaseName("payment");
    // Автосоздание топиков выключено, как в docker-compose.yml. Брокер Testcontainers по умолчанию
    // создаёт топик на первое обращение — и тогда отправка в топик с ОШИБОЧНЫМ именем молча
    // проходит. Так прятался дефект с именем DLT (spring-kafka 4.x пишет в "-dlt", а не ".DLT").
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1")
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");

    static {
        POSTGRES.start();
        KAFKA.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("eureka.client.enabled", () -> "false");
        registry.add("spring.cloud.discovery.enabled", () -> "false");
        // Фоновый relay не должен срабатывать сам — тесты вызывают publishBatch() вручную.
        registry.add("cineflow.payment.outbox.interval", () -> "3600000");
        // Короткие таймауты продюсера: тест «брокер недоступен» не должен ждать по 15–25 секунд.
        // Соотношение то же, что в application.yaml: send-timeout > max.block + delivery.timeout.
        registry.add("spring.kafka.producer.properties.delivery.timeout.ms", () -> "3000");
        registry.add("spring.kafka.producer.properties.request.timeout.ms", () -> "1000");
        registry.add("spring.kafka.producer.properties.max.block.ms", () -> "1000");
        registry.add("cineflow.payment.outbox.send-timeout", () -> "6s");
        // Фоновые задачи тоже вызываются вручную; повтор возвратов — без паузы.
        registry.add("cineflow.payment.jobs.reconciliation-interval", () -> "3600000");
        registry.add("cineflow.payment.jobs.refund-retry-interval", () -> "3600000");
        registry.add("cineflow.payment.jobs.refund-retry-min-age", () -> "0s");
        // Потребитель команд: 2 повтора по 200 мс — тест «в DLT после попыток» не ждёт секундами.
        registry.add("cineflow.payment.commands.max-retries", () -> "2");
        registry.add("cineflow.payment.commands.retry-backoff", () -> "200ms");
        registry.add("cineflow.payment.kaspi.webhook-secret", () -> WEBHOOK_SECRET);
        // Webhook симулятора: в RANDOM_PORT-тестах — в поднятый сервер; в MOCK-тестах порта нет,
        // и дефолт :1 гарантирует «не доставлено», а не доставку в случайно запущенный локально
        // payment-service на 8086. Плейсхолдер разрешается в момент отправки (KaspiSimulatorApp).
        registry.add("cineflow.payment.kaspi.simulator.webhook-url",
                () -> "http://localhost:${local.server.port:1}/payment/webhooks/kaspi");
    }

    @Autowired protected PaymentService paymentService;
    @Autowired protected PaymentPersistence paymentPersistence;
    @Autowired protected OutboxRelay outboxRelay;
    @Autowired protected JdbcTemplate jdbcTemplate;
    @Autowired protected JsonMapper jsonMapper;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM outbox");
        jdbcTemplate.update("DELETE FROM payments");
        jdbcTemplate.update("DELETE FROM sim_invoices");
    }

    protected int outboxCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM outbox", Integer.class);
    }

    protected String paymentStatus(UUID paymentId) {
        return jdbcTemplate.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, paymentId);
    }

    protected String invoiceStatus(String providerInvoiceId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM sim_invoices WHERE provider_invoice_id = ?", String.class, providerInvoiceId);
    }

    // Срок счёта вышел — и у нас, и у «провайдера» (симулятор выводит EXPIRED из своего expires_at).
    protected void expire(UUID paymentId) {
        jdbcTemplate.update("UPDATE payments SET expires_at = now() - interval '1 minute' WHERE id = ?", paymentId);
        jdbcTemplate.update("UPDATE sim_invoices SET expires_at = now() - interval '1 minute' WHERE payment_id = ?",
                paymentId);
    }

    // Строки outbox данного типа для брони — события, ещё не дошедшие до Kafka, тоже видны.
    protected List<String> outboxPayloads(UUID bookingId, String eventType) {
        return jdbcTemplate.queryForList(
                "SELECT payload::text FROM outbox WHERE aggregate_id = ? AND event_type = ? ORDER BY created_at, id",
                String.class, bookingId, eventType);
    }

    // HMAC-SHA256 тела в hex — собственной реализацией теста, не кодом сервиса.
    protected static String sign(byte[] body, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    protected int unpublishedCount() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox WHERE published_at IS NULL", Integer.class);
    }

    // Читает топик с самого начала и возвращает записи с данным ключом (тесты делят топик,
    // у каждого теста свой bookingId). Ждёт, пока наберётся expected записей или истечёт таймаут;
    // после этого ещё раз дочитывает хвост, чтобы поймать ЛИШНИЕ (дубли), а не только недостачу.
    protected List<ConsumerRecord<String, String>> readByKey(String topic, UUID key, int expected) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer =
                     new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer())) {
            List<TopicPartition> partitions = consumer.partitionsFor(topic).stream()
                    .map(p -> new TopicPartition(topic, p.partition())).toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);

            long deadline = System.currentTimeMillis() + 15_000;
            while (found.size() < expected && System.currentTimeMillis() < deadline) {
                consumer.poll(Duration.ofMillis(300)).forEach(r -> {
                    if (key.toString().equals(r.key())) {
                        found.add(r);
                    }
                });
            }
            // Хвост: даём ещё один заход, чтобы вылезли дубли.
            consumer.poll(Duration.ofMillis(1000)).forEach(r -> {
                if (key.toString().equals(r.key())) {
                    found.add(r);
                }
            });
        }
        return found;
    }
}

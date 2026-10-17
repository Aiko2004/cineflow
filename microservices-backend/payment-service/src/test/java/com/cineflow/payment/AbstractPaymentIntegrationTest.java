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
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
abstract class AbstractPaymentIntegrationTest {

    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16").withDatabaseName("payment");
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");

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

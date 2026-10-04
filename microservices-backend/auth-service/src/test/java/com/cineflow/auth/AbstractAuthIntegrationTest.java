package com.cineflow.auth;

import com.cineflow.auth.util.TokenHasher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Map;

// База для интеграционных тестов auth-service: поднимает реальные Postgres и Redis
// в Docker (Testcontainers). Flyway накатывает схему, Hibernate её валидирует.
//
// webEnvironment = NONE: HTTP-слой не нужен — дёргаем сервисы напрямую как бины.
// Eureka выключена, чтобы контекст не пытался регистрироваться.
//
// Singleton-контейнеры (а НЕ @Testcontainers/@Container): контейнеры стартуют один раз
// в static-блоке и живут до конца JVM (Ryuk убирает их при выходе). Обычный жизненный цикл
// @Container останавливает контейнеры в afterAll ПЕРВОГО отработавшего класса, а второй
// тест-класс переиспользует закешированный Spring-контекст (те же свойства) и стучится
// в уже мёртвый контейнер → RedisCommandTimeoutException. Именно на этом падал первый прогон.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
abstract class AbstractAuthIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16").withDatabaseName("auth");

    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));

        registry.add("eureka.client.enabled", () -> "false");
        registry.add("spring.cloud.discovery.enabled", () -> "false");
    }

    @Autowired
    protected StringRedisTemplate redis;

    // Чистим Redis перед каждым тестом — счётчики rate limit и OTP-ключи не должны
    // протекать между тестами.
    protected void flushRedis() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    // Кладём в Redis известный OTP-код напрямую, минуя генерацию в OtpService.send().
    // Так тесты verify/login работают с кодом, который они знают (сам код нигде не отдаётся).
    protected void seedOtp(String email, String code, int attempts) {
        String key = "otp:" + email.trim().toLowerCase();
        redis.opsForHash().putAll(key, Map.of(
                "codeHash", TokenHasher.sha256Hex(code),
                "attempts", String.valueOf(attempts)
        ));
        redis.expire(key, Duration.ofMinutes(5));
    }
}

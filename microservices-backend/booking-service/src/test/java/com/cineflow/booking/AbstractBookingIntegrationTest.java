package com.cineflow.booking;

import com.cineflow.booking.client.ScreeningData;
import com.cineflow.booking.client.ScreeningGrpcClient;
import com.cineflow.booking.client.SeatCatalogClient;
import com.cineflow.booking.client.dto.SeatFeignResponse;
import com.cineflow.booking.repository.BookingRepository;
import com.cineflow.booking.repository.BookingSeatRepository;
import com.cineflow.booking.service.BookingService;
import com.cineflow.booking.service.RedisHoldService;
import com.cineflow.contracts.SeatType;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

// База интеграционных тестов booking. Реальные Postgres + Redis (Testcontainers),
// внешние вызовы (gRPC screening, Feign theater) замоканы — тест сосредоточен на инварианте
// «место продаётся один раз», статусах, идемпотентности и Redis-удержаниях.
//
// Singleton-контейнеры (static-блок) — тот же приём, что в auth: обычный @Container
// останавливал бы контейнеры между тест-классами, а Spring-контекст кешируется.
//
// webEnvironment = MOCK (не NONE): booking — ресурсный сервер, его SecurityConfig требует
// бин HttpSecurity, а тот существует только в сервлетном веб-контексте. Сервер не слушает
// порт; сервисы дёргаем напрямую как бины, минуя security-фильтры.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
abstract class AbstractBookingIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16").withDatabaseName("booking");
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
        // Уборщик не должен срабатывать сам во время тестов — вызываем sweep() вручную.
        registry.add("booking.sweeper.interval", () -> "3600000");
        // gRPC-клиент замокан, но бин канала всё равно создаётся — адрес неважен.
        registry.add("grpc.client.screening.host", () -> "localhost");
        registry.add("grpc.client.screening.port", () -> "9083");
    }

    protected static final UUID SCREENING = UUID.fromString("0199c4f2-0000-7000-8000-000000000001");
    protected static final String HALL_ID = "10";

    @Autowired protected BookingService bookingService;
    @Autowired protected BookingRepository bookingRepository;
    @Autowired protected BookingSeatRepository bookingSeatRepository;
    @Autowired protected RedisHoldService redisHoldService;
    @Autowired protected StringRedisTemplate redisTemplate;
    @Autowired protected JdbcTemplate jdbcTemplate;

    // Внешние вызовы — моки. Реальные gRPC/Feign не нужны для проверки инвариантов БД.
    @MockitoBean protected ScreeningGrpcClient screeningGrpcClient;
    @MockitoBean protected SeatCatalogClient seatCatalogClient;

    @BeforeEach
    void resetState() {
        bookingSeatRepository.deleteAllInBatch();
        bookingRepository.deleteAllInBatch();
        flushRedis();

        // Сеанс: зал 10, цены по типам мест.
        Map<SeatType, BigDecimal> prices = new EnumMap<>(SeatType.class);
        prices.put(SeatType.NORMAL, new BigDecimal("200.00"));
        prices.put(SeatType.VIP, new BigDecimal("350.00"));
        ScreeningData screening = new ScreeningData(
                SCREENING.toString(),
                Instant.parse("2026-10-01T18:00:00Z"),
                LocalDate.parse("2026-10-01"),
                "1", "The Matrix", "the-matrix", "banner.jpg",
                "1", "CineFlow Downtown", "1 Main St",
                HALL_ID, "Hall 1",
                prices);
        when(screeningGrpcClient.getScreening(anyString())).thenReturn(screening);

        // Любое место принадлежит залу 10, тип NORMAL.
        when(seatCatalogClient.fetchSeat(anyLong())).thenAnswer(inv -> {
            Long seatId = inv.getArgument(0);
            return new SeatFeignResponse(seatId, Long.parseLong(HALL_ID), 1, seatId.intValue(), SeatType.NORMAL);
        });
    }

    protected void flushRedis() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }
}

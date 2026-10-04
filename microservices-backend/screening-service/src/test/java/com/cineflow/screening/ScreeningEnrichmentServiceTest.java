package com.cineflow.screening;

import com.cineflow.screening.client.MovieClient;
import com.cineflow.screening.client.TheaterClient;
import com.cineflow.screening.client.dto.MovieFeignResponse;
import com.cineflow.screening.exception.MovieNotFoundException;
import com.cineflow.screening.exception.ServiceUnavailableException;
import com.cineflow.screening.service.ScreeningEnrichmentService;
import feign.FeignException;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

// Тесты resilience-логики ScreeningEnrichmentService.
//
// Стратегия: НЕ поднимаем Spring-контекст (нет MongoDB, Eureka, Feign).
// Используем программный Resilience4j API (CircuitBreaker.decorateSupplier и т.д.)
// — это то, что Spring AOP делает за нас в рантайме, только здесь явно.
//
// Что проверяем:
//   - поведение самого сервиса (404 → MovieNotFoundException)
//   - переходы CB: CLOSED → OPEN → HALF-OPEN → CLOSED
//   - Retry повторяет на временных сбоях, не повторяет на 404
//   - Bulkhead отклоняет при исчерпанном семафоре
//   - fallback-методы бросают ServiceUnavailableException
@ExtendWith(MockitoExtension.class)
class ScreeningEnrichmentServiceTest {

    @Mock
    private MovieClient movieClient;
    @Mock
    private TheaterClient theaterClient;

    private ScreeningEnrichmentService service;

    // Программные инстансы Resilience4j с теми же правилами, что в application.yaml,
    // но с меньшим sliding window (4 вместо 10) — чтобы не гонять лишние вызовы в тестах.
    private CircuitBreaker movieCb;
    private Bulkhead movieBulkhead;
    private Retry movieRetry;

    @BeforeEach
    void setUp() {
        service = new ScreeningEnrichmentService(movieClient, theaterClient);

        movieCb = CircuitBreaker.of("movie-service", CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(4)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(60))
                .permittedNumberOfCallsInHalfOpenState(2)
                // зеркало ignoreExceptions из application.yaml
                .ignoreExceptions(MovieNotFoundException.class, ServiceUnavailableException.class)
                .build());

        movieBulkhead = Bulkhead.of("movie-service", BulkheadConfig.custom()
                .maxConcurrentCalls(1)
                .maxWaitDuration(Duration.ZERO)
                .build());

        movieRetry = Retry.of("movie-service", RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(10)) // минимум — тесты не должны спать долго
                .ignoreExceptions(
                        MovieNotFoundException.class,
                        ServiceUnavailableException.class,
                        CallNotPermittedException.class)
                .build());
    }

    // Декорируем вызов только CB — для тестов одного CB без Retry.
    private MovieFeignResponse fetchWithCb(String id) {
        return CircuitBreaker.decorateSupplier(movieCb, () -> service.fetchMovie(id)).get();
    }

    // Декорируем Retry → CB (порядок аспектов как в prod: Retry снаружи CB).
    private MovieFeignResponse fetchWithRetryCb(String id) {
        Supplier<MovieFeignResponse> withCb = CircuitBreaker.decorateSupplier(
                movieCb, () -> service.fetchMovie(id));
        return Retry.decorateSupplier(movieRetry, withCb).get();
    }

    // ── 1. CB открывается после превышения порога ошибок ─────────────────────

    @Test
    void cb_opensAfterFailureThreshold() {
        // InternalServerError — не в ignoreExceptions, считается сбоем
        when(movieClient.getById(anyString()))
                .thenThrow(mock(FeignException.InternalServerError.class));

        // 4 вызова: 100% failure rate > 50% порога → CB переходит в OPEN
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> fetchWithCb("1"))
                    .isInstanceOf(FeignException.InternalServerError.class);
        }

        assertThat(movieCb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    // ── 2. При OPEN вызов не доходит до клиента ───────────────────────────────

    @Test
    void cb_open_callNotReachingClient() {
        movieCb.transitionToOpenState();

        // CB бросает CallNotPermittedException немедленно, не вызывая сервис
        assertThatThrownBy(() -> fetchWithCb("1"))
                .isInstanceOf(CallNotPermittedException.class);

        verifyNoInteractions(movieClient);
    }

    // ── 3. 404 не открывает CB ────────────────────────────────────────────────

    @Test
    void cb_404_doesNotCountAsFailure() {
        when(movieClient.getById(anyString()))
                .thenThrow(mock(FeignException.NotFound.class));

        // 4 вызова с 404 — MovieNotFoundException в ignoreExceptions, не failure
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> fetchWithCb("1"))
                    .isInstanceOf(MovieNotFoundException.class);
        }

        assertThat(movieCb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(movieCb.getMetrics().getNumberOfFailedCalls()).isZero();
    }

    // ── 4. Retry повторяет на временном сбое и успешно завершается ───────────

    @Test
    void retry_retriesOnTransientFailure() {
        MovieFeignResponse ok = new MovieFeignResponse(1L, "Inception", "desc", "poster.jpg", "sci-fi");
        when(movieClient.getById(anyString()))
                .thenThrow(mock(FeignException.InternalServerError.class))
                .thenThrow(mock(FeignException.InternalServerError.class))
                .thenReturn(ok);

        MovieFeignResponse result = fetchWithRetryCb("1");

        assertThat(result.title()).isEqualTo("Inception");
        verify(movieClient, times(3)).getById("1");
    }

    // ── 5. Retry не повторяет на 404 ─────────────────────────────────────────

    @Test
    void retry_doesNotRetryOn404() {
        when(movieClient.getById(anyString()))
                .thenThrow(mock(FeignException.NotFound.class));

        assertThatThrownBy(() -> fetchWithRetryCb("1"))
                .isInstanceOf(MovieNotFoundException.class);

        // Ровно 1 вызов — Retry сразу проброшено исключение без повторов
        verify(movieClient, times(1)).getById("1");
    }

    // ── 6. Retry не повторяет, когда CB открыт ───────────────────────────────
    //
    // Это объясняет, почему Retry снаружи CB, а не наоборот.
    // Retry видит CallNotPermittedException → это в ignoreExceptions → немедленный пробросок.
    // При обратном порядке (CB→Retry) CB засчитывал бы каждую retry-попытку
    // как отдельный сбой и открывался бы в N раз быстрее.

    @Test
    void retry_doesNotRetryWhenCbOpen() {
        movieCb.transitionToOpenState();

        assertThatThrownBy(() -> fetchWithRetryCb("1"))
                .isInstanceOf(CallNotPermittedException.class);

        verifyNoInteractions(movieClient);
    }

    // ── 7. Bulkhead переполнен — немедленный отказ ────────────────────────────

    @Test
    void bulkhead_full_immediateRejection() {
        // Захватываем единственный слот вручную — следующий вызов должен упасть сразу
        boolean acquired = movieBulkhead.tryAcquirePermission();
        assertThat(acquired).isTrue();

        try {
            assertThatThrownBy(() ->
                    Bulkhead.decorateSupplier(movieBulkhead, () -> service.fetchMovie("1")).get()
            ).isInstanceOf(BulkheadFullException.class);

            verifyNoInteractions(movieClient);
        } finally {
            movieBulkhead.releasePermission();
        }
    }

    // ── 8. HALF-OPEN → CLOSED после успешных проб ────────────────────────────

    @Test
    void cb_halfOpen_closesAfterSuccessfulProbes() {
        movieCb.transitionToOpenState();
        movieCb.transitionToHalfOpenState();

        MovieFeignResponse ok = new MovieFeignResponse(1L, "Film", "desc", "p.jpg", "drama");
        when(movieClient.getById(anyString())).thenReturn(ok);

        // permittedNumberOfCallsInHalfOpenState = 2: два успешных вызова → CLOSED
        fetchWithCb("1");
        fetchWithCb("1");

        assertThat(movieCb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    // ── 9. Fallback-методы: проверяем, что бросают ServiceUnavailableException ─
    //
    // ОТВЕТ НА ВОПРОС: вызывается ли fallback дважды при переполненном bulkhead?
    //
    // Нет. Стек при BulkheadFullException (при отдельных fallback-методах):
    //   1. Bulkhead-аспект (order=25): BulkheadFullException → movieBulkheadFallback()
    //      movieBulkheadFallback() бросает ServiceUnavailableException
    //   2. CB-аспект (order=10): перехватывает ServiceUnavailableException.
    //      ServiceUnavailableException есть в ignoreExceptions → CB не вызывает свой fallback,
    //      просто пропускает исключение вверх.
    //
    // При старом коде (один fallbackMethod для обоих аннотаций) было то же самое:
    // CB не вызывает fallback, потому что ignoreExceptions работает раньше.
    // Разделили методы ради ясности (разные причины → разные имена), не из-за бага.

    @Test
    void cbFallback_throwsServiceUnavailable() {
        assertThatThrownBy(() -> service.movieCbFallback("1", new RuntimeException("cb open")))
                .isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void bulkheadFallback_throwsServiceUnavailable() {
        assertThatThrownBy(() -> service.movieBulkheadFallback("1", new RuntimeException("bh full")))
                .isInstanceOf(ServiceUnavailableException.class);
    }
}

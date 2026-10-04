package com.cineflow.screening.service;

import com.cineflow.screening.client.MovieClient;
import com.cineflow.screening.client.TheaterClient;
import com.cineflow.screening.client.dto.HallFeignResponse;
import com.cineflow.screening.client.dto.MovieFeignResponse;
import com.cineflow.screening.client.dto.TheaterFeignResponse;
import com.cineflow.screening.exception.HallNotFoundException;
import com.cineflow.screening.exception.MovieNotFoundException;
import com.cineflow.screening.exception.ServiceUnavailableException;
import com.cineflow.screening.exception.TheaterNotFoundException;
import feign.FeignException;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

// Адаптер исходящих вызовов к movie-service и theater-service.
//
// Выделен в отдельный @Service, потому что @CircuitBreaker, @Bulkhead, @Retry —
// AOP-аннотации. Spring AOP работает через прокси и перехватывает только
// вызовы ПУБЛИЧНЫХ методов ЧЕРЕЗ ССЫЛКУ НА БИН. Приватные методы или
// вызовы this.method() внутри одного класса AOP не видит.
//
// Порядок аспектов: Retry (outer) → CircuitBreaker → Bulkhead → метод.
// Retry снаружи CB: при открытом CB каждая попытка получает CallNotPermittedException
// (ignoredException в retry-config), поэтому Retry не тратит попытки впустую.
// При обратном порядке (CB→Retry) CB засчитывал бы каждую retry-попытку как отдельный сбой
// и открывался бы в N раз быстрее — там, где должен работать Retry.
@Service
@RequiredArgsConstructor
public class ScreeningEnrichmentService {

    private final MovieClient movieClient;
    private final TheaterClient theaterClient;

    // ── Movie ────────────────────────────────────────────────────────────────

    // @CircuitBreaker (order=10) оборачивает @Bulkhead (order=25).
    // Cascade: bulkhead fallback бросает ServiceUnavailableException →
    // CB видит это исключение, но ServiceUnavailableException в ignoreExceptions →
    // CB не считает это сбоем, не вызывает свой fallback, пропускает исключение.
    @Retry(name = "movie-service")
    @CircuitBreaker(name = "movie-service", fallbackMethod = "movieCbFallback")
    @Bulkhead(name = "movie-service", fallbackMethod = "movieBulkheadFallback")
    public MovieFeignResponse fetchMovie(String movieId) {
        try {
            return movieClient.getById(movieId);
        } catch (FeignException.NotFound e) {
            // MovieNotFoundException в ignoreExceptions CB — не считается сбоем,
            // CB fallback не вызывается, исключение прокидывается как есть → 404.
            throw new MovieNotFoundException(movieId);
        }
    }

    // Вызывается при OPEN автомате: ex = CallNotPermittedException.
    public MovieFeignResponse movieCbFallback(String movieId, Throwable ex) {
        throw new ServiceUnavailableException("movie-service");
    }

    // Вызывается при переполненном bulkhead: ex = BulkheadFullException.
    public MovieFeignResponse movieBulkheadFallback(String movieId, Throwable ex) {
        throw new ServiceUnavailableException("movie-service");
    }

    // ── Hall ─────────────────────────────────────────────────────────────────

    @Retry(name = "theater-service")
    @CircuitBreaker(name = "theater-service", fallbackMethod = "hallCbFallback")
    @Bulkhead(name = "theater-service", fallbackMethod = "hallBulkheadFallback")
    public HallFeignResponse fetchHall(String hallId) {
        try {
            return theaterClient.getHallById(hallId);
        } catch (FeignException.NotFound e) {
            throw new HallNotFoundException(hallId);
        }
    }

    public HallFeignResponse hallCbFallback(String hallId, Throwable ex) {
        throw new ServiceUnavailableException("theater-service");
    }

    public HallFeignResponse hallBulkheadFallback(String hallId, Throwable ex) {
        throw new ServiceUnavailableException("theater-service");
    }

    // ── Theater ───────────────────────────────────────────────────────────────

    // fetchHall и fetchTheater используют один CB-экземпляр "theater-service".
    // Сбои к обоим эндпоинтам суммируются — это правильно: они оба на одном сервисе.
    @Retry(name = "theater-service")
    @CircuitBreaker(name = "theater-service", fallbackMethod = "theaterCbFallback")
    @Bulkhead(name = "theater-service", fallbackMethod = "theaterBulkheadFallback")
    public TheaterFeignResponse fetchTheater(String theaterId) {
        try {
            return theaterClient.getTheaterById(theaterId);
        } catch (FeignException.NotFound e) {
            throw new TheaterNotFoundException(theaterId);
        }
    }

    public TheaterFeignResponse theaterCbFallback(String theaterId, Throwable ex) {
        throw new ServiceUnavailableException("theater-service");
    }

    public TheaterFeignResponse theaterBulkheadFallback(String theaterId, Throwable ex) {
        throw new ServiceUnavailableException("theater-service");
    }
}

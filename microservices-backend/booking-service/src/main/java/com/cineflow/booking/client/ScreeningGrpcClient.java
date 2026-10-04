package com.cineflow.booking.client;

import com.cineflow.booking.exception.ScreeningNotFoundException;
import com.cineflow.booking.exception.ServiceUnavailableException;
import com.cineflow.contracts.SeatType;
import com.cineflow.contracts.grpc.screening.GetScreeningRequest;
import com.cineflow.contracts.grpc.screening.GetScreeningResponse;
import com.cineflow.contracts.grpc.screening.ScreeningServiceGrpc;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

// Адаптер gRPC-вызова к screening-service под circuit breaker + bulkhead + retry
// (по образцу ScreeningEnrichmentService в screening-service).
//
// Чем вызов отличается от Feign на уровне кода:
//   Feign: объявляешь интерфейс с @GetMapping, Spring генерирует реализацию; вызов —
//          обычный вызов метода интерфейса, ошибки прилетают как FeignException.
//   gRPC:  вызываешь метод сгенерированного стаба (stub.getScreening(request)); аргумент
//          и ответ — protobuf-builder'ы, а не POJO; ошибка — StatusRuntimeException со
//          Status.Code (NOT_FOUND/UNAVAILABLE/...), а не HTTP-код. withDeadlineAfter задаёт
//          дедлайн на весь вызов (аналог read-timeout у Feign).
@Service
@RequiredArgsConstructor
public class ScreeningGrpcClient {

    private final ScreeningServiceGrpc.ScreeningServiceBlockingStub screeningStub;

    @Retry(name = "screening-service")
    @CircuitBreaker(name = "screening-service", fallbackMethod = "cbFallback")
    @Bulkhead(name = "screening-service", fallbackMethod = "bulkheadFallback")
    public ScreeningData getScreening(String screeningId) {
        try {
            GetScreeningResponse response = screeningStub
                    .withDeadlineAfter(5, TimeUnit.SECONDS)
                    .getScreening(GetScreeningRequest.newBuilder()
                            .setScreeningId(screeningId)
                            .build());
            return map(response);
        } catch (StatusRuntimeException e) {
            // NOT_FOUND — «сеанса нет», не сбой сервиса: в ignore-exceptions, CB не трогаем.
            if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
                throw new ScreeningNotFoundException(screeningId);
            }
            // Прочие статусы (UNAVAILABLE, DEADLINE_EXCEEDED, ...) — сбой → считает CB/Retry.
            throw e;
        }
    }

    public ScreeningData cbFallback(String screeningId, Throwable ex) {
        throw new ServiceUnavailableException("screening-service");
    }

    public ScreeningData bulkheadFallback(String screeningId, Throwable ex) {
        throw new ServiceUnavailableException("screening-service");
    }

    private ScreeningData map(GetScreeningResponse r) {
        Map<SeatType, BigDecimal> prices = new EnumMap<>(SeatType.class);
        r.getSeatTypesList().forEach(st ->
                prices.put(parseSeatType(st.getType()), new BigDecimal(st.getPrice())));

        return new ScreeningData(
                r.getId(),
                Instant.parse(r.getStartAt()),
                LocalDate.parse(r.getScreeningDate()),
                r.getMovie().getId(),
                r.getMovie().getTitle(),
                emptyToNull(r.getMovie().getSlug()),
                emptyToNull(r.getMovie().getBanner()),
                r.getTheater().getId(),
                r.getTheater().getName(),
                emptyToNull(r.getTheater().getAddress()),
                r.getHall().getId(),
                r.getHall().getName(),
                prices);
    }

    // Незнакомый тип места (более новая версия contracts) → UNKNOWN, а не падение.
    private SeatType parseSeatType(String name) {
        try {
            return SeatType.valueOf(name);
        } catch (IllegalArgumentException e) {
            return SeatType.UNKNOWN;
        }
    }

    private String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}

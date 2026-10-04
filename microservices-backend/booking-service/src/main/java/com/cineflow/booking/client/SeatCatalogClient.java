package com.cineflow.booking.client;

import com.cineflow.booking.client.dto.SeatFeignResponse;
import com.cineflow.booking.exception.SeatNotFoundException;
import com.cineflow.booking.exception.ServiceUnavailableException;
import feign.FeignException;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

// Адаптер Feign-вызова к theater-service под CB + bulkhead + retry.
@Service
@RequiredArgsConstructor
public class SeatCatalogClient {

    private final SeatClient seatClient;

    @Retry(name = "theater-service")
    @CircuitBreaker(name = "theater-service", fallbackMethod = "cbFallback")
    @Bulkhead(name = "theater-service", fallbackMethod = "bulkheadFallback")
    public SeatFeignResponse fetchSeat(Long seatId) {
        try {
            return seatClient.getSeatById(String.valueOf(seatId));
        } catch (FeignException.NotFound e) {
            throw new SeatNotFoundException(seatId);
        }
    }

    public SeatFeignResponse cbFallback(Long seatId, Throwable ex) {
        throw new ServiceUnavailableException("theater-service");
    }

    public SeatFeignResponse bulkheadFallback(Long seatId, Throwable ex) {
        throw new ServiceUnavailableException("theater-service");
    }
}

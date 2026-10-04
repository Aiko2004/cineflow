package com.cineflow.booking.client;

import com.cineflow.contracts.SeatType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

// Anti-corruption layer: proto-ответ screening-service переводим в удобную нам форму,
// чтобы генерированные gRPC-классы не растекались по доменному коду booking.
public record ScreeningData(
        String id,
        Instant startAt,
        LocalDate screeningDate,
        String movieId,
        String movieTitle,
        String movieSlug,
        String movieBanner,
        String theaterId,
        String theaterName,
        String theaterAddress,
        String hallId,
        String hallName,
        Map<SeatType, BigDecimal> pricesByType
) {
}

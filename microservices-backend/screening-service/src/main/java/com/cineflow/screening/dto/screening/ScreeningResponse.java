package com.cineflow.screening.dto.screening;

import com.cineflow.contracts.SeatType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

// Форма ответа строго по контракту фронтенда:
// ScreeningResponse { id, startAt, endAt, hallId, theater, hall, movie, seatTypes[] }
public record ScreeningResponse(
        String id,
        Instant startAt,
        Instant endAt,
        String hallId,
        TheaterView theater,
        HallView hall,
        MovieView movie,
        List<SeatTypeView> seatTypes
) {

    // ScreeningTheaterResponse { id, name, address }
    public record TheaterView(String id, String name, String address) {}

    // ScreeningHallResponse { id, name }
    public record HallView(String id, String name) {}

    // ScreeningMovieResponse { id, title, slug, banner }
    public record MovieView(String id, String title, String slug, String banner) {}

    // ScreeningSeatTypeResponse { type, price }
    public record SeatTypeView(SeatType type, BigDecimal price) {}
}

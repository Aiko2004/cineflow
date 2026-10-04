package com.cineflow.screening.dto.screening;

import com.cineflow.contracts.SeatType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record CreateScreeningRequest(

        @NotBlank String movieId,
        @NotBlank String theaterId,
        @NotBlank String hallId,

        @NotNull Instant startAt,
        @NotNull Instant endAt,

        // endAt > startAt проверяется в сервисе — Bean Validation не умеет
        // выражать межполевые ограничения без кастомного @Constraint.

        @NotEmpty List<@Valid SeatTypePriceData> seatTypes

) {
    public record SeatTypePriceData(
            @NotNull SeatType type,
            @NotNull @Positive BigDecimal price
    ) {}
}

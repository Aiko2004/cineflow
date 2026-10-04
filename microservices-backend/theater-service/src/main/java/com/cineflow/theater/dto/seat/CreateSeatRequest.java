package com.cineflow.theater.dto.seat;

import com.cineflow.contracts.SeatType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CreateSeatRequest(
        @NotNull(message = "Hall ID is required")
        Long hallId,

        @NotNull(message = "Row number is required")
        @Positive(message = "Row number must be positive")
        Integer rowNumber,

        @NotNull(message = "Seat number is required")
        @Positive(message = "Seat number must be positive")
        Integer seatNumber,

        @NotNull(message = "Seat type is required")
        SeatType type
) {
}

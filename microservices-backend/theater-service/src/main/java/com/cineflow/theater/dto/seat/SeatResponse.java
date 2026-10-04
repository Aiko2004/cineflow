package com.cineflow.theater.dto.seat;

import com.cineflow.contracts.SeatType;

public record SeatResponse(
        Long id,
        Long hallId,
        Integer rowNumber,
        Integer seatNumber,
        SeatType type
) {
}

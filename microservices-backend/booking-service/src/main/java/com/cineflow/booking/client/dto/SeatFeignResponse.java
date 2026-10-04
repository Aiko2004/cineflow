package com.cineflow.booking.client.dto;

import com.cineflow.contracts.SeatType;

// Форма ответа theater-service GET /api/seats/{id}.
public record SeatFeignResponse(
        Long id,
        Long hallId,
        Integer rowNumber,
        Integer seatNumber,
        SeatType type
) {
}

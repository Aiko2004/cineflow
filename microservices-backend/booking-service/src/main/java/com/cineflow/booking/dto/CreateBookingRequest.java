package com.cineflow.booking.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

// user_id НЕ входит в тело — берётся из claim sub токена (BOOKING_DESIGN.md §7).
public record CreateBookingRequest(
        @NotNull UUID screeningId,
        @NotEmpty List<Long> seatIds
) {
}

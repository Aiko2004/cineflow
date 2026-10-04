package com.cineflow.booking.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public record HoldRequest(
        @NotNull UUID screeningId,
        @NotEmpty List<Long> seatIds
) {
}

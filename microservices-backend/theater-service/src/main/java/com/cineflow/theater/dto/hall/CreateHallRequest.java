package com.cineflow.theater.dto.hall;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateHallRequest(
        @NotNull(message = "Theater ID is required")
        Long theaterId,

        @NotBlank(message = "Name is required")
        String name
) {
}

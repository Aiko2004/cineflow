package com.cineflow.theater.dto.hall;

public record HallResponse(
        Long id,
        Long theaterId,
        String name
) {
}

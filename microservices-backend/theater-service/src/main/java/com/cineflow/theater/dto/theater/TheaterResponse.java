package com.cineflow.theater.dto.theater;

public record TheaterResponse(
        Long id,
        String name,
        String address,
        String city
) {
}

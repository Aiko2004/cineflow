package com.cineflow.theater.dto;

public record ErrorResponse(
        int status,
        String message
) {
}

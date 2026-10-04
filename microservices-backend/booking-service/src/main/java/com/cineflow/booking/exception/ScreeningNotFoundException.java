package com.cineflow.booking.exception;

// 404. Сеанс не найден в screening-service (gRPC NOT_FOUND).
public class ScreeningNotFoundException extends RuntimeException {
    public ScreeningNotFoundException(String screeningId) {
        super("Screening not found: " + screeningId);
    }
}

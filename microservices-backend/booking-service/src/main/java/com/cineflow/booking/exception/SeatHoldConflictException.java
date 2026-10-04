package com.cineflow.booking.exception;

// 409. Место удерживает другой пользователь (Redis SET NX не сработал).
public class SeatHoldConflictException extends RuntimeException {
    public SeatHoldConflictException() {
        super("One or more seats are currently held by another user");
    }
}

package com.cineflow.booking.exception;

// 400. В запросе указано место, которого нет в theater-service.
public class SeatNotFoundException extends RuntimeException {
    public SeatNotFoundException(Long seatId) {
        super("Seat not found: " + seatId);
    }
}

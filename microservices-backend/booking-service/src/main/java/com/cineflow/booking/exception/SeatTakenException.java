package com.cineflow.booking.exception;

// 409. Место уже занято активной бронью — нарушение частичного индекса uk_seat_taken.
public class SeatTakenException extends RuntimeException {
    public SeatTakenException() {
        super("One or more seats are already taken");
    }
}

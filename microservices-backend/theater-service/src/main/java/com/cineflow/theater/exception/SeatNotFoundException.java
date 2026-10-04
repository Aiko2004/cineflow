package com.cineflow.theater.exception;

public class SeatNotFoundException extends ResourceNotFoundException {
    public SeatNotFoundException(Long id) {
        super("Seat with id " + id + " not found");
    }
}

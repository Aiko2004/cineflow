package com.cineflow.screening.exception;

public class TheaterNotFoundException extends ResourceNotFoundException {
    public TheaterNotFoundException(String id) {
        super("Theater not found: " + id);
    }
}

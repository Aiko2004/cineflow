package com.cineflow.screening.exception;

public class ScreeningNotFoundException extends ResourceNotFoundException {
    public ScreeningNotFoundException(String id) {
        super("Screening not found: " + id);
    }
}

package com.cineflow.screening.exception;

public class HallNotFoundException extends ResourceNotFoundException {
    public HallNotFoundException(String id) {
        super("Hall not found: " + id);
    }
}

package com.cineflow.screening.exception;

public class MovieNotFoundException extends ResourceNotFoundException {
    public MovieNotFoundException(String id) {
        super("Movie not found: " + id);
    }
}

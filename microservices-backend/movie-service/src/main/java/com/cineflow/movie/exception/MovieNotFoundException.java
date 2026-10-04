package com.cineflow.movie.exception;

public class MovieNotFoundException extends ResourceNotFoundException {
    public MovieNotFoundException(Long id) {
        super("Movie not found: " + id);
    }
}

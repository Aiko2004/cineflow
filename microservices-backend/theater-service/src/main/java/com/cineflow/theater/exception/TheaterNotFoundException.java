package com.cineflow.theater.exception;

public class TheaterNotFoundException extends ResourceNotFoundException{
    public TheaterNotFoundException(Long id) {
        super("Theater with id " + id + " not found");
    }
}

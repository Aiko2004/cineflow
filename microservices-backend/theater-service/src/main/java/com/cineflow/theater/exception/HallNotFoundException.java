package com.cineflow.theater.exception;

public class HallNotFoundException extends ResourceNotFoundException {
    public HallNotFoundException(Long id) {
        super("Hall with id " + id + " not found");
    }
}

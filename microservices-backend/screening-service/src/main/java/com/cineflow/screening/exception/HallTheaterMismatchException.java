package com.cineflow.screening.exception;

public class HallTheaterMismatchException extends BadRequestException {
    public HallTheaterMismatchException(String hallId, String theaterId) {
        super("Hall " + hallId + " does not belong to theater " + theaterId);
    }
}

package com.cineflow.screening.exception;

public class ScreeningConflictException extends BadRequestException {
    public ScreeningConflictException(String hallId) {
        super("Hall " + hallId + " already has a screening in the requested time slot");
    }
}

package com.cineflow.screening.exception;

public class InvalidTimeRangeException extends BadRequestException {
    public InvalidTimeRangeException() {
        super("endAt must be after startAt");
    }
}

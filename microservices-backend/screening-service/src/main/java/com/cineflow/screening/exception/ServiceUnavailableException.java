package com.cineflow.screening.exception;

public class ServiceUnavailableException extends RuntimeException {
    public ServiceUnavailableException(String service) {
        super(service + " is currently unavailable, please try again later");
    }
}

package com.cineflow.booking.exception;

// 503. Бросается из fallback'ов (CB открыт / bulkhead переполнен). В ignore-exceptions
// resilience — чтобы не открывать CB повторно на собственном же fallback.
public class ServiceUnavailableException extends RuntimeException {
    public ServiceUnavailableException(String service) {
        super("Service temporarily unavailable: " + service);
    }
}

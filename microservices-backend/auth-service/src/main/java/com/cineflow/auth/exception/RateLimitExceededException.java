package com.cineflow.auth.exception;

// 429. Превышен лимит запросов кода (по email или по IP).
public class RateLimitExceededException extends RuntimeException {
    public RateLimitExceededException() {
        super("Too many requests, try again later");
    }
}

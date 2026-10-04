package com.cineflow.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

// Типобезопасная конфигурация домена auth. Значения — в application.yaml под cineflow.auth.
// Record + constructor binding: поля неизменяемы, невалидный конфиг падает на старте.
@ConfigurationProperties(prefix = "cineflow.auth")
public record AuthProperties(
        Jwt jwt,
        Otp otp,
        RateLimit rateLimit
) {

    public record Jwt(
            String issuer,
            String audience,
            Duration accessTtl,
            Duration refreshTtl,
            String keyId
    ) {}

    public record Otp(
            Duration ttl,
            int codeLength,
            int maxAttempts
    ) {}

    public record RateLimit(
            Limit email,
            Limit ip
    ) {
        public record Limit(int max, Duration window) {}
    }
}

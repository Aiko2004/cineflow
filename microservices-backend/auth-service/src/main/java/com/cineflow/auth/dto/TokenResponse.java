package com.cineflow.auth.dto;

// Пара токенов. expiresIn — время жизни accessToken в секундах (refresh живёт дольше,
// его срок клиенту знать не обязательно — он просто хранит токен до отказа /refresh).
public record TokenResponse(
        String accessToken,
        String refreshToken,
        long expiresIn
) {
}

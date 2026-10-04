package com.cineflow.auth.exception;

// 401. Refresh-токен не найден, истёк или уже отозван. Сообщение единое —
// не раскрываем, какой именно из случаев (в т.ч. факт срабатывания тревоги).
public class InvalidRefreshTokenException extends RuntimeException {
    public InvalidRefreshTokenException() {
        super("Invalid refresh token");
    }
}

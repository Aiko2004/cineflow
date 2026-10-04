package com.cineflow.auth.dto;

// expiresIn — сколько секунд живёт код. Клиент показывает таймер до повторной отправки.
public record OtpSendResponse(long expiresIn) {
}

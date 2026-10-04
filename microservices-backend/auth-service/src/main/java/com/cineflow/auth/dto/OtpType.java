package com.cineflow.auth.dto;

// Канал доставки кода. Сейчас реализован только EMAIL; PHONE зарезервирован
// под вход по телефону (см. AUTH_DESIGN.md — Telegram/телефон следующими шагами).
// Поле присутствует в запросе, чтобы контракт совпадал с реальным фронтендом.
public enum OtpType {
    EMAIL,
    PHONE
}

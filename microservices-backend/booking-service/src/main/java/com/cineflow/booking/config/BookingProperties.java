package com.cineflow.booking.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

// Параметры домена booking. Значения — в application.yaml под cineflow.booking.
// Числа взяты из BOOKING_DESIGN.md §10 (здравый смысл, пользователей ещё нет).
@ConfigurationProperties(prefix = "cineflow.booking")
public record BookingProperties(
        // TTL мягкого удержания в Redis на этапе выбора мест (клик по схеме зала).
        Duration seatHoldTtl,
        // Сколько бронь держит места в статусе PENDING (окно оформления/оплаты).
        Duration bookingHoldTtl,
        // Лимит мест в одной брони — иначе один запрос заблокирует весь зал.
        int maxSeatsPerBooking
) {
}

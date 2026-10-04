package com.cineflow.booking.exception;

import java.util.UUID;

// 404. Бронь не найдена ИЛИ принадлежит другому пользователю. Намеренно один и тот же
// ответ: 403 подтвердил бы, что бронь существует (BOOKING_DESIGN.md §7).
public class BookingNotFoundException extends RuntimeException {
    public BookingNotFoundException(UUID id) {
        super("Booking not found: " + id);
    }
}

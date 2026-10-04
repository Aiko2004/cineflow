package com.cineflow.booking.model;

// Статусы брони и правила переходов (BOOKING_DESIGN.md §3).
// Правила живут ЗДЕСЬ, а не размазаны по сервису: «из CONFIRMED нельзя в PENDING»
// должно быть в одном месте. Атомарность перехода обеспечивает условный UPDATE
// в репозитории (WHERE status = ожидаемый) — enum задаёт, какой переход вообще допустим.
public enum BookingStatus {
    PENDING,
    CONFIRMED,
    CANCELLED,
    EXPIRED;

    // Подтвердить можно только ожидающую бронь.
    public boolean canConfirm() {
        return this == PENDING;
    }

    // Отменить можно ожидающую или уже подтверждённую (возврат для CONFIRMED — Фаза 4).
    public boolean canCancel() {
        return this == PENDING || this == CONFIRMED;
    }

    // Терминальные состояния: обратных переходов нет.
    public boolean isTerminal() {
        return this == CANCELLED || this == EXPIRED;
    }

    // Держат ли места записи в этом статусе (у их booking_seats released_at пуст).
    public boolean holdsSeats() {
        return this == PENDING || this == CONFIRMED;
    }
}

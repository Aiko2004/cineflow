package com.cineflow.booking.exception;

// 409. Условный UPDATE перехода затронул 0 строк: статус уже изменился
// (подтверждение отменённой брони, отмена терминальной, истёкшее удержание).
public class InvalidStatusTransitionException extends RuntimeException {
    public InvalidStatusTransitionException(String message) {
        super(message);
    }
}

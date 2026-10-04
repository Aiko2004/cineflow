package com.cineflow.booking.exception;

// 400. Некорректный запрос: пусто/слишком много мест, место не из зала сеанса,
// у типа места нет цены в сеансе.
public class BadRequestException extends RuntimeException {
    public BadRequestException(String message) {
        super(message);
    }
}

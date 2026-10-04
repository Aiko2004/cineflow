package com.cineflow.auth.exception;

// 400. Код не найден (истёк/не запрашивался) или не совпал.
// Сообщение намеренно одинаковое для обоих случаев — не подсказываем атакующему,
// была ли вообще заявка на этот email.
public class InvalidOtpException extends RuntimeException {
    public InvalidOtpException() {
        super("Invalid or expired code");
    }
}

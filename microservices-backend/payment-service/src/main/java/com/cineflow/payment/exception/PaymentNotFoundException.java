package com.cineflow.payment.exception;

import java.util.UUID;

// 404. Платёж не найден ИЛИ принадлежит другому пользователю — один и тот же ответ
// (403 подтвердил бы, что платёж существует).
public class PaymentNotFoundException extends RuntimeException {
    public PaymentNotFoundException(UUID id) {
        super("Payment not found: " + id);
    }
}

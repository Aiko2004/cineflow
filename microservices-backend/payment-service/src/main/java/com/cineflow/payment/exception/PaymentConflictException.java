package com.cineflow.payment.exception;

import java.util.UUID;

// 409. Для брони уже есть платёж, но с другими параметрами (пользователь, сумма, телефон).
// Это не повтор запроса, а попытка выставить другой счёт на ту же бронь.
public class PaymentConflictException extends RuntimeException {
    public PaymentConflictException(UUID bookingId) {
        super("Booking " + bookingId + " already has a payment with different parameters");
    }
}

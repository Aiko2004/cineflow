package com.cineflow.payment.messaging;

// Сообщение в payment.commands, которое нельзя обработать НИКОГДА: не JSON, незнакомый type,
// нет обязательных полей, bookingId не совпадает с платежом. Повтор не поможет, поэтому
// DefaultErrorHandler отправляет такое сообщение в .DLT сразу, без попыток (KafkaConsumerConfig).
public class MalformedCommandException extends RuntimeException {
    public MalformedCommandException(String message) {
        super(message);
    }

    public MalformedCommandException(String message, Throwable cause) {
        super(message, cause);
    }
}

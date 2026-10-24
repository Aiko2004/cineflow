package com.cineflow.payment.exception;

// 400. Webhook с верной подписью, но непригодный к обработке: не разбирается как JSON, нет
// обязательных полей, незнакомый статус, счёт не совпадает с записанным у платежа.
public class MalformedWebhookException extends RuntimeException {
    public MalformedWebhookException(String message) {
        super(message);
    }

    public MalformedWebhookException(String message, Throwable cause) {
        super(message, cause);
    }
}

package com.cineflow.payment.exception;

// 401. Подпись webhook отсутствует или не совпала: тело либо подделано, либо подписано чужим
// секретом. Причину наружу не детализируем — подсказки атакующему не нужны.
public class InvalidWebhookSignatureException extends RuntimeException {
    public InvalidWebhookSignatureException() {
        super("Invalid webhook signature");
    }
}

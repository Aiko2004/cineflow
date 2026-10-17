package com.cineflow.payment.gateway;

// 503. Провайдер недоступен или ответил ошибкой. Платёж при этом остаётся в консистентном
// состоянии (PENDING без счёта), повтор запроса дойдёт до провайдера снова.
public class PaymentProviderException extends RuntimeException {
    public PaymentProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}

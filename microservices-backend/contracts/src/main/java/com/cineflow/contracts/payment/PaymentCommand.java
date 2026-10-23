package com.cineflow.contracts.payment;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

// Команды booking-service → payment-service (топик payment.commands).
// Отдельный иерархический корень от PaymentEvent: потребитель топика команд десериализует
// в PaymentCommand и не может по ошибке принять событие за команду (и наоборот).
// Правила эволюции и причина `type` в теле — см. PaymentEvent и PaymentMessage.
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = RefundPayment.class, name = "RefundPayment"),
        @JsonSubTypes.Type(value = CancelInvoice.class, name = "CancelInvoice")
})
@JsonIgnoreProperties(ignoreUnknown = true)
public sealed interface PaymentCommand extends PaymentMessage
        permits RefundPayment, CancelInvoice {
}

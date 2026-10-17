package com.cineflow.contracts.payment;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

// События payment-service → потребители (топик payment.events).
//
// Имена в @JsonSubTypes — часть контракта на проводе; менять и переименовывать их нельзя
// (в топике и в outbox лежат старые сообщения). Новый тип события = новая строка здесь.
// Тип, которого потребитель не знает, даёт ошибку десериализации → после повторов в .DLT:
// молча проглотить событие, смысла которого не понимаем, хуже, чем разобрать его руками.
//
// ignoreUnknown: новые поля в сообщениях добавлять можно — старый потребитель их игнорирует.
// Правило записано в самом контракте, а не зависит от настройки ObjectMapper'а потребителя.
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = PaymentSucceeded.class, name = "PaymentSucceeded"),
        @JsonSubTypes.Type(value = PaymentFailed.class, name = "PaymentFailed"),
        @JsonSubTypes.Type(value = RefundCompleted.class, name = "RefundCompleted")
})
@JsonIgnoreProperties(ignoreUnknown = true)
public sealed interface PaymentEvent extends PaymentMessage
        permits PaymentSucceeded, PaymentFailed, RefundCompleted {
}

package com.cineflow.contracts.payment;

import java.time.Instant;
import java.util.UUID;

// Общий конверт всех сообщений payment-домена (события и команды).
//
// Поля конверта лежат в теле JSON плоско вместе с полями конкретного сообщения. Поле `type`
// пишет и читает Jackson по @JsonTypeInfo на PaymentEvent/PaymentCommand — отдельного
// компонента в record'ах для него нет.
//
// Зачем тип в ТЕЛЕ, а не в заголовке __TypeId__ (стандарт Spring Kafka JsonSerializer):
//  1. Заголовок по умолчанию содержит полное имя Java-класса. Тогда пакет и имя класса
//     становятся частью протокола: переименовал пакет — у потребителя ClassNotFoundException.
//     Здесь на проводе логическое имя ("PaymentSucceeded"), в какой класс оно превращается —
//     дело потребителя.
//  2. Сообщение самодостаточно: его можно положить руками (Kafka UI, kcat) и прочитать из
//     не-Java-сервиса (Media Service на Go) без знания соглашений Spring.
public sealed interface PaymentMessage permits PaymentEvent, PaymentCommand {

    // Уникальный id сообщения. Совпадает с id строки outbox у отправителя: по нему видно, что
    // два сообщения в топике — одна и та же повторная отправка, а не два разных события
    // (трассировка, разбор DLT). Идемпотентность потребителей держится НЕ на нём, а на условных
    // переходах автоматов (PAYMENT_DESIGN.md §5): таблицы обработанных eventId нет.
    UUID eventId();

    Instant occurredAt();

    // Он же ключ сообщения в Kafka: все сообщения одной покупки попадают в одну партицию.
    UUID bookingId();

    UUID paymentId();
}

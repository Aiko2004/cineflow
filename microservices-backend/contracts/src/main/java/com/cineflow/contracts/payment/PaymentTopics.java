package com.cineflow.contracts.payment;

// Имена топиков payment-домена. Пересекают границу сервисов (payment пишет в events и читает
// commands, booking — наоборот), поэтому живут в contracts: опечатка в строковом литерале у
// одного из участников при выключенном автосоздании топиков = сообщения уходят в никуда.
// Только имена: число партиций и репликация — решение владельца топиков (payment-service).
public final class PaymentTopics {

    // payment → booking. Ключ сообщения — bookingId.
    public static final String EVENTS = "payment.events";
    // booking → payment. Ключ сообщения — bookingId.
    public static final String COMMANDS = "payment.commands";

    // Dead-letter: DeadLetterPublishingRecoverer по умолчанию пишет в <topic>.DLT
    // в ТУ ЖЕ партицию, что и исходное сообщение, поэтому у DLT партиций не меньше.
    public static final String EVENTS_DLT = EVENTS + ".DLT";
    public static final String COMMANDS_DLT = COMMANDS + ".DLT";

    private PaymentTopics() {
    }
}

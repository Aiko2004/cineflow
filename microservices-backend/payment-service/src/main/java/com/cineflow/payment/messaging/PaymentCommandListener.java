package com.cineflow.payment.messaging;

import com.cineflow.contracts.payment.CancelInvoice;
import com.cineflow.contracts.payment.PaymentCommand;
import com.cineflow.contracts.payment.PaymentTopics;
import com.cineflow.contracts.payment.RefundPayment;
import com.cineflow.payment.model.Payment;
import com.cineflow.payment.service.CancellationService;
import com.cineflow.payment.service.PaymentPersistence;
import com.cineflow.payment.service.RefundService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

// Потребитель payment.commands (booking → payment). Группа — spring.kafka.consumer.group-id
// (payment-service).
//
// Доставка at-least-once: offset коммитится ПОСЛЕ успешной обработки (ack-mode RECORD), упал
// посреди — сообщение придёт снова. Отдельной таблицы обработанных eventId (inbox) нет:
// каждый обработчик идемпотентен сам, через условные переходы автомата (почему повтор
// безвреден — в RefundService.requestRefund и CancellationService.cancel).
//
// Значение читается как String и разбирается здесь, а не ErrorHandlingDeserializer +
// JacksonJsonDeserializer в конфигурации потребителя. Причина — .DLT: при ошибке разбора
// DeadLetterPublishingRecoverer должен положить туда исходное сообщение. С String-значением
// это ровно тот текст, что пришёл, и его публикует тот же KafkaTemplate<String, String>, что
// и relay. С JSON-десериализатором значение в DLT было бы то byte[] (ошибка разбора), то
// объектом PaymentCommand (ошибка обработки) — понадобился бы второй шаблон с сериализатором,
// выбирающим по типу. Тот же принцип, что у продюсера: в Kafka — готовый JSON-текст.
@Component
@Slf4j
@RequiredArgsConstructor
public class PaymentCommandListener {

    private final JsonMapper jsonMapper;
    private final PaymentPersistence persistence;
    private final RefundService refundService;
    private final CancellationService cancellationService;

    @KafkaListener(topics = PaymentTopics.COMMANDS)
    public void onCommand(ConsumerRecord<String, String> record) {
        PaymentCommand command = parse(record);
        requireSameBooking(command);

        log.info("Command {} {} for payment {} (partition {}, offset {})",
                command.getClass().getSimpleName(), command.eventId(), command.paymentId(),
                record.partition(), record.offset());

        // sealed-иерархия: компилятор проверяет, что обработаны все типы команд.
        switch (command) {
            case RefundPayment refund -> refundService.requestRefund(refund.paymentId());
            case CancelInvoice cancel -> cancellationService.cancel(cancel.paymentId());
        }
    }

    private PaymentCommand parse(ConsumerRecord<String, String> record) {
        if (record.value() == null) {
            throw new MalformedCommandException("Empty command at offset " + record.offset());
        }
        PaymentCommand command;
        try {
            // Тип команды — поле "type" в теле (@JsonTypeInfo на PaymentCommand). Незнакомый type —
            // исключение, а не молчаливый пропуск: команду, которую мы не поняли, нужно увидеть в DLT.
            command = jsonMapper.readValue(record.value(), PaymentCommand.class);
        } catch (JacksonException e) {
            throw new MalformedCommandException("Unreadable command: " + e.getOriginalMessage(), e);
        }
        if (command.paymentId() == null || command.bookingId() == null || command.eventId() == null) {
            throw new MalformedCommandException("Command must contain eventId, bookingId and paymentId");
        }
        return command;
    }

    // Команда адресует платёж по paymentId, но несёт и bookingId (он же ключ сообщения).
    // Несовпадение — дефект отправителя: исполнять такую команду (особенно возврат) нельзя.
    private void requireSameBooking(PaymentCommand command) {
        Payment payment = persistence.get(command.paymentId());   // нет платежа → в DLT без повторов
        if (!payment.getBookingId().equals(command.bookingId())) {
            throw new MalformedCommandException("Command " + command.eventId() + ": payment "
                    + command.paymentId() + " does not belong to booking " + command.bookingId());
        }
    }
}

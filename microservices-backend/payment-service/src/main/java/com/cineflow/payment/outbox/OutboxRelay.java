package com.cineflow.payment.outbox;

import com.cineflow.payment.config.OutboxProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

// Relay: доставляет строки outbox в Kafka (PAYMENT_DESIGN.md §4).
//
// Гарантия — at-least-once. Если процесс упал после того, как брокер подтвердил сообщение, но
// до коммита published_at, при перезапуске сообщение уйдёт ещё раз. Дубль гасит потребитель по
// eventId (= outbox.id) и условные UPDATE — отдельного «ровно один раз» тут нет и не нужно.
@Component
@Slf4j
@RequiredArgsConstructor
public class OutboxRelay {

    private final OutboxRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final TransactionTemplate transactionTemplate;
    private final OutboxProperties properties;

    // fixedDelay: следующий тик начинается через интервал ПОСЛЕ окончания предыдущего, поэтому
    // тики одного экземпляра не накладываются. initialDelay = интервал — в тестах он большой,
    // и фоновый тик не мешает ручному вызову publishBatch().
    @Scheduled(fixedDelayString = "${cineflow.payment.outbox.interval:1000}",
            initialDelayString = "${cineflow.payment.outbox.interval:1000}")
    public void tick() {
        try {
            publishBatch();
        } catch (RuntimeException e) {
            // Например, БД недоступна. Падение тика не должно останавливать расписание.
            log.warn("Outbox relay tick failed: {}", e.toString());
        }
    }

    // Один проход. Возвращает число опубликованных сообщений.
    //
    // Вся пачка — в одной транзакции: строки заблокированы (FOR UPDATE SKIP LOCKED) до
    // коммита. Цена: на время ожидания брокера удерживаются блокировки и соединение с БД.
    // Это ограничено: пачка небольшая, а ожидание каждого сообщения — не дольше sendTimeout.
    public int publishBatch() {
        Integer published = transactionTemplate.execute(status -> {
            List<OutboxMessage> batch = repository.lockUnpublishedBatch(properties.batchSize());
            List<UUID> sent = new ArrayList<>(batch.size());

            for (OutboxMessage message : batch) {
                if (!send(message)) {
                    // Первая же неудача прерывает пачку. Идти дальше нельзя: более позднее
                    // сообщение того же bookingId обогнало бы неотправленное — потребитель
                    // увидел бы, например, возврат раньше оплаты. Следующий тик начнёт с
                    // этого же сообщения.
                    break;
                }
                sent.add(message.id());
            }

            // Подтверждённые до первой ошибки помечаем и коммитим — иначе они уйдут повторно.
            repository.markPublished(sent, OffsetDateTime.now());
            return sent.size();
        });
        return published == null ? 0 : published;
    }

    // Отправка с ожиданием подтверждения брокера (acks=all). Ключ = bookingId: все сообщения
    // одной покупки попадают в одну партицию и читаются в порядке записи.
    private boolean send(OutboxMessage message) {
        try {
            kafkaTemplate.send(message.topic(), message.aggregateId().toString(), message.payload())
                    .get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Outbox relay interrupted while sending {}", message.id());
            return false;
        } catch (ExecutionException | TimeoutException e) {
            log.warn("Outbox relay: broker did not confirm message {} ({}) to {}: {}",
                    message.id(), message.eventType(), message.topic(), e.toString());
            return false;
        } catch (RuntimeException e) {
            // KafkaTemplate.send может бросить синхронно (например, продюсер закрыт).
            log.warn("Outbox relay: send failed for message {} to {}: {}",
                    message.id(), message.topic(), e.toString());
            return false;
        }
    }
}

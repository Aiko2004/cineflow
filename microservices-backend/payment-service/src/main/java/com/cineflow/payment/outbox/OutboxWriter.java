package com.cineflow.payment.outbox;

import com.cineflow.contracts.payment.PaymentEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;

// Запись события в outbox. Вызывается ВНУТРИ транзакции, меняющей состояние платежа.
@Component
@RequiredArgsConstructor
public class OutboxWriter {

    private final OutboxRepository repository;
    private final JsonMapper jsonMapper;

    // MANDATORY: вызов вне транзакции = немедленная ошибка. Это превращает правило «событие
    // пишется в той же транзакции, что и смена состояния» из договорённости в проверку на
    // этапе выполнения. REQUIRED (по умолчанию) молча открыл бы СВОЮ транзакцию, и тогда
    // состояние и событие снова могли бы разойтись — тот самый dual write, ради устранения
    // которого outbox существует.
    @Transactional(propagation = Propagation.MANDATORY)
    public void write(String topic, PaymentEvent event) {
        // Сериализуем в дерево один раз: имя типа берём из самого JSON (поле `type`, его пишет
        // Jackson по @JsonSubTypes контракта). Так логическое имя на проводе и event_type в
        // таблице не могут разойтись — источник один.
        JsonNode tree = jsonMapper.valueToTree(event);
        String eventType = tree.get("type").asString();
        String payload = jsonMapper.writeValueAsString(tree);

        repository.insert(event.eventId(), event.bookingId(), topic, eventType, payload,
                OffsetDateTime.now());
    }
}

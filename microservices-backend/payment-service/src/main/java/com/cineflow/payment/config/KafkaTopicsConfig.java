package com.cineflow.payment.config;

import com.cineflow.contracts.payment.PaymentTopics;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

// Топики payment-домена объявляются здесь NewTopic-бинами; KafkaAdmin создаёт недостающие
// при старте контекста. Автосоздание на брокере выключено (docker-compose.yml).
//
// Почему NewTopic в сервисе, а не init-контейнер в compose (как postgres-init):
//  + определение топика лежит в коде рядом с тем, кто его использует, версионируется вместе
//    с ним и одинаково работает в dev, в тестах (Testcontainers) и в проде — не нужен второй
//    механизм «создания инфраструктуры» только для dev;
//  + payment-service владеет доменом, а значит и топиками событий И команд к нему.
//  - топики создаются только при СТАРТЕ сервиса. Если Kafka была недоступна на старте,
//    они не появятся до рестарта (KafkaAdmin не повторяет попытку). Сервис при этом
//    стартует (fail-fast выключен) — outbox как раз и нужен, чтобы приём платежей не
//    зависел от брокера. Потребитель booking с несуществующим топиком стартует терпимо,
//    но сообщений не получит, пока payment-service не создаст топики.
//  - NewTopic не меняет существующий топик (кроме увеличения числа партиций): правка
//    конфигурации у живого топика делается вручную.
//
// 3 партиции: ключ = bookingId, порядок гарантирован внутри партиции, разные покупки
// обрабатываются параллельно (до 3 потребителей в группе).
@Configuration
@RequiredArgsConstructor
public class KafkaTopicsConfig {

    private static final int PARTITIONS = 3;

    private final PaymentProperties properties;

    @Bean
    public NewTopic paymentEventsTopic() {
        return topic(PaymentTopics.EVENTS);
    }

    @Bean
    public NewTopic paymentCommandsTopic() {
        return topic(PaymentTopics.COMMANDS);
    }

    // DLT-топики имеют столько же партиций: DeadLetterPublishingRecoverer по умолчанию
    // пишет в ту же партицию, что и исходное сообщение; при меньшем числе партиций в DLT
    // отправка упала бы для сообщений из «лишних» партиций.
    @Bean
    public NewTopic paymentEventsDlt() {
        return topic(PaymentTopics.EVENTS_DLT);
    }

    @Bean
    public NewTopic paymentCommandsDlt() {
        return topic(PaymentTopics.COMMANDS_DLT);
    }

    private NewTopic topic(String name) {
        return TopicBuilder.name(name)
                .partitions(PARTITIONS)
                .replicas(properties.topicReplicas())
                .build();
    }
}

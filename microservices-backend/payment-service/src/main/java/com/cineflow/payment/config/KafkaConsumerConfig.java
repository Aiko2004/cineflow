package com.cineflow.payment.config;

import com.cineflow.contracts.payment.PaymentTopics;
import com.cineflow.payment.exception.PaymentNotFoundException;
import org.apache.kafka.common.TopicPartition;
import com.cineflow.payment.messaging.MalformedCommandException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

// Обработка ошибок потребителя. Бин CommonErrorHandler Spring Boot сам подставляет в фабрику
// контейнеров @KafkaListener (KafkaAnnotationDrivenConfiguration) — отдельная фабрика не нужна.
//
// Зачем DLT: Kafka читает партицию строго по порядку. Сообщение, обработка которого падает
// всегда («ядовитое»), без DLT повторялось бы бесконечно и остановило бы все команды этой
// партиции — то есть чужие покупки, попавшие в ту же партицию.
@Configuration
public class KafkaConsumerConfig {

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate,
                                                 CommandsProperties properties) {
        // Публикует упавшее сообщение в DLT, в ТУ ЖЕ партицию (поэтому у DLT столько же партиций —
        // KafkaTopicsConfig), с заголовками kafka_dlt-* (исходные топик/партиция/offset, класс и
        // текст исключения). Шаблон тот же, что у relay: значение — String.
        //
        // Имя DLT задано ЯВНО (PaymentTopics), а не взято по умолчанию. Дефолт библиотеки в
        // spring-kafka 4.x — "<topic>-dlt" (в 2.x/3.x было "<topic>.DLT"; проверено чтением
        // DeadLetterPublishingRecoverer 4.1.1 и тестом). Имя топика — часть контракта: оно не
        // должно меняться от обновления библиотеки. При выключенном автосоздании топиков
        // дефолт вёл бы в несуществующий топик.
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, exception) -> new TopicPartition(deadLetterTopic(record.topic()), record.partition()));

        // Временные сбои (БД, провайдер при отмене счёта) — maxRetries повторов с паузой, затем DLT.
        // Повторы блокирующие: на время пауз партиция стоит. Поэтому попыток и пауза небольшие —
        // долгие сбои закрывают не повторы, а фоновые задачи (сверка, повтор возвратов).
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer,
                new FixedBackOff(properties.retryBackoff().toMillis(), properties.maxRetries()));

        // Не повторять то, что не исправится повтором. Классификатор DefaultErrorHandler смотрит
        // и в причины (исключение листенера приходит обёрнутым в ListenerExecutionFailedException) —
        // проверено чтением ExceptionClassifier в spring-kafka 4.1.1 (traverseCauses = true) и тестом.
        handler.addNotRetryableExceptions(MalformedCommandException.class, PaymentNotFoundException.class);
        return handler;
    }

    // Только топики, которые payment-service потребляет. Неизвестный топик — ошибка конфигурации,
    // а не повод придумать имя DLT на лету.
    private static String deadLetterTopic(String topic) {
        if (PaymentTopics.COMMANDS.equals(topic)) {
            return PaymentTopics.COMMANDS_DLT;
        }
        throw new IllegalStateException("No dead-letter topic configured for " + topic);
    }
}

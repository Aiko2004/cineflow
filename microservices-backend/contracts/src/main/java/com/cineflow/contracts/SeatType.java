package com.cineflow.contracts;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;

/**
 * Тип места. Общий для theater-service (владелец данных),
 * screening-service (цена за тип) и booking-service (что именно бронируют).
 *
 * <p>Правила эволюции: добавлять значения можно — старые сервисы смапят
 * незнакомое значение в {@link #UNKNOWN} вместо падения десериализации.
 * Удалять и переименовывать существующие значения нельзя: старые сообщения
 * в Kafka и старые записи в БД продолжают их содержать.
 *
 * <p>Чтобы {@link JsonEnumDefaultValue} заработала, у сервиса-потребителя
 * должно быть включено:
 * {@code spring.jackson.deserialization.read-unknown-enum-values-using-default-value: true}
 */
public enum SeatType {

    NORMAL,
    VIP,

    /** Значение, которого этот сервис ещё не знает. Не использовать явно. */
    @JsonEnumDefaultValue
    UNKNOWN
}

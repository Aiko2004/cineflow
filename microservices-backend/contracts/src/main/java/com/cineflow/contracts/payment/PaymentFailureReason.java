package com.cineflow.contracts.payment;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;

// Почему платёж не состоялся. Эволюция — по правилам contracts: значения добавлять можно,
// удалять и переименовывать нельзя. UNKNOWN нужен, чтобы потребитель, не знающий нового
// значения, не падал на десериализации (poison pill в Kafka).
public enum PaymentFailureReason {
    EXPIRED,
    DECLINED,
    CANCELLED,
    @JsonEnumDefaultValue
    UNKNOWN
}

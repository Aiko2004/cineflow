package com.cineflow.payment.gateway;

// Статус счёта в словаре ПРОВАЙДЕРА. Намеренно отдельный от PaymentStatus: у чужой системы
// свои состояния (DECLINED, EXPIRED), которые не обязаны совпадать с нашим автоматом, —
// соответствие устанавливает код сверки (anti-corruption, как снапшоты в screening).
public enum InvoiceStatus {
    PENDING,
    PAID,
    DECLINED,
    EXPIRED,
    CANCELLED
}

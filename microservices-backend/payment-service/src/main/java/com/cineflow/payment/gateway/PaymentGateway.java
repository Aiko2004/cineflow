package com.cineflow.payment.gateway;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

// Платёжный провайдер (PAYMENT_DESIGN.md §6). Остальной код payment-service знает только этот
// интерфейс: симулятор Kaspi заменяется настоящим провайдером без правок в домене.
//
// Вызовы идут по сети и могут упасть — реализации бросают PaymentProviderException, а
// вызывающий код вызывает их ВНЕ транзакции БД (иначе соединение и блокировки держатся
// на время чужого HTTP-вызова).
//
// cancelInvoice и refund появятся вместе с обработкой команд (следующий PR).
public interface PaymentGateway {

    // Выставляет счёт на номер телефона. Идемпотентно по paymentId: повторный вызов с тем же
    // paymentId возвращает тот же счёт, а не создаёт второй.
    Invoice createInvoice(UUID paymentId, String phone, BigDecimal amount, Instant expiresAt);

    // Статус счёта у провайдера — для сверки, когда webhook не дошёл.
    InvoiceStatus getInvoiceStatus(String providerInvoiceId);
}

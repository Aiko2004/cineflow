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
public interface PaymentGateway {

    // Выставляет счёт на номер телефона. Идемпотентно по paymentId: повторный вызов с тем же
    // paymentId возвращает тот же счёт, а не создаёт второй.
    Invoice createInvoice(UUID paymentId, String phone, BigDecimal amount, Instant expiresAt);

    // Статус счёта у провайдера — для сверки, когда webhook не дошёл.
    InvoiceStatus getInvoiceStatus(String providerInvoiceId);

    // Отменяет неоплаченный счёт. Идемпотентно: отмена уже отменённого/истёкшего счёта — успех.
    //  true  — счёт больше нельзя оплатить (отменён сейчас или раньше, истёк, отклонён);
    //  false — счёт УЖЕ оплачен, отменить нельзя. Это не ошибка, а исход гонки «пользователь
    //          оплатил, пока шла отмена»: деньги списаны, вызывающий должен считать платёж PAID.
    boolean cancelInvoice(String providerInvoiceId);

    // Полный возврат по оплаченному счёту. Идемпотентно по idempotencyKey: повторный вызов с
    // тем же ключом не возвращает деньги второй раз (ключ = paymentId).
    void refund(String providerInvoiceId, UUID idempotencyKey);
}

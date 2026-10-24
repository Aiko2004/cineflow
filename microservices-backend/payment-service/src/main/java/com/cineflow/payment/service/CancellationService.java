package com.cineflow.payment.service;

import com.cineflow.payment.gateway.PaymentGateway;
import com.cineflow.payment.model.Payment;
import com.cineflow.payment.model.PaymentStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

// Отмена неоплаченного счёта — команда CancelInvoice (пользователь отменил бронь до оплаты).
//
// Порядок: СНАЧАЛА отмена у провайдера, ПОТОМ PENDING → CANCELLED у нас. Обратный порядок
// терял бы деньги в гонке «пользователь оплатил, пока шла отмена»: мы уже CANCELLED, провайдер
// отвечает «счёт оплачен», а условный PENDING → PAID из webhook затронет 0 строк — деньги
// списаны, платёж числится отменённым. При нашем порядке провайдер — источник правды: если
// счёт уже оплачен, платёж становится PAID, booking получает PaymentSucceeded для отменённой
// брони и сам просит возврат (сценарий 7, PAYMENT_DESIGN.md §3).
@Service
@Slf4j
@RequiredArgsConstructor
public class CancellationService {

    private final PaymentPersistence persistence;
    private final PaymentGateway gateway;

    // Почему повтор команды безвреден (естественная идемпотентность, PAYMENT_DESIGN.md §5):
    //  - платёж уже не PENDING (отменён прошлой доставкой, оплачен, истёк) — ничего не делаем;
    //  - процесс упал после отмены у провайдера, но до CANCELLED у нас — offset не закоммичен,
    //    команда придёт снова, платёж ещё PENDING: повторная отмена у провайдера идемпотентна
    //    (cancelInvoice уже отменённого счёта = true), затем условный переход;
    //  - PaymentFailed{CANCELLED} пишется вместе с условным PENDING → CANCELLED — один раз.
    //
    // Провайдер недоступен → PaymentProviderException пробрасывается: Kafka повторит доставку
    // (DefaultErrorHandler), после исчерпания попыток команда уйдёт в .DLT. Платёж при этом
    // остаётся PENDING и не «зависает»: после expires_at его закроет сверка.
    public void cancel(UUID paymentId) {
        Payment payment = persistence.get(paymentId);
        if (payment.getStatus() != PaymentStatus.PENDING) {
            log.info("CancelInvoice for payment {} in status {}: nothing to cancel",
                    paymentId, payment.getStatus());
            return;
        }

        if (payment.getProviderInvoiceId() != null && !gateway.cancelInvoice(payment.getProviderInvoiceId())) {
            // Пользователь успел оплатить. Это не ошибка команды: фиксируем оплату (webhook мог ещё
            // не дойти) — дальше сага сама сделает возврат через RefundPayment.
            log.info("Invoice of payment {} is already paid, cancellation lost the race", paymentId);
            persistence.markPaid(paymentId);
            return;
        }
        // Счёта у провайдера нет (PENDING без счёта) или он отменён/истёк.
        persistence.markCancelled(paymentId);
    }
}

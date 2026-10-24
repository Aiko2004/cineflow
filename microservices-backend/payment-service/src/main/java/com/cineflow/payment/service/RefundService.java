package com.cineflow.payment.service;

import com.cineflow.payment.config.JobsProperties;
import com.cineflow.payment.gateway.PaymentGateway;
import com.cineflow.payment.gateway.PaymentProviderException;
import com.cineflow.payment.model.Payment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.UUID;

// Возврат денег (PAYMENT_DESIGN.md §6): команда RefundPayment и фоновый повтор.
//
//   PAID ──(TX, коммит)──▶ REFUND_PENDING ──refund у провайдера (вне TX)──▶ (TX) REFUNDED + RefundCompleted
//
// Провайдер вызывается вне транзакции (сеть). Если он недоступен, платёж остаётся в
// REFUND_PENDING, а возврат доводит до конца фоновая задача retryStaleRefunds.
//
// Задача рассчитана на ОДИН экземпляр сервиса (как outbox relay). Несколько экземпляров
// корректность не ломают — провайдер идемпотентен по ключу, RefundCompleted привязан к
// условному переходу, — но могут вызвать refund у провайдера по одному платежу одновременно.
// Не проверялось.
@Service
@Slf4j
@RequiredArgsConstructor
public class RefundService {

    private final PaymentPersistence persistence;
    private final PaymentGateway gateway;
    private final JobsProperties jobs;

    // Обработчик команды RefundPayment.
    //
    // Почему повтор команды безвреден (идемпотентность естественная, без таблицы обработанных
    // сообщений — PAYMENT_DESIGN.md §5):
    //  - Провайдера вызывает только тот, чей условный переход PAID → REFUND_PENDING затронул
    //    строку. Из двух доставок одной команды (или двух разных команд на один платёж) переход
    //    выигрывает одна; вторая видит REFUND_PENDING/REFUNDED и ничего не делает.
    //  - Повтор, пришедший, пока платёж в REFUND_PENDING (провайдер был недоступен, или процесс
    //    упал после refund, но до REFUNDED), провайдера НЕ вызывает. Довести возврат — работа
    //    ТОЛЬКО фоновой задачи: иначе каждый повтор команды из Kafka был бы ещё одним вызовом
    //    refund, и число вызовов зависело бы от числа доставок, а не от нашего решения.
    //  - Даже если refund всё же вызван дважды (падение между refund и REFUNDED → фоновая задача
    //    повторяет), провайдер не вернёт деньги второй раз: ключ идемпотентности = paymentId.
    //  - RefundCompleted пишется вместе с условным REFUND_PENDING → REFUNDED — ровно один раз.
    public void requestRefund(UUID paymentId) {
        if (persistence.markRefundPending(paymentId)) {
            tryRefund(persistence.get(paymentId));
            return;
        }
        Payment payment = persistence.get(paymentId);   // нет платежа → PaymentNotFoundException → DLT
        switch (payment.getStatus()) {
            case REFUND_PENDING -> log.info("Refund of payment {} is already in progress; "
                    + "the retry job will finish it", paymentId);
            case REFUNDED -> log.info("Payment {} is already refunded", paymentId);
            // Возвращать нечего. booking шлёт RefundPayment только после PaymentSucceeded, так что
            // для PENDING это означает гонку/дефект; если платёж потом станет PAID, booking
            // получит PaymentSucceeded и попросит возврат снова.
            default -> log.warn("RefundPayment for payment {} in status {}: nothing to refund",
                    paymentId, payment.getStatus());
        }
    }

    @Scheduled(fixedDelayString = "${cineflow.payment.jobs.refund-retry-interval:30000}",
            initialDelayString = "${cineflow.payment.jobs.refund-retry-interval:30000}")
    public void retryTick() {
        try {
            retryStaleRefunds();
        } catch (RuntimeException e) {
            log.warn("Refund retry tick failed: {}", e.toString());
        }
    }

    // Доводит до конца возвраты, застрявшие в REFUND_PENDING. Возвращает число завершённых.
    // Берёт только платежи старше refund-retry-min-age — не вмешивается в возврат, который
    // прямо сейчас выполняет обработчик команды.
    public int retryStaleRefunds() {
        int completed = 0;
        for (Payment payment : persistence.findStaleRefundPending(jobs.refundRetryMinAge(), jobs.batchSize())) {
            if (tryRefund(payment)) {
                completed++;
            }
        }
        return completed;
    }

    private boolean tryRefund(Payment payment) {
        try {
            // Ключ идемпотентности = paymentId: один платёж — один полный возврат.
            gateway.refund(payment.getProviderInvoiceId(), payment.getId());
        } catch (PaymentProviderException e) {
            // Не пробрасываем: команда обработана (намерение записано), повтор — забота задачи.
            // Брось мы исключение, Kafka повторила бы доставку, а повтор команды провайдера
            // всё равно не вызывает (см. requestRefund) — только засорили бы .DLT.
            log.warn("Refund of payment {} failed, stays REFUND_PENDING: {}", payment.getId(), e.toString());
            return false;
        }
        // false — возврат уже отмечен (другой исполнитель успел): RefundCompleted не дублируется.
        persistence.markRefunded(payment.getId());
        return true;
    }
}

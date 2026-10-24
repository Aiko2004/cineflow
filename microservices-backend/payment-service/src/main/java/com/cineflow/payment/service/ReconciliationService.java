package com.cineflow.payment.service;

import com.cineflow.contracts.payment.PaymentFailureReason;
import com.cineflow.payment.config.JobsProperties;
import com.cineflow.payment.gateway.InvoiceStatus;
import com.cineflow.payment.gateway.PaymentGateway;
import com.cineflow.payment.gateway.PaymentProviderException;
import com.cineflow.payment.model.Payment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

// Сверка (reconciliation, PAYMENT_DESIGN.md §6): webhook — не единственный источник правды.
// Он может не дойти никогда (провайдер не достучался, мы были недоступны), и платёж навсегда
// остался бы PENDING, а бронь — в ожидании.
//
// Задача берёт PENDING-платежи с истёкшим expires_at и спрашивает статус у провайдера. Все
// переходы — те же условные UPDATE + outbox, что и у webhook, поэтому гонка «сверка и
// опоздавший webhook одновременно» даёт ровно одно событие.
//
// Рассчитана на ОДИН экземпляр сервиса (как outbox relay и повтор возвратов). Несколько
// экземпляров корректность не ломают (условные UPDATE), но дублируют запросы к провайдеру по
// одним и тем же платежам. Не проверялось.
@Service
@Slf4j
@RequiredArgsConstructor
public class ReconciliationService {

    private final PaymentPersistence persistence;
    private final PaymentGateway gateway;
    private final JobsProperties jobs;

    @Scheduled(fixedDelayString = "${cineflow.payment.jobs.reconciliation-interval:30000}",
            initialDelayString = "${cineflow.payment.jobs.reconciliation-interval:30000}")
    public void tick() {
        try {
            reconcileExpired();
        } catch (RuntimeException e) {
            log.warn("Reconciliation tick failed: {}", e.toString());
        }
    }

    // Один проход. Возвращает число платежей, сменивших статус.
    public int reconcileExpired() {
        int changed = 0;
        for (Payment payment : persistence.findExpiredPending(jobs.batchSize())) {
            try {
                if (reconcile(payment)) {
                    changed++;
                }
            } catch (PaymentProviderException e) {
                // Провайдер недоступен — платёж останется PENDING до следующего тика.
                log.warn("Reconciliation of payment {} postponed: {}", payment.getId(), e.toString());
            }
        }
        return changed;
    }

    private boolean reconcile(Payment payment) {
        if (payment.getProviderInvoiceId() == null) {
            // Счёт так и не был привязан (провайдер был недоступен при создании, или процесс упал
            // до attachInvoice), а срок вышел. Спрашивать провайдера не о чем — id счёта нет;
            // выставлять счёт с истёкшим сроком бессмысленно. Тот же исход, что у позднего
            // повтора создания счёта (PaymentService.createInvoice).
            return persistence.markFailed(payment.getId(), PaymentFailureReason.EXPIRED);
        }

        InvoiceStatus status = gateway.getInvoiceStatus(payment.getProviderInvoiceId());
        return switch (status) {
            case PAID -> {
                // Оплачено, а webhook потерялся.
                log.info("Reconciliation: payment {} was paid, webhook missed", payment.getId());
                yield persistence.markPaid(payment.getId());
            }
            case EXPIRED -> persistence.markFailed(payment.getId(), PaymentFailureReason.EXPIRED);
            case DECLINED -> persistence.markFailed(payment.getId(), PaymentFailureReason.DECLINED);
            case CANCELLED -> persistence.markCancelled(payment.getId());
            // Часы провайдера чуть отстают от наших: следующий тик.
            case PENDING -> false;
        };
    }
}

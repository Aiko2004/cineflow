package com.cineflow.payment.webhook;

import com.cineflow.payment.exception.MalformedWebhookException;
import com.cineflow.payment.gateway.InvoiceStatus;
import com.cineflow.payment.model.Payment;
import com.cineflow.payment.model.PaymentStatus;
import com.cineflow.payment.service.PaymentPersistence;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

// Обработка webhook провайдера (тело уже прошло проверку подписи).
//
// Идемпотентность — естественная, через условный UPDATE: переход PENDING → PAID/FAILED
// затрагивает строку только один раз. Повторный webhook (провайдер повторил доставку,
// симулятор отправил дважды, злоумышленник переиграл перехваченное тело) получает 0 строк —
// событие в outbox не пишется, ответ тот же 200. Поэтому защита от replay по метке времени
// здесь не нужна: переиграть можно только уже применённый исход.
@Component
@Slf4j
@RequiredArgsConstructor
public class KaspiWebhookHandler {

    private final PaymentPersistence persistence;
    private final JsonMapper jsonMapper;

    public void handle(byte[] body) {
        KaspiWebhook webhook = parse(body);
        InvoiceStatus outcome = outcomeOf(webhook.status());

        // Транзакция короткая (один UPDATE + одна строка outbox): провайдер получает 200 быстро,
        // а публикация в Kafka — уже забота relay.
        boolean changed = persistence.applyProviderOutcome(webhook.paymentId(), webhook.invoiceId(), outcome);
        if (changed) {
            log.info("Webhook applied: payment {} -> {}", webhook.paymentId(), outcome);
            return;
        }

        Payment payment = persistence.get(webhook.paymentId());
        if (outcome == InvoiceStatus.PAID
                && (payment.getStatus() == PaymentStatus.FAILED || payment.getStatus() == PaymentStatus.CANCELLED)) {
            // Провайдер списал деньги, а у нас платёж уже неуспешный. В нормальном потоке этого не
            // бывает (истёкший/отменённый счёт оплатить нельзя), но возможно при двойном сбое —
            // см. PAYMENT_DESIGN.md §9. Автоматического возврата из FAILED/CANCELLED автомат не
            // допускает; сейчас — громкий лог и ручной разбор.
            log.error("Provider reports invoice {} PAID, but payment {} is {}: money captured on a "
                            + "non-successful payment, manual refund required",
                    webhook.invoiceId(), payment.getId(), payment.getStatus());
        } else {
            log.info("Duplicate webhook ignored: payment {} is already {} (webhook {})",
                    payment.getId(), payment.getStatus(), outcome);
        }
    }

    private KaspiWebhook parse(byte[] body) {
        KaspiWebhook webhook;
        try {
            webhook = jsonMapper.readValue(body, KaspiWebhook.class);
        } catch (JacksonException e) {
            throw new MalformedWebhookException("Webhook body is not valid JSON", e);
        }
        if (webhook.paymentId() == null || webhook.invoiceId() == null || webhook.status() == null) {
            throw new MalformedWebhookException("Webhook must contain paymentId, invoiceId and status");
        }
        return webhook;
    }

    // Webhook сообщает только финальные исходы оплаты. EXPIRED/CANCELLED провайдер не шлёт —
    // истечение мы определяем сами (сверка), отмену инициируем сами.
    private InvoiceStatus outcomeOf(String status) {
        return switch (status) {
            case "PAID" -> InvoiceStatus.PAID;
            case "DECLINED" -> InvoiceStatus.DECLINED;
            default -> throw new MalformedWebhookException("Unsupported webhook status " + status);
        };
    }
}

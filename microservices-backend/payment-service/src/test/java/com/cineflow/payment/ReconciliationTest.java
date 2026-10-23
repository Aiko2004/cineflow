package com.cineflow.payment;

import com.cineflow.contracts.payment.PaymentTopics;
import com.cineflow.payment.dto.CreateInvoiceRequest;
import com.cineflow.payment.dto.InvoiceResponse;
import com.cineflow.payment.gateway.InvoiceStatus;
import com.cineflow.payment.gateway.PaymentGateway;
import com.cineflow.payment.gateway.PaymentProviderException;
import com.cineflow.payment.model.Payment;
import com.cineflow.payment.service.ReconciliationService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

// Сверка: PENDING-платежи с истёкшим сроком счёта, по которым не пришёл webhook.
class ReconciliationTest extends AbstractPaymentIntegrationTest {

    @Autowired ReconciliationService reconciliation;
    @MockitoSpyBean PaymentGateway gateway;

    private static final UUID USER = UUID.fromString("0199c4f2-0000-7000-8000-0000000000aa");

    private InvoiceResponse newInvoice(UUID bookingId) {
        return paymentService.createInvoice(USER,
                new CreateInvoiceRequest(bookingId, new BigDecimal("3000.00"), "+77011234567"));
    }

    private JsonNode singleEvent(UUID bookingId) {
        outboxRelay.publishBatch();
        List<ConsumerRecord<String, String>> records = readByKey(PaymentTopics.EVENTS, bookingId, 1);
        assertThat(records).hasSize(1);
        return jsonMapper.readTree(records.getFirst().value());
    }

    // Пользователь оплатил, webhook потерялся. Сверка узнаёт об оплате у провайдера.
    @Test
    void lostWebhookIsRecoveredAsPaymentSucceeded() {
        UUID bookingId = UUID.randomUUID();
        InvoiceResponse invoice = newInvoice(bookingId);
        jdbcTemplate.update("UPDATE sim_invoices SET status = 'PAID' WHERE provider_invoice_id = ?",
                invoice.providerInvoiceId());
        expire(invoice.paymentId());

        assertThat(reconciliation.reconcileExpired()).isEqualTo(1);

        assertThat(paymentStatus(invoice.paymentId())).isEqualTo("PAID");
        assertThat(singleEvent(bookingId).get("type").asString()).isEqualTo("PaymentSucceeded");
    }

    @Test
    void unpaidExpiredInvoiceBecomesPaymentFailedExpired() {
        UUID bookingId = UUID.randomUUID();
        InvoiceResponse invoice = newInvoice(bookingId);
        expire(invoice.paymentId());

        assertThat(reconciliation.reconcileExpired()).isEqualTo(1);

        assertThat(paymentStatus(invoice.paymentId())).isEqualTo("FAILED");
        JsonNode event = singleEvent(bookingId);
        assertThat(event.get("type").asString()).isEqualTo("PaymentFailed");
        assertThat(event.get("reason").asString()).isEqualTo("EXPIRED");
    }

    // PENDING без счёта и с истёкшим сроком: провайдера спрашивать не о чем — FAILED(EXPIRED) сразу.
    @Test
    void expiredPaymentWithoutInvoiceFailsWithoutAskingProvider() {
        UUID bookingId = UUID.randomUUID();
        Payment payment = paymentPersistence.createPending(USER, bookingId, new BigDecimal("3000.00"), "+77011234567");
        expire(payment.getId());

        assertThat(reconciliation.reconcileExpired()).isEqualTo(1);

        assertThat(paymentStatus(payment.getId())).isEqualTo("FAILED");
        assertThat(outboxPayloads(bookingId, "PaymentFailed")).singleElement()
                .satisfies(payload -> assertThat(payload).contains("\"reason\": \"EXPIRED\""));
        verify(gateway, never()).getInvoiceStatus(any());
    }

    @Test
    void notYetExpiredPaymentIsLeftAlone() {
        InvoiceResponse invoice = newInvoice(UUID.randomUUID());

        assertThat(reconciliation.reconcileExpired()).isZero();

        assertThat(paymentStatus(invoice.paymentId())).isEqualTo("PENDING");
        verify(gateway, never()).getInvoiceStatus(any());
    }

    // Провайдер недоступен — платёж остаётся PENDING, следующий тик повторит.
    @Test
    void providerOutagePostponesReconciliation() {
        InvoiceResponse invoice = newInvoice(UUID.randomUUID());
        expire(invoice.paymentId());
        doThrow(new PaymentProviderException("down", null)).when(gateway).getInvoiceStatus(any());

        assertThat(reconciliation.reconcileExpired()).isZero();

        assertThat(paymentStatus(invoice.paymentId())).isEqualTo("PENDING");
        assertThat(outboxCount()).isZero();
    }

    // Сверка и опоздавший webhook об одной оплате: событие одно.
    @Test
    void reconciliationThenLateWebhookGivesSingleEvent() {
        UUID bookingId = UUID.randomUUID();
        InvoiceResponse invoice = newInvoice(bookingId);
        jdbcTemplate.update("UPDATE sim_invoices SET status = 'PAID' WHERE provider_invoice_id = ?",
                invoice.providerInvoiceId());
        expire(invoice.paymentId());
        reconciliation.reconcileExpired();

        boolean webhookChanged = paymentPersistence.applyProviderOutcome(invoice.paymentId(),
                invoice.providerInvoiceId(), InvoiceStatus.PAID);

        assertThat(webhookChanged).isFalse();
        assertThat(outboxPayloads(bookingId, "PaymentSucceeded")).hasSize(1);
    }
}

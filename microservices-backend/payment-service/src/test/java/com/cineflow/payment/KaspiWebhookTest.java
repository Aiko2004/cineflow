package com.cineflow.payment;

import com.cineflow.payment.dto.CreateInvoiceRequest;
import com.cineflow.payment.dto.InvoiceResponse;
import com.cineflow.payment.gateway.Invoice;
import com.cineflow.payment.gateway.PaymentGateway;
import com.cineflow.payment.model.Payment;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// POST /payment/webhooks/kaspi: публичный путь, подлинность — HMAC-подпись тела.
// Запросы идут БЕЗ токена — так же, как от провайдера.
@AutoConfigureMockMvc
class KaspiWebhookTest extends AbstractPaymentIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired PaymentGateway gateway;

    private static final UUID USER = UUID.fromString("0199c4f2-0000-7000-8000-0000000000aa");

    private InvoiceResponse newInvoice(UUID bookingId) {
        return paymentService.createInvoice(USER,
                new CreateInvoiceRequest(bookingId, new BigDecimal("1800.00"), "+77011234567"));
    }

    private static byte[] body(String invoiceId, UUID paymentId, String status) {
        return """
                {"invoiceId":"%s","paymentId":"%s","status":"%s","occurredAt":"2026-10-23T10:00:00Z"}
                """.formatted(invoiceId, paymentId, status).getBytes(StandardCharsets.UTF_8);
    }

    private ResultActions send(byte[] body, String signature) throws Exception {
        var request = post("/payment/webhooks/kaspi").contentType(MediaType.APPLICATION_JSON).content(body);
        if (signature != null) {
            request.header("X-Kaspi-Signature", signature);
        }
        return mockMvc.perform(request);
    }

    private ResultActions sendSigned(byte[] body) throws Exception {
        return send(body, sign(body, WEBHOOK_SECRET));
    }

    @Test
    void paidWebhookMarksPaymentPaidAndWritesOneEvent() throws Exception {
        UUID bookingId = UUID.randomUUID();
        InvoiceResponse invoice = newInvoice(bookingId);

        sendSigned(body(invoice.providerInvoiceId(), invoice.paymentId(), "PAID")).andExpect(status().isOk());

        assertThat(paymentStatus(invoice.paymentId())).isEqualTo("PAID");
        assertThat(outboxPayloads(bookingId, "PaymentSucceeded")).hasSize(1);
    }

    @Test
    void duplicateWebhookIsOkButCreatesNoSecondEvent() throws Exception {
        UUID bookingId = UUID.randomUUID();
        InvoiceResponse invoice = newInvoice(bookingId);
        byte[] body = body(invoice.providerInvoiceId(), invoice.paymentId(), "PAID");

        sendSigned(body).andExpect(status().isOk());
        sendSigned(body).andExpect(status().isOk());

        assertThat(paymentStatus(invoice.paymentId())).isEqualTo("PAID");
        assertThat(outboxCount()).isEqualTo(1);
    }

    @Test
    void wrongSignatureIs401AndChangesNothing() throws Exception {
        InvoiceResponse invoice = newInvoice(UUID.randomUUID());
        byte[] body = body(invoice.providerInvoiceId(), invoice.paymentId(), "PAID");

        send(body, sign(body, "attacker-guess")).andExpect(status().isUnauthorized());
        // Подпись от другого тела (подменили статус) — тоже 401.
        send(body, sign(body(invoice.providerInvoiceId(), invoice.paymentId(), "DECLINED"), WEBHOOK_SECRET))
                .andExpect(status().isUnauthorized());
        send(body, "not-hex").andExpect(status().isUnauthorized());

        assertThat(paymentStatus(invoice.paymentId())).isEqualTo("PENDING");
        assertThat(outboxCount()).isZero();
    }

    @Test
    void missingSignatureIs401() throws Exception {
        InvoiceResponse invoice = newInvoice(UUID.randomUUID());

        send(body(invoice.providerInvoiceId(), invoice.paymentId(), "PAID"), null)
                .andExpect(status().isUnauthorized());

        assertThat(paymentStatus(invoice.paymentId())).isEqualTo("PENDING");
    }

    @Test
    void declinedWebhookFailsPaymentWithReasonDeclined() throws Exception {
        UUID bookingId = UUID.randomUUID();
        InvoiceResponse invoice = newInvoice(bookingId);

        sendSigned(body(invoice.providerInvoiceId(), invoice.paymentId(), "DECLINED")).andExpect(status().isOk());

        assertThat(paymentStatus(invoice.paymentId())).isEqualTo("FAILED");
        assertThat(outboxPayloads(bookingId, "PaymentFailed")).singleElement()
                .satisfies(payload -> assertThat(payload).contains("\"reason\": \"DECLINED\""));
    }

    // Webhook пришёл раньше, чем мы записали provider_invoice_id (счёт у провайдера создан,
    // attachInvoice ещё не выполнен). Платёж находится по paymentId, счёт привязывается.
    @Test
    void webhookBeforeInvoiceIsAttachedIsNotLost() throws Exception {
        UUID bookingId = UUID.randomUUID();
        Payment payment = paymentPersistence.createPending(USER, bookingId, new BigDecimal("1800.00"), "+77011234567");
        Invoice invoice = gateway.createInvoice(payment.getId(), payment.getPhone(), payment.getAmount(),
                payment.getExpiresAt().toInstant());

        sendSigned(body(invoice.providerInvoiceId(), payment.getId(), "PAID")).andExpect(status().isOk());

        assertThat(paymentStatus(payment.getId())).isEqualTo("PAID");
        assertThat(jdbcTemplate.queryForObject("SELECT provider_invoice_id FROM payments WHERE id = ?",
                String.class, payment.getId())).isEqualTo(invoice.providerInvoiceId());
        assertThat(outboxPayloads(bookingId, "PaymentSucceeded")).hasSize(1);
    }

    @Test
    void invoiceThatDoesNotMatchPaymentIs400() throws Exception {
        InvoiceResponse invoice = newInvoice(UUID.randomUUID());

        sendSigned(body("SIM-someone-else", invoice.paymentId(), "PAID")).andExpect(status().isBadRequest());

        assertThat(paymentStatus(invoice.paymentId())).isEqualTo("PENDING");
    }

    @Test
    void unknownPaymentIs404() throws Exception {
        sendSigned(body("SIM-x", UUID.randomUUID(), "PAID")).andExpect(status().isNotFound());
    }

    @Test
    void signedButMalformedBodyIs400() throws Exception {
        sendSigned("not json".getBytes(StandardCharsets.UTF_8)).andExpect(status().isBadRequest());
        sendSigned(body("SIM-x", UUID.randomUUID(), "REFUNDED")).andExpect(status().isBadRequest());
    }
}

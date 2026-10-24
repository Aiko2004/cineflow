package com.cineflow.payment;

import com.cineflow.contracts.payment.PaymentTopics;
import com.cineflow.payment.dto.CreateInvoiceRequest;
import com.cineflow.payment.dto.InvoiceResponse;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Полный путь через симулятор: «кнопка в приложении Kaspi» → симулятор шлёт НАСТОЯЩИЙ HTTP-webhook
// с HMAC-подписью в поднятый сервер (RANDOM_PORT) → переход + outbox → relay → Kafka.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class SimulatorFlowTest extends AbstractPaymentIntegrationTest {

    @Autowired MockMvc mockMvc;

    private static final UUID USER = UUID.fromString("0199c4f2-0000-7000-8000-0000000000aa");

    private InvoiceResponse newInvoice(UUID bookingId) {
        return paymentService.createInvoice(USER,
                new CreateInvoiceRequest(bookingId, new BigDecimal("2500.00"), "+77011234567"));
    }

    private void awaitPaymentStatus(UUID paymentId, String expected) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(paymentStatus(paymentId)).isEqualTo(expected));
    }

    private JsonNode singleEvent(UUID bookingId) {
        outboxRelay.publishBatch();
        List<ConsumerRecord<String, String>> records = readByKey(PaymentTopics.EVENTS, bookingId, 1);
        assertThat(records).hasSize(1);
        return jsonMapper.readTree(records.getFirst().value());
    }

    @Test
    void payInSimulatorDeliversWebhookAndPaymentSucceededReachesKafka() throws Exception {
        UUID bookingId = UUID.randomUUID();
        InvoiceResponse invoice = newInvoice(bookingId);

        mockMvc.perform(post("/payment/sim/invoices/{id}/pay", invoice.providerInvoiceId())
                        .with(jwt().jwt(j -> j.subject(USER.toString()))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PAID"));

        awaitPaymentStatus(invoice.paymentId(), "PAID");
        JsonNode event = singleEvent(bookingId);
        assertThat(event.get("type").asString()).isEqualTo("PaymentSucceeded");
        assertThat(event.get("paymentId").asString()).isEqualTo(invoice.paymentId().toString());
        assertThat(event.get("amount").asString()).isEqualTo("2500.00");
    }

    @Test
    void declineInSimulatorProducesPaymentFailedDeclined() throws Exception {
        UUID bookingId = UUID.randomUUID();
        InvoiceResponse invoice = newInvoice(bookingId);

        mockMvc.perform(post("/payment/sim/invoices/{id}/decline", invoice.providerInvoiceId())
                        .with(jwt().jwt(j -> j.subject(USER.toString()))))
                .andExpect(status().isAccepted());

        awaitPaymentStatus(invoice.paymentId(), "FAILED");
        JsonNode event = singleEvent(bookingId);
        assertThat(event.get("type").asString()).isEqualTo("PaymentFailed");
        assertThat(event.get("reason").asString()).isEqualTo("DECLINED");
    }

    // delaySeconds задерживает ДОСТАВКУ webhook: у провайдера счёт оплачен сразу, у нас платёж
    // остаётся PENDING, пока уведомление не придёт.
    @Test
    void delayedWebhookArrivesLater() throws Exception {
        InvoiceResponse invoice = newInvoice(UUID.randomUUID());
        long start = System.nanoTime();

        mockMvc.perform(post("/payment/sim/invoices/{id}/pay", invoice.providerInvoiceId())
                        .param("delaySeconds", "2")
                        .with(jwt().jwt(j -> j.subject(USER.toString()))))
                .andExpect(status().isAccepted());

        assertThat(invoiceStatus(invoice.providerInvoiceId())).isEqualTo("PAID");
        assertThat(paymentStatus(invoice.paymentId())).isEqualTo("PENDING");

        awaitPaymentStatus(invoice.paymentId(), "PAID");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(1900));
    }

    @Test
    void expiredInvoiceCannotBePaid() throws Exception {
        InvoiceResponse invoice = newInvoice(UUID.randomUUID());
        expire(invoice.paymentId());

        mockMvc.perform(post("/payment/sim/invoices/{id}/pay", invoice.providerInvoiceId())
                        .with(jwt().jwt(j -> j.subject(USER.toString()))))
                .andExpect(status().isConflict());

        assertThat(invoiceStatus(invoice.providerInvoiceId())).isEqualTo("PENDING");
    }

    @Test
    void simulatorEndpointsRequireToken() throws Exception {
        InvoiceResponse invoice = newInvoice(UUID.randomUUID());

        mockMvc.perform(post("/payment/sim/invoices/{id}/pay", invoice.providerInvoiceId()))
                .andExpect(status().isUnauthorized());
    }
}

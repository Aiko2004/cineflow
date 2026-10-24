package com.cineflow.payment;

import com.cineflow.payment.dto.CreateInvoiceRequest;
import com.cineflow.payment.dto.InvoiceResponse;
import com.cineflow.payment.exception.PaymentConflictException;
import com.cineflow.payment.gateway.PaymentGateway;
import com.cineflow.payment.gateway.PaymentProviderException;
import com.cineflow.payment.model.PaymentStatus;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

// Создание счёта: идемпотентность по bookingId и поведение при сбоях провайдера.
class PaymentCreationTest extends AbstractPaymentIntegrationTest {

    // Шпион поверх симулятора: по умолчанию настоящее поведение, в отдельных тестах — сбой.
    @MockitoSpyBean PaymentGateway gateway;

    private static final UUID USER = UUID.fromString("0199c4f2-0000-7000-8000-0000000000aa");

    private CreateInvoiceRequest request(UUID bookingId) {
        return new CreateInvoiceRequest(bookingId, new BigDecimal("1500.00"), "+77011234567");
    }

    @Test
    void createsPendingPaymentWithInvoice() {
        UUID bookingId = UUID.randomUUID();

        InvoiceResponse response = paymentService.createInvoice(USER, request(bookingId));

        assertThat(response.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(response.providerInvoiceId()).startsWith("SIM-");
        assertThat(response.expiresAt()).isAfter(java.time.Instant.now());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM payments WHERE booking_id = ?", String.class, bookingId))
                .isEqualTo("PENDING");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM sim_invoices", Integer.class)).isEqualTo(1);
    }

    // Решение PR1: выставление счёта событие НЕ публикует (нет потребителя, как у booking.events).
    // Тест фиксирует это решение — появление события потребует осознанного изменения.
    @Test
    void creatingInvoicePublishesNoEvent() {
        paymentService.createInvoice(USER, request(UUID.randomUUID()));

        assertThat(outboxCount()).isZero();
    }

    @Test
    void repeatWithSameBookingReturnsSamePayment() {
        UUID bookingId = UUID.randomUUID();

        InvoiceResponse first = paymentService.createInvoice(USER, request(bookingId));
        InvoiceResponse second = paymentService.createInvoice(USER, request(bookingId));

        assertThat(second).isEqualTo(first);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM payments WHERE booking_id = ?", Integer.class, bookingId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM sim_invoices", Integer.class)).isEqualTo(1);
        // Повтор не должен ходить к провайдеру: счёт уже привязан.
        verify(gateway, times(1)).createInvoice(any(), any(), any(), any());
    }

    @Test
    void repeatWithDifferentAmountIsConflict() {
        UUID bookingId = UUID.randomUUID();
        paymentService.createInvoice(USER, request(bookingId));

        CreateInvoiceRequest other = new CreateInvoiceRequest(bookingId, new BigDecimal("1.00"), "+77011234567");

        assertThatThrownBy(() -> paymentService.createInvoice(USER, other))
                .isInstanceOf(PaymentConflictException.class);
    }

    @Test
    void anotherUserCannotTakeOverTheBooking() {
        UUID bookingId = UUID.randomUUID();
        paymentService.createInvoice(USER, request(bookingId));

        assertThatThrownBy(() -> paymentService.createInvoice(UUID.randomUUID(), request(bookingId)))
                .isInstanceOf(PaymentConflictException.class);
    }

    @Test
    void parallelRequestsForSameBookingProduceOnePaymentAndOneInvoice() throws Exception {
        UUID bookingId = UUID.randomUUID();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<InvoiceResponse>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return paymentService.createInvoice(USER, request(bookingId));
            }));
        }
        start.countDown();

        List<InvoiceResponse> responses = new ArrayList<>();
        for (Future<InvoiceResponse> f : futures) {
            responses.add(f.get());
        }
        pool.shutdown();

        assertThat(responses).doesNotContainNull().hasSize(threads);
        assertThat(responses).allSatisfy(r -> assertThat(r).isEqualTo(responses.get(0)));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM payments WHERE booking_id = ?", Integer.class, bookingId)).isEqualTo(1);
        // Счёт у провайдера один: симулятор идемпотентен по paymentId (UNIQUE + ON CONFLICT).
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM sim_invoices", Integer.class)).isEqualTo(1);
    }

    @Test
    void providerFailureLeavesPendingPaymentAndRetryCompletesIt() {
        UUID bookingId = UUID.randomUUID();
        doThrow(new PaymentProviderException("down", null))
                .when(gateway).createInvoice(any(), any(), any(), any());

        assertThatThrownBy(() -> paymentService.createInvoice(USER, request(bookingId)))
                .isInstanceOf(PaymentProviderException.class);

        // Намерение зафиксировано, счёта ещё нет.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT provider_invoice_id FROM payments WHERE booking_id = ?", String.class, bookingId))
                .isNull();

        // Провайдер «ожил» — тот же запрос самовосстанавливается на том же платеже.
        doCallRealMethod().when(gateway).createInvoice(any(), any(), any(), any());
        InvoiceResponse response = paymentService.createInvoice(USER, request(bookingId));

        assertThat(response.providerInvoiceId()).startsWith("SIM-");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM payments WHERE booking_id = ?", Integer.class, bookingId)).isEqualTo(1);
    }

    // Граничный случай: платёж PENDING без счёта (провайдер был недоступен), а повтор запроса
    // пришёл, когда срок счёта уже вышел. Счёт с истёкшим сроком не выставляется — FAILED(EXPIRED).
    @Test
    void lateRetryOfPendingWithoutInvoiceFailsWithExpiredInsteadOfCreatingInvoice() {
        UUID bookingId = UUID.randomUUID();
        doThrow(new PaymentProviderException("down", null))
                .when(gateway).createInvoice(any(), any(), any(), any());
        assertThatThrownBy(() -> paymentService.createInvoice(USER, request(bookingId)))
                .isInstanceOf(PaymentProviderException.class);
        doCallRealMethod().when(gateway).createInvoice(any(), any(), any(), any());
        UUID paymentId = jdbcTemplate.queryForObject(
                "SELECT id FROM payments WHERE booking_id = ?", UUID.class, bookingId);
        expire(paymentId);

        InvoiceResponse response = paymentService.createInvoice(USER, request(bookingId));

        assertThat(response.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(response.providerInvoiceId()).isNull();
        assertThat(outboxPayloads(bookingId, "PaymentFailed")).singleElement()
                .satisfies(payload -> assertThat(payload).contains("\"reason\": \"EXPIRED\""));
        // Провайдера звали один раз — в первой (упавшей) попытке; поздний повтор до него не дошёл.
        verify(gateway, times(1)).createInvoice(any(), any(), any(), any());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM sim_invoices", Integer.class)).isZero();

        // Ещё один повтор: платёж уже FAILED — ни счёта, ни второго события.
        InvoiceResponse again = paymentService.createInvoice(USER, request(bookingId));

        assertThat(again.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(outboxCount()).isEqualTo(1);
        verify(gateway, times(1)).createInvoice(any(), any(), any(), any());
    }
}

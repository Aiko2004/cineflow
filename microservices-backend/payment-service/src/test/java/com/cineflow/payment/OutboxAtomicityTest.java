package com.cineflow.payment;

import com.cineflow.contracts.payment.PaymentFailureReason;
import com.cineflow.contracts.payment.PaymentTopics;
import com.cineflow.contracts.payment.RefundCompleted;
import com.cineflow.payment.dto.CreateInvoiceRequest;
import com.cineflow.payment.dto.InvoiceResponse;
import com.cineflow.payment.outbox.OutboxWriter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Смена статуса платежа и запись события — одна транзакция.
class OutboxAtomicityTest extends AbstractPaymentIntegrationTest {

    @Autowired TransactionTemplate transactionTemplate;
    @Autowired OutboxWriter outboxWriter;

    private static final UUID USER = UUID.fromString("0199c4f2-0000-7000-8000-0000000000aa");

    private InvoiceResponse newInvoice(UUID bookingId) {
        return paymentService.createInvoice(USER,
                new CreateInvoiceRequest(bookingId, new BigDecimal("2000.00"), "+77011234567"));
    }

    private String status(UUID paymentId) {
        return jdbcTemplate.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, paymentId);
    }

    @Test
    void markPaidChangesStatusAndWritesEventTogether() {
        UUID bookingId = UUID.randomUUID();
        UUID paymentId = newInvoice(bookingId).paymentId();

        boolean changed = paymentPersistence.markPaid(paymentId);

        assertThat(changed).isTrue();
        assertThat(status(paymentId)).isEqualTo("PAID");
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT aggregate_id, topic, event_type, published_at, payload::text AS payload FROM outbox");
        assertThat(row.get("aggregate_id")).isEqualTo(bookingId);
        assertThat(row.get("topic")).isEqualTo(PaymentTopics.EVENTS);
        assertThat(row.get("event_type")).isEqualTo("PaymentSucceeded");
        assertThat(row.get("published_at")).isNull();
        assertThat((String) row.get("payload")).contains("\"amount\": \"2000.00\"");
    }

    @Test
    void repeatedTransitionIsNoOpAndCreatesNoSecondEvent() {
        UUID paymentId = newInvoice(UUID.randomUUID()).paymentId();
        paymentPersistence.markPaid(paymentId);

        // Повторный webhook / повторная обработка: статус уже PAID.
        boolean second = paymentPersistence.markPaid(paymentId);
        boolean failedAfterPaid = paymentPersistence.markFailed(paymentId, PaymentFailureReason.EXPIRED);

        assertThat(second).isFalse();
        assertThat(failedAfterPaid).isFalse();
        assertThat(status(paymentId)).isEqualTo("PAID");
        assertThat(outboxCount()).isEqualTo(1);
    }

    @Test
    void markFailedWritesFailureEventWithReason() {
        UUID paymentId = newInvoice(UUID.randomUUID()).paymentId();

        assertThat(paymentPersistence.markFailed(paymentId, PaymentFailureReason.DECLINED)).isTrue();

        assertThat(status(paymentId)).isEqualTo("FAILED");
        assertThat(jdbcTemplate.queryForObject("SELECT event_type FROM outbox", String.class))
                .isEqualTo("PaymentFailed");
        assertThat(jdbcTemplate.queryForObject("SELECT payload->>'reason' FROM outbox", String.class))
                .isEqualTo("DECLINED");
    }

    // Откат внешней транзакции откатывает ОБА изменения. Если бы outbox писался в своей
    // транзакции (REQUIRES_NEW), строка события пережила бы откат платежа.
    @Test
    void rollbackUndoesBothStatusAndEvent() {
        UUID paymentId = newInvoice(UUID.randomUUID()).paymentId();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(tx -> {
            paymentPersistence.markPaid(paymentId);
            throw new IllegalStateException("boom after state change");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(status(paymentId)).isEqualTo("PENDING");
        assertThat(outboxCount()).isZero();
    }

    @Test
    void writerRefusesToRunOutsideTransaction() {
        RefundCompleted event = new RefundCompleted(UUID.randomUUID(), Instant.now(),
                UUID.randomUUID(), UUID.randomUUID(), "1.00", "KZT");

        assertThatThrownBy(() -> outboxWriter.write(PaymentTopics.EVENTS, event))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(outboxCount()).isZero();
    }
}

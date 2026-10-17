package com.cineflow.payment;

import com.cineflow.payment.model.PaymentStatus;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

// Автомат платежа целиком: разрешённые переходы перечислены явно, всё остальное запрещено.
class PaymentStatusTest {

    private static final Map<PaymentStatus, Set<PaymentStatus>> ALLOWED = Map.of(
            PaymentStatus.PENDING, Set.of(PaymentStatus.PAID, PaymentStatus.FAILED, PaymentStatus.CANCELLED),
            PaymentStatus.PAID, Set.of(PaymentStatus.REFUND_PENDING),
            PaymentStatus.REFUND_PENDING, Set.of(PaymentStatus.REFUNDED),
            PaymentStatus.FAILED, Set.of(),
            PaymentStatus.CANCELLED, Set.of(),
            PaymentStatus.REFUNDED, Set.of());

    @Test
    void everyPairMatchesTheAutomaton() {
        for (PaymentStatus from : PaymentStatus.values()) {
            for (PaymentStatus to : PaymentStatus.values()) {
                assertThat(from.canTransitionTo(to))
                        .as("%s -> %s", from, to)
                        .isEqualTo(ALLOWED.get(from).contains(to));
            }
        }
    }

    @Test
    void terminalStatesHaveNoWayOut() {
        for (PaymentStatus status : PaymentStatus.values()) {
            assertThat(status.isTerminal()).isEqualTo(ALLOWED.get(status).isEmpty());
        }
    }
}

package com.cineflow.payment.model;

// Автомат платежа (PAYMENT_DESIGN.md §4):
//
//   PENDING ──оплата──▶ PAID ──RefundPayment──▶ REFUND_PENDING ──провайдер ок──▶ REFUNDED
//      ├──истёк/отклонён──▶ FAILED
//      └──CancelInvoice───▶ CANCELLED
//
// Как у BookingStatus: правила допустимых переходов — ЗДЕСЬ, в одном месте; атомарность
// самого перехода обеспечивает условный UPDATE (WHERE status = ожидаемый) в репозитории.
public enum PaymentStatus {
    PENDING,
    PAID,
    FAILED,
    CANCELLED,
    REFUND_PENDING,
    REFUNDED;

    public boolean canTransitionTo(PaymentStatus next) {
        return switch (this) {
            case PENDING -> next == PAID || next == FAILED || next == CANCELLED;
            case PAID -> next == REFUND_PENDING;
            case REFUND_PENDING -> next == REFUNDED;
            case FAILED, CANCELLED, REFUNDED -> false;
        };
    }

    // Терминальные: из них выхода нет.
    public boolean isTerminal() {
        return this == FAILED || this == CANCELLED || this == REFUNDED;
    }
}

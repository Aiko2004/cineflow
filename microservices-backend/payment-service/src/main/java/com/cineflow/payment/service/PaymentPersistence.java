package com.cineflow.payment.service;

import com.cineflow.contracts.payment.PaymentFailed;
import com.cineflow.contracts.payment.PaymentFailureReason;
import com.cineflow.contracts.payment.PaymentSucceeded;
import com.cineflow.contracts.payment.PaymentTopics;
import com.cineflow.payment.config.PaymentProperties;
import com.cineflow.payment.exception.PaymentNotFoundException;
import com.cineflow.payment.model.Payment;
import com.cineflow.payment.model.PaymentStatus;
import com.cineflow.payment.outbox.OutboxWriter;
import com.cineflow.payment.repository.PaymentRepository;
import com.github.f4b6a3.uuid.UuidCreator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

// Транзакционные шаги над платежом. Вынесены из PaymentService в отдельный бин: вызов
// @Transactional-метода из того же класса идёт мимо прокси и транзакция не открывается.
// Заодно это даёт нужное разделение — вызовы провайдера (сеть) живут в PaymentService и
// находятся ВНЕ этих транзакций.
//
// Здесь же — все смены статуса. Каждая — «условный UPDATE + запись события в outbox» в ОДНОЙ
// транзакции: либо оба, либо ни одного (OutboxWriter требует активную транзакцию).
@Component
@RequiredArgsConstructor
public class PaymentPersistence {

    private final PaymentRepository payments;
    private final OutboxWriter outbox;
    private final PaymentProperties properties;

    @Transactional(readOnly = true)
    public Optional<Payment> findByBookingId(UUID bookingId) {
        return payments.findByBookingId(bookingId);
    }

    @Transactional(readOnly = true)
    public Payment getOwned(UUID paymentId, UUID userId) {
        return payments.findByIdAndUserId(paymentId, userId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));
    }

    @Transactional(readOnly = true)
    public Payment get(UUID paymentId) {
        return payments.findById(paymentId).orElseThrow(() -> new PaymentNotFoundException(paymentId));
    }

    // Фиксируем намерение ДО обращения к провайдеру: сначала запись «хотим выставить счёт»,
    // потом внешнее действие. Наоборот (счёт у провайдера, потом запись) при падении между
    // шагами оставляло бы у провайдера счёт, о котором мы не знаем, — а по нему можно заплатить.
    // saveAndFlush: нарушение uk_payments_booking должно всплыть здесь, а не при коммите.
    @Transactional
    public Payment createPending(UUID userId, UUID bookingId, BigDecimal amount, String phone) {
        OffsetDateTime now = OffsetDateTime.now();
        Payment payment = new Payment();
        payment.setId(UuidCreator.getTimeOrderedEpoch());
        payment.setBookingId(bookingId);
        payment.setUserId(userId);
        payment.setAmount(amount);
        payment.setCurrency(properties.currency());
        payment.setPhone(phone);
        payment.setStatus(PaymentStatus.PENDING);
        payment.setExpiresAt(now.plus(properties.invoiceTtl()));
        payment.setCreatedAt(now);
        payment.setUpdatedAt(now);
        return payments.saveAndFlush(payment);
    }

    @Transactional
    public Payment attachInvoice(UUID paymentId, String providerInvoiceId) {
        payments.attachInvoice(paymentId, providerInvoiceId, OffsetDateTime.now());
        return get(paymentId);
    }

    // PENDING → PAID + PaymentSucceeded. false — платёж уже не PENDING (повтор webhook,
    // отмена выиграла гонку): событие НЕ создаётся.
    @Transactional
    public boolean markPaid(UUID paymentId) {
        if (!transition(paymentId, PaymentStatus.PENDING, PaymentStatus.PAID)) {
            return false;
        }
        Payment payment = get(paymentId);
        outbox.write(PaymentTopics.EVENTS, new PaymentSucceeded(
                UuidCreator.getTimeOrderedEpoch(), Instant.now(),
                payment.getBookingId(), payment.getId(),
                payment.getAmount().toPlainString(), payment.getCurrency()));
        return true;
    }

    // PENDING → FAILED + PaymentFailed{reason}.
    @Transactional
    public boolean markFailed(UUID paymentId, PaymentFailureReason reason) {
        if (!transition(paymentId, PaymentStatus.PENDING, PaymentStatus.FAILED)) {
            return false;
        }
        Payment payment = get(paymentId);
        outbox.write(PaymentTopics.EVENTS, new PaymentFailed(
                UuidCreator.getTimeOrderedEpoch(), Instant.now(),
                payment.getBookingId(), payment.getId(), reason));
        return true;
    }

    private boolean transition(UUID paymentId, PaymentStatus from, PaymentStatus to) {
        if (!from.canTransitionTo(to)) {
            // Ошибка программиста, а не состояния данных: такой переход автомат не допускает.
            throw new IllegalStateException("Illegal payment transition " + from + " -> " + to);
        }
        return payments.transition(paymentId, from, to, OffsetDateTime.now()) == 1;
    }
}

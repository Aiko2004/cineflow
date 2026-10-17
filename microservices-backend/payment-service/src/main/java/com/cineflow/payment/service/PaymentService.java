package com.cineflow.payment.service;

import com.cineflow.payment.dto.CreateInvoiceRequest;
import com.cineflow.payment.dto.InvoiceResponse;
import com.cineflow.payment.dto.PaymentResponse;
import com.cineflow.payment.exception.PaymentConflictException;
import com.cineflow.payment.gateway.Invoice;
import com.cineflow.payment.gateway.PaymentGateway;
import com.cineflow.payment.model.Payment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

// Оркестрация создания счёта. Сам класс НЕ транзакционный: вызов провайдера идёт по сети
// и должен быть вне транзакции БД; транзакционные шаги — в PaymentPersistence.
@Service
@Slf4j
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentPersistence persistence;
    private final PaymentGateway gateway;

    // Выставить счёт. Идемпотентно по bookingId: повтор возвращает тот же платёж.
    //
    // Шаги и что остаётся при падении между ними:
    //  1. запись платежа PENDING (счёта ещё нет)  — падение: платежа нет, повтор создаст;
    //  2. createInvoice у провайдера (вне TX)     — падение: платёж PENDING без счёта;
    //  3. запись provider_invoice_id              — падение: то же, что после шага 2.
    // Состояние «PENDING без счёта» самовосстанавливается: повтор того же запроса находит
    // платёж, видит пустой provider_invoice_id и повторяет шаги 2–3. Провайдер идемпотентен
    // по paymentId, поэтому второй счёт не появится.
    public InvoiceResponse createInvoice(UUID userId, CreateInvoiceRequest request) {
        BigDecimal amount = request.amount().setScale(2);   // валидация гарантирует ≤ 2 знаков
        Payment payment = findOrCreate(userId, request, amount);

        if (payment.getProviderInvoiceId() == null) {
            Invoice invoice = gateway.createInvoice(
                    payment.getId(), payment.getPhone(), payment.getAmount(),
                    payment.getExpiresAt().toInstant());
            payment = persistence.attachInvoice(payment.getId(), invoice.providerInvoiceId());
        }
        return new InvoiceResponse(payment.getId(), payment.getProviderInvoiceId(),
                payment.getExpiresAt().toInstant(), payment.getStatus());
    }

    public PaymentResponse getOwned(UUID userId, UUID paymentId) {
        Payment payment = persistence.getOwned(paymentId, userId);
        return new PaymentResponse(payment.getId(), payment.getBookingId(), payment.getAmount(),
                payment.getCurrency(), payment.getStatus(), payment.getExpiresAt().toInstant(),
                payment.getPaidAt() == null ? null : payment.getPaidAt().toInstant());
    }

    private Payment findOrCreate(UUID userId, CreateInvoiceRequest request, BigDecimal amount) {
        Optional<Payment> existing = persistence.findByBookingId(request.bookingId());
        if (existing.isEmpty()) {
            try {
                return persistence.createPending(userId, request.bookingId(), amount, request.phone());
            } catch (DataIntegrityViolationException e) {
                // Параллельный запрос с тем же bookingId успел вставить раньше (uk_payments_booking).
                // Повторное чтение — снаружи упавшей транзакции: внутри неё Postgres уже не
                // принимает команд. Если платежа нет, значит нарушение другое — пробрасываем.
                existing = persistence.findByBookingId(request.bookingId());
                if (existing.isEmpty()) {
                    throw e;
                }
            }
        }
        return requireSameRequest(existing.get(), userId, amount, request.phone());
    }

    // Повтор считается повтором, только если параметры те же. Иначе молча вернуть платёж с
    // ДРУГОЙ суммой — значит выставить пользователю не тот счёт, который запрошен.
    private Payment requireSameRequest(Payment payment, UUID userId, BigDecimal amount, String phone) {
        if (!payment.getUserId().equals(userId)
                || payment.getAmount().compareTo(amount) != 0
                || !payment.getPhone().equals(phone)) {
            throw new PaymentConflictException(payment.getBookingId());
        }
        return payment;
    }
}

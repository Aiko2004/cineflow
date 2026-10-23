package com.cineflow.payment.simulator;

import com.cineflow.payment.gateway.Invoice;
import com.cineflow.payment.gateway.InvoiceStatus;
import com.cineflow.payment.gateway.PaymentGateway;
import com.cineflow.payment.gateway.PaymentProviderException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

// Симулятор Kaspi: «внешняя система» внутри payment-service (PAYMENT_DESIGN.md §6).
// Живёт в профиле kaspi-sim и хранит счета в СВОЕЙ таблице sim_invoices — без связи с payments,
// как настоящая система, не имеющая доступа к нашей БД. Остальной код общается с ним только
// через интерфейс PaymentGateway.
//
// Таблица — через JdbcClient, не JPA: это не доменная сущность, а имитация чужого хранилища.
//
// Статусы счёта в sim_invoices: PENDING, PAID, DECLINED, CANCELLED, REFUNDED (последний
// добавлен с возвратами; в комментарии V2 его нет — применённую миграцию не правим, CHECK-
// ограничения на колонке нет). EXPIRED не хранится, а выводится из expires_at.
//
// Сторона «пользователь платит в приложении» и отправка webhook — KaspiSimulatorApp.
@Component
@Profile("kaspi-sim")
@RequiredArgsConstructor
public class KaspiSimulatorGateway implements PaymentGateway {

    private final JdbcClient jdbc;

    // Идемпотентно по paymentId: ON CONFLICT DO NOTHING + чтение результата. Два параллельных
    // вызова не создадут два счёта (arbiter — UNIQUE по payment_id), оба получат один и тот же.
    @Override
    @Transactional
    public Invoice createInvoice(UUID paymentId, String phone, BigDecimal amount, Instant expiresAt) {
        try {
            OffsetDateTime now = OffsetDateTime.now();
            jdbc.sql("""
                            INSERT INTO sim_invoices (provider_invoice_id, payment_id, phone, amount, status,
                                                      expires_at, created_at, updated_at)
                            VALUES (:invoiceId, :paymentId, :phone, :amount, 'PENDING', :expiresAt, :now, :now)
                            ON CONFLICT (payment_id) DO NOTHING
                            """)
                    .param("invoiceId", "SIM-" + UUID.randomUUID())
                    .param("paymentId", paymentId)
                    .param("phone", phone)
                    .param("amount", amount)
                    .param("expiresAt", OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC))
                    .param("now", now)
                    .update();

            return jdbc.sql("SELECT provider_invoice_id, expires_at FROM sim_invoices WHERE payment_id = :paymentId")
                    .param("paymentId", paymentId)
                    .query((rs, rowNum) -> new Invoice(
                            rs.getString("provider_invoice_id"),
                            rs.getObject("expires_at", OffsetDateTime.class).toInstant()))
                    .single();
        } catch (DataAccessException e) {
            throw new PaymentProviderException("Kaspi simulator is unavailable", e);
        }
    }

    // Истечение выводится из expires_at, а не хранится: счёт, который никто не трогал, истекает
    // сам по часам, без фоновой задачи в «приложении Kaspi».
    @Override
    @Transactional(readOnly = true)
    public InvoiceStatus getInvoiceStatus(String providerInvoiceId) {
        try {
            return jdbc.sql("SELECT status, expires_at FROM sim_invoices WHERE provider_invoice_id = :id")
                    .param("id", providerInvoiceId)
                    .query((rs, rowNum) -> {
                        InvoiceStatus stored = InvoiceStatus.valueOf(rs.getString("status"));
                        Instant expiresAt = rs.getObject("expires_at", OffsetDateTime.class).toInstant();
                        return stored == InvoiceStatus.PENDING && expiresAt.isBefore(Instant.now())
                                ? InvoiceStatus.EXPIRED
                                : stored;
                    })
                    .optional()
                    .orElseThrow(() -> new PaymentProviderException(
                            "Unknown invoice " + providerInvoiceId, null));
        } catch (DataAccessException e) {
            throw new PaymentProviderException("Kaspi simulator is unavailable", e);
        }
    }

    // Отменить можно только PENDING, срок которого не вышел. Всё остальное — не ошибка:
    // отменённый/отклонённый/истёкший счёт и так нельзя оплатить (true), оплаченный — нельзя
    // отменить (false, см. контракт PaymentGateway).
    @Override
    @Transactional
    public boolean cancelInvoice(String providerInvoiceId) {
        try {
            int cancelled = jdbc.sql("""
                            UPDATE sim_invoices SET status = 'CANCELLED', updated_at = :now
                             WHERE provider_invoice_id = :id AND status = 'PENDING' AND expires_at > :now
                            """)
                    .param("id", providerInvoiceId)
                    .param("now", OffsetDateTime.now())
                    .update();
            if (cancelled == 1) {
                return true;
            }
            String status = storedStatus(providerInvoiceId);
            return !("PAID".equals(status) || "REFUNDED".equals(status));
        } catch (DataAccessException e) {
            throw new PaymentProviderException("Kaspi simulator is unavailable", e);
        }
    }

    // Только полный возврат: PAID → REFUNDED. Идемпотентность симулятора — по статусу счёта
    // (повторный возврат уже возвращённого счёта — успех без второго движения денег). Настоящий
    // провайдер хранил бы idempotencyKey и отвечал на повтор с тем же ключом сохранённым
    // результатом; симулятору с полными возвратами это не нужно — ключ принимается ради контракта.
    @Override
    @Transactional
    public void refund(String providerInvoiceId, UUID idempotencyKey) {
        try {
            int refunded = jdbc.sql("""
                            UPDATE sim_invoices SET status = 'REFUNDED', updated_at = :now
                             WHERE provider_invoice_id = :id AND status = 'PAID'
                            """)
                    .param("id", providerInvoiceId)
                    .param("now", OffsetDateTime.now())
                    .update();
            if (refunded == 0 && !"REFUNDED".equals(storedStatus(providerInvoiceId))) {
                throw new PaymentProviderException("Invoice " + providerInvoiceId + " is not paid", null);
            }
        } catch (DataAccessException e) {
            throw new PaymentProviderException("Kaspi simulator is unavailable", e);
        }
    }

    private String storedStatus(String providerInvoiceId) {
        return jdbc.sql("SELECT status FROM sim_invoices WHERE provider_invoice_id = :id")
                .param("id", providerInvoiceId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new PaymentProviderException("Unknown invoice " + providerInvoiceId, null));
    }
}

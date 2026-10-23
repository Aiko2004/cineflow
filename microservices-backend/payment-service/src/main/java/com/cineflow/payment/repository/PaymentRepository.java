package com.cineflow.payment.repository;

import com.cineflow.payment.model.Payment;
import com.cineflow.payment.model.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    // Идемпотентность создания счёта.
    Optional<Payment> findByBookingId(UUID bookingId);

    // Владелец проверяется в самом запросе: чужой платёж просто не найдётся → 404 (не 403).
    Optional<Payment> findByIdAndUserId(UUID id, UUID userId);

    Optional<Payment> findByProviderInvoiceId(String providerInvoiceId);

    // Сверка: PENDING, у которых истёк срок счёта, — webhook так и не пришёл.
    // Native-запрос со статусом ЛИТЕРАЛОМ, а не параметром: частичный индекс
    // idx_payments_pending_expires (V3) применим, только если планировщик видит в запросе то
    // же условие status = 'PENDING'. С параметром (:status) при generic-плане prepared
    // statement Postgres этого доказать не может и сканирует таблицу.
    @Query(value = """
            SELECT * FROM payments
             WHERE status = 'PENDING' AND expires_at < :now
             ORDER BY expires_at
             LIMIT :limit
            """, nativeQuery = true)
    List<Payment> findExpiredPending(@Param("now") OffsetDateTime now, @Param("limit") int limit);

    // Повтор возвратов: REFUND_PENDING, не менявшиеся с :before (литерал статуса — по той же
    // причине, индекс idx_payments_refund_pending).
    @Query(value = """
            SELECT * FROM payments
             WHERE status = 'REFUND_PENDING' AND updated_at < :before
             ORDER BY updated_at
             LIMIT :limit
            """, nativeQuery = true)
    List<Payment> findStaleRefundPending(@Param("before") OffsetDateTime before, @Param("limit") int limit);

    // Привязка счёта провайдера к платежу. Условие IS NULL: если параллельный повтор уже
    // привязал (провайдер идемпотентен по paymentId, значение то же) — 0 строк, не ошибка.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Payment p
               SET p.providerInvoiceId = :providerInvoiceId,
                   p.updatedAt = :now
             WHERE p.id = :id
               AND p.providerInvoiceId IS NULL
            """)
    int attachInvoice(@Param("id") UUID id,
                      @Param("providerInvoiceId") String providerInvoiceId,
                      @Param("now") OffsetDateTime now);

    // Единственный способ сменить статус: условный UPDATE (WHERE status = from).
    // 0 строк = статус уже сменился (повторный webhook, гонка отмены и оплаты) — вызывающий
    // не создаёт событие. В отличие от «прочитать, проверить, записать», два параллельных
    // перехода из одного состояния не пройдут оба: второй увидит новый статус и получит 0.
    //
    // Допустимость пары (from → to) проверяет PaymentStatus.canTransitionTo в сервисе —
    // сам запрос лишь атомарно применяет переход. paid_at / refunded_at ставятся вместе
    // со статусом, чтобы не было промежутка «PAID без времени оплаты».
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Payment p
               SET p.status = :to,
                   p.updatedAt = :now,
                   p.paidAt = CASE WHEN :to = com.cineflow.payment.model.PaymentStatus.PAID
                                   THEN :now ELSE p.paidAt END,
                   p.refundedAt = CASE WHEN :to = com.cineflow.payment.model.PaymentStatus.REFUNDED
                                       THEN :now ELSE p.refundedAt END
             WHERE p.id = :id
               AND p.status = :from
            """)
    int transition(@Param("id") UUID id,
                   @Param("from") PaymentStatus from,
                   @Param("to") PaymentStatus to,
                   @Param("now") OffsetDateTime now);
}

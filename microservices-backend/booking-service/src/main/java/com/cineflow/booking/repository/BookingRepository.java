package com.cineflow.booking.repository;

import com.cineflow.booking.model.Booking;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface BookingRepository extends JpaRepository<Booking, UUID> {

    // Идемпотентность: повтор POST с тем же ключом возвращает эту же бронь.
    Optional<Booking> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);

    // Владелец проверяется в самом запросе: чужая бронь просто не найдётся → 404 (не 403).
    Optional<Booking> findByIdAndUserId(UUID id, UUID userId);

    // Версии с fetch-join мест — для ответов, строящихся вне открытой транзакции
    // (getById, идемпотентный повтор): иначе ленивый seats дал бы LazyInitializationException.
    @Query("select b from Booking b left join fetch b.seats where b.id = :id and b.userId = :userId")
    Optional<Booking> findWithSeatsByIdAndUserId(@Param("id") UUID id, @Param("userId") UUID userId);

    @Query("select b from Booking b left join fetch b.seats "
            + "where b.userId = :userId and b.idempotencyKey = :key")
    Optional<Booking> findWithSeatsByUserIdAndIdempotencyKey(@Param("userId") UUID userId,
                                                             @Param("key") String key);

    // GET /bookings/@me — Slice: счётчик всех броней пользователю не нужен.
    Slice<Booking> findByUserId(UUID userId, Pageable pageable);

    // Переход PENDING → CONFIRMED условным UPDATE (BOOKING_DESIGN.md §3).
    // Ноль строк — статус уже не PENDING (отменён/истёк/повторный запрос) или удержание
    // прошло: подтверждать нечего. holdExpiresAt в условии — доступность учитывает срок,
    // а не только статус.
    // clearAutomatically: после bulk-update сбрасываем контекст, чтобы повторное чтение
    // брони вернуло новый статус/qr, а не устаревший управляемый экземпляр.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Booking b
               SET b.status = com.cineflow.booking.model.BookingStatus.CONFIRMED,
                   b.qrCode = :qrCode,
                   b.updatedAt = :now
             WHERE b.id = :id
               AND b.status = com.cineflow.booking.model.BookingStatus.PENDING
               AND b.holdExpiresAt > :now
            """)
    int confirm(@Param("id") UUID id, @Param("qrCode") String qrCode, @Param("now") OffsetDateTime now);

    // Отмена из PENDING или CONFIRMED. Из терминальных (CANCELLED/EXPIRED) — 0 строк.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Booking b
               SET b.status = com.cineflow.booking.model.BookingStatus.CANCELLED,
                   b.updatedAt = :now
             WHERE b.id = :id
               AND b.status IN (com.cineflow.booking.model.BookingStatus.PENDING,
                                com.cineflow.booking.model.BookingStatus.CONFIRMED)
            """)
    int cancel(@Param("id") UUID id, @Param("now") OffsetDateTime now);

    // Уборщик: все просроченные PENDING → EXPIRED (места освобождаются отдельным запросом).
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Booking b
               SET b.status = com.cineflow.booking.model.BookingStatus.EXPIRED,
                   b.updatedAt = :now
             WHERE b.status = com.cineflow.booking.model.BookingStatus.PENDING
               AND b.holdExpiresAt < :now
            """)
    int expireOverdue(@Param("now") OffsetDateTime now);

    // То же, но только для одного сеанса — вызывается перед вставкой новой брони,
    // чтобы просроченные удержания на нужные места не блокировали uk_seat_taken.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Booking b
               SET b.status = com.cineflow.booking.model.BookingStatus.EXPIRED,
                   b.updatedAt = :now
             WHERE b.screeningId = :screeningId
               AND b.status = com.cineflow.booking.model.BookingStatus.PENDING
               AND b.holdExpiresAt < :now
            """)
    int expireForScreening(@Param("screeningId") UUID screeningId, @Param("now") OffsetDateTime now);
}

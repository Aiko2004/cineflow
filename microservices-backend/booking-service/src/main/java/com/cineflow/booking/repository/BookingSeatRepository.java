package com.cineflow.booking.repository;

import com.cineflow.booking.model.BookingSeat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.UUID;

public interface BookingSeatRepository extends JpaRepository<BookingSeat, UUID> {

    // Освобождение мест конкретной брони (при отмене): released_at → место снова продаётся.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE BookingSeat s
               SET s.releasedAt = :now
             WHERE s.booking.id = :bookingId
               AND s.releasedAt IS NULL
            """)
    int releaseByBooking(@Param("bookingId") UUID bookingId, @Param("now") OffsetDateTime now);

    // Освобождение мест всех истёкших броней (уборщик). released_at IS NULL делает
    // запрос идемпотентным: уже освобождённые не трогаются.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE BookingSeat s
               SET s.releasedAt = :now
             WHERE s.releasedAt IS NULL
               AND s.booking.id IN (
                   SELECT b.id FROM Booking b
                    WHERE b.status = com.cineflow.booking.model.BookingStatus.EXPIRED)
            """)
    int releaseForExpiredBookings(@Param("now") OffsetDateTime now);

    // То же, но по одному сеансу — вместе с expireForScreening перед вставкой брони.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE BookingSeat s
               SET s.releasedAt = :now
             WHERE s.screeningId = :screeningId
               AND s.releasedAt IS NULL
               AND s.booking.id IN (
                   SELECT b.id FROM Booking b
                    WHERE b.screeningId = :screeningId
                      AND b.status = com.cineflow.booking.model.BookingStatus.EXPIRED)
            """)
    int releaseForExpiredByScreening(@Param("screeningId") UUID screeningId, @Param("now") OffsetDateTime now);

    // Для тестов/проверок: занято ли место активной записью.
    boolean existsByScreeningIdAndSeatIdAndReleasedAtIsNull(UUID screeningId, Long seatId);
}

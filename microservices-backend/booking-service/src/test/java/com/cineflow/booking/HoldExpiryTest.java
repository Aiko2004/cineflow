package com.cineflow.booking;

import com.cineflow.booking.dto.BookingResponse;
import com.cineflow.booking.dto.CreateBookingRequest;
import com.cineflow.booking.model.Booking;
import com.cineflow.booking.model.BookingStatus;
import com.cineflow.booking.service.HoldSweeper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Истечение удержания: брошенная PENDING-бронь переходит в EXPIRED и освобождает место.
class HoldExpiryTest extends AbstractBookingIntegrationTest {

    @Autowired HoldSweeper holdSweeper;

    private final UUID userA = UUID.randomUUID();
    private final UUID userB = UUID.randomUUID();

    private CreateBookingRequest req(long seatId) {
        return new CreateBookingRequest(SCREENING, List.of(seatId));
    }

    private void expireHold(UUID bookingId) {
        jdbcTemplate.update("UPDATE bookings SET hold_expires_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(60)), bookingId);
    }

    @Test
    void sweeper_expiresOverduePending_andReleasesSeat() {
        BookingResponse b1 = bookingService.create(userA, req(100L), null);
        UUID id = UUID.fromString(b1.id());
        expireHold(id);

        // До уборки место ещё числится занятым (released_at пуст).
        assertThat(bookingSeatRepository
                .existsByScreeningIdAndSeatIdAndReleasedAtIsNull(SCREENING, 100L)).isTrue();

        holdSweeper.sweep();

        Booking reloaded = bookingRepository.findById(id).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(BookingStatus.EXPIRED);
        assertThat(bookingSeatRepository
                .existsByScreeningIdAndSeatIdAndReleasedAtIsNull(SCREENING, 100L)).isFalse();

        // Освобождённое место продаётся снова.
        BookingResponse b2 = bookingService.create(userB, req(100L), null);
        assertThat(b2.status()).isEqualTo("PENDING");
    }

    @Test
    void availabilityChecksHoldExpiry_evenBeforeSweeper() {
        // Проверка доступности учитывает hold_expires_at, а не только существование записи
        // (BOOKING_DESIGN.md §4): просроченное удержание не блокирует новую бронь даже до уборщика.
        BookingResponse b1 = bookingService.create(userA, req(100L), null);
        UUID id = UUID.fromString(b1.id());
        expireHold(id);

        // Уборщик НЕ вызывается — но бронирование того же места проходит,
        // потому что persist() освобождает просроченные удержания перед вставкой.
        BookingResponse b2 = bookingService.create(userB, req(100L), null);
        assertThat(b2.status()).isEqualTo("PENDING");

        assertThat(bookingRepository.findById(id).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.EXPIRED);
    }
}

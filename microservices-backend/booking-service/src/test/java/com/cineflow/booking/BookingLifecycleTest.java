package com.cineflow.booking;

import com.cineflow.booking.dto.BookingResponse;
import com.cineflow.booking.dto.CreateBookingRequest;
import com.cineflow.booking.exception.BookingNotFoundException;
import com.cineflow.booking.exception.InvalidStatusTransitionException;
import com.cineflow.booking.exception.SeatTakenException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Жизненный цикл брони: создание, подтверждение, отмена+переоткрытие места,
// запрет подтверждения отменённой, недоступность чужой (404), идемпотентность.
class BookingLifecycleTest extends AbstractBookingIntegrationTest {

    private final UUID userA = UUID.randomUUID();
    private final UUID userB = UUID.randomUUID();

    private CreateBookingRequest req(long seatId) {
        return new CreateBookingRequest(SCREENING, List.of(seatId));
    }

    @Test
    void create_thenConfirm_setsStatusAndQr() {
        BookingResponse created = bookingService.create(userA, req(100L), null);
        assertThat(created.status()).isEqualTo("PENDING");
        assertThat(created.seats()).hasSize(1);
        assertThat(created.totalPrice()).isEqualByComparingTo("200.00");
        assertThat(created.qrCode()).isNull();

        BookingResponse confirmed = bookingService.confirm(userA, UUID.fromString(created.id()));
        assertThat(confirmed.status()).isEqualTo("CONFIRMED");
        assertThat(confirmed.qrCode()).isNotBlank();
    }

    @Test
    void cancel_releasesSeat_soItCanBeBookedAgain() {
        BookingResponse b1 = bookingService.create(userA, req(100L), null);
        assertThat(bookingSeatRepository
                .existsByScreeningIdAndSeatIdAndReleasedAtIsNull(SCREENING, 100L)).isTrue();

        // Пока бронь активна — то же место занято.
        assertThatThrownBy(() -> bookingService.create(userB, req(100L), null))
                .isInstanceOf(SeatTakenException.class);

        bookingService.cancel(userA, UUID.fromString(b1.id()));
        assertThat(bookingSeatRepository
                .existsByScreeningIdAndSeatIdAndReleasedAtIsNull(SCREENING, 100L)).isFalse();

        // После отмены место продаётся снова.
        BookingResponse b2 = bookingService.create(userB, req(100L), null);
        assertThat(b2.status()).isEqualTo("PENDING");
    }

    @Test
    void confirm_cancelledBooking_fails() {
        BookingResponse b1 = bookingService.create(userA, req(100L), null);
        bookingService.cancel(userA, UUID.fromString(b1.id()));

        assertThatThrownBy(() -> bookingService.confirm(userA, UUID.fromString(b1.id())))
                .isInstanceOf(InvalidStatusTransitionException.class);
    }

    @Test
    void foreignBooking_isNotAccessible_404() {
        BookingResponse b1 = bookingService.create(userA, req(100L), null);
        UUID id = UUID.fromString(b1.id());

        assertThatThrownBy(() -> bookingService.getById(userB, id))
                .isInstanceOf(BookingNotFoundException.class);
        assertThatThrownBy(() -> bookingService.confirm(userB, id))
                .isInstanceOf(BookingNotFoundException.class);
    }

    @Test
    void sameIdempotencyKey_returnsSameBooking_noSecondRow() {
        BookingResponse b1 = bookingService.create(userA, req(100L), "key-1");
        BookingResponse b2 = bookingService.create(userA, req(100L), "key-1");

        assertThat(b2.id()).isEqualTo(b1.id());
        assertThat(bookingRepository.count()).isEqualTo(1);
    }
}

package com.cineflow.booking.service;

import com.cineflow.booking.model.Booking;
import com.cineflow.booking.repository.BookingRepository;
import com.cineflow.booking.repository.BookingSeatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

// Транзакционная запись брони. Вынесена в ОТДЕЛЬНЫЙ бин намеренно: BookingService делает
// внешние вызовы (gRPC/Feign) ДО транзакции, а @Transactional на методе того же класса,
// вызванном изнутри, не сработал бы (self-invocation минует прокси). Вызов сюда идёт через
// бин-прокси → транзакция реально открывается, и держится только на время вставки в БД.
@Service
@RequiredArgsConstructor
public class BookingPersistence {

    private final BookingRepository bookingRepository;
    private final BookingSeatRepository bookingSeatRepository;

    @Transactional
    public Booking persist(Booking booking) {
        UUID screeningId = booking.getScreeningId();
        OffsetDateTime now = OffsetDateTime.now();

        // Доступность места учитывает hold_expires_at (BOOKING_DESIGN.md §4): перед вставкой
        // освобождаем просроченные, но ещё не выметенные PENDING-удержания на этот сеанс —
        // иначе они заняли бы uk_seat_taken, хотя фактически уже истекли.
        bookingRepository.expireForScreening(screeningId, now);
        bookingSeatRepository.releaseForExpiredByScreening(screeningId, now);

        // saveAndFlush: INSERT брони и мест происходит здесь → нарушение uk_seat_taken
        // (или uk_booking_idempotency) всплывёт как DataIntegrityViolationException сейчас,
        // а не при коммите, и его поймает вызывающий.
        return bookingRepository.saveAndFlush(booking);
    }
}

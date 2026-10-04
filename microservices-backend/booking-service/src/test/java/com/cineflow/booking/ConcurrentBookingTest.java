package com.cineflow.booking;

import com.cineflow.booking.dto.BookingResponse;
import com.cineflow.booking.dto.CreateBookingRequest;
import com.cineflow.booking.exception.SeatTakenException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

// Гонка: два параллельных бронирования ОДНОГО места. Ровно одно проходит, второе — 409.
// Гарантию даёт частичный уникальный индекс uk_seat_taken, а не проверка в коде.
//
// Чтобы увидеть, что тест ловит проблему, индекс можно временно убрать: раскомментировать
// dropSeatIndex() ниже — тогда оба бронирования пройдут (succeeded=2) и тест упадёт.
class ConcurrentBookingTest extends AbstractBookingIntegrationTest {

    private CreateBookingRequest req(long seatId) {
        return new CreateBookingRequest(SCREENING, List.of(seatId));
    }

    // Демонстрация «без индекса»: DROP INDEX uk_seat_taken перед гонкой.
    private void dropSeatIndex() {
        jdbcTemplate.execute("DROP INDEX IF EXISTS uk_seat_taken");
    }

    @Test
    void concurrentBooking_sameSeat_onlyOneSucceeds() throws Exception {
        // dropSeatIndex(); // ← раскомментировать, чтобы показать двойную продажу без индекса

        int threads = 2;
        long seatId = 100L;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads); // оба стартуют одновременно

        List<Future<BookingResponse>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Callable<BookingResponse> task = () -> {
                barrier.await();
                return bookingService.create(UUID.randomUUID(), req(seatId), null);
            };
            futures.add(pool.submit(task));
        }

        int succeeded = 0;
        int conflicts = 0;
        for (Future<BookingResponse> f : futures) {
            try {
                f.get(20, TimeUnit.SECONDS);
                succeeded++;
            } catch (ExecutionException e) {
                if (e.getCause() instanceof SeatTakenException) {
                    conflicts++;
                } else {
                    throw e;
                }
            }
        }
        pool.shutdownNow();

        assertThat(succeeded).as("успешным должно быть ровно одно бронирование").isEqualTo(1);
        assertThat(conflicts).as("второе должно получить 409 SeatTaken").isEqualTo(1);
        assertThat(bookingSeatRepository
                .existsByScreeningIdAndSeatIdAndReleasedAtIsNull(SCREENING, seatId)).isTrue();
    }
}

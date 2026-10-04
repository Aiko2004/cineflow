package com.cineflow.booking.service;

import com.cineflow.booking.repository.BookingRepository;
import com.cineflow.booking.repository.BookingSeatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

// Фоновая уборка брошенных PENDING-броней (BOOKING_DESIGN.md §4, §10).
// PENDING с прошедшим hold_expires_at → EXPIRED, их места получают released_at
// (освобождают частичный индекс). Интервал — из конфига (начать с минуты).
@Component
@Slf4j
@RequiredArgsConstructor
public class HoldSweeper {

    private final BookingRepository bookingRepository;
    private final BookingSeatRepository bookingSeatRepository;

    // initialDelay = interval: первый запуск через интервал, а не на старте контекста
    // (в тестах интервал большой — фоновый прогон не вмешивается в ручной вызов sweep()).
    @Scheduled(fixedDelayString = "${booking.sweeper.interval:60000}",
            initialDelayString = "${booking.sweeper.interval:60000}")
    @Transactional
    public void sweep() {
        OffsetDateTime now = OffsetDateTime.now();
        int expired = bookingRepository.expireOverdue(now);
        if (expired > 0) {
            int released = bookingSeatRepository.releaseForExpiredBookings(now);
            log.info("Hold sweeper: expired {} booking(s), released {} seat(s)", expired, released);
        }
    }
}

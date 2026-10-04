package com.cineflow.booking.dto;

import com.cineflow.contracts.SeatType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

// Форма по контракту фронтенда (BOOKING_DESIGN.md §7): снапшоты внутри, без обращения
// к соседним сервисам. qrCode заполнен только после подтверждения.
public record BookingResponse(
        String id,
        String status,
        LocalDate screeningDate,
        LocalTime screeningTime,
        BigDecimal totalPrice,
        MovieView movie,
        TheaterView theater,
        HallView hall,
        List<SeatView> seats,
        String qrCode
) {
    public record MovieView(String id, String title, String slug, String banner) {}
    public record TheaterView(String id, String name, String address) {}
    public record HallView(String id, String name) {}
    public record SeatView(Long seatId, Integer rowNumber, Integer seatNumber, SeatType seatType, BigDecimal price) {}
}

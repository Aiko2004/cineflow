package com.cineflow.booking.model;

import com.cineflow.contracts.SeatType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

// Одно место в брони. Активная запись (released_at IS NULL) занимает место —
// это она участвует в частичном уникальном индексе uk_seat_taken.
@Entity
@Table(name = "booking_seats")
@Getter
@Setter
@NoArgsConstructor
public class BookingSeat {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_id", nullable = false)
    private Booking booking;

    // Денормализация: screening_id обязан лежать в этой таблице, чтобы оба столбца
    // индекса uk_seat_taken (screening_id, seat_id) были здесь же.
    @Column(name = "screening_id", nullable = false)
    private UUID screeningId;

    @Column(name = "seat_id", nullable = false)
    private Long seatId;

    @Column(name = "row_number", nullable = false)
    private Integer rowNumber;

    @Column(name = "seat_number", nullable = false)
    private Integer seatNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "seat_type", nullable = false, length = 32)
    private SeatType seatType;

    @Column(nullable = false)
    private BigDecimal price;

    @Column(name = "released_at")
    private OffsetDateTime releasedAt;

    public BookingSeat(UUID id, UUID screeningId, Long seatId,
                       Integer rowNumber, Integer seatNumber, SeatType seatType, BigDecimal price) {
        this.id = id;
        this.screeningId = screeningId;
        this.seatId = seatId;
        this.rowNumber = rowNumber;
        this.seatNumber = seatNumber;
        this.seatType = seatType;
        this.price = price;
    }
}

package com.cineflow.booking.model;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.springframework.data.domain.Persistable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// Агрегат брони. Держит снапшоты (цена фиксируется на момент покупки) и список мест.
// Переходы статусов выполняются условным UPDATE в репозитории (атомарность), а допустимость
// перехода задаёт BookingStatus.
@Entity
@Table(name = "bookings")
@Getter
@Setter
@NoArgsConstructor
public class Booking implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "screening_id", nullable = false)
    private UUID screeningId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private BookingStatus status;

    @Column(name = "total_price", nullable = false)
    private BigDecimal totalPrice;

    @Column(name = "hold_expires_at", nullable = false)
    private OffsetDateTime holdExpiresAt;

    @Column(name = "qr_code")
    private String qrCode;

    @Column(name = "screening_date", nullable = false)
    private LocalDate screeningDate;

    @Column(name = "screening_time", nullable = false)
    private LocalTime screeningTime;

    @Embedded
    private MovieSnapshot movie;

    @Embedded
    private TheaterSnapshot theater;

    @Embedded
    private HallSnapshot hall;

    @Column(name = "idempotency_key", length = 64)
    private String idempotencyKey;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    // cascade = PERSIST: booking + его места вставляются одним saveAndFlush,
    // и нарушение uk_seat_taken всплывает как DataIntegrityViolationException в этот момент.
    // @BatchSize: при отдаче списка броней (/@me) места подгружаются одним IN-запросом,
    // а не N отдельными.
    @OneToMany(mappedBy = "booking", cascade = CascadeType.PERSIST)
    @BatchSize(size = 50)
    private List<BookingSeat> seats = new ArrayList<>();

    public void addSeat(BookingSeat seat) {
        seat.setBooking(this);
        seats.add(seat);
    }

    // id присваивается приложением (UUIDv7), поэтому Spring Data не может отличить новую
    // сущность от отсоединённой и по умолчанию делает merge() (лишний SELECT + поломка
    // каскада на коллекции). Persistable.isNew() возвращает true для свежесозданной →
    // repository.save() вызывает persist() (чистый INSERT + каскад мест). Флаг снимается
    // после первого persist/load.
    @Transient
    private boolean persisted = false;

    @Override
    public boolean isNew() {
        return !persisted;
    }

    @PostPersist
    @PostLoad
    void markPersisted() {
        this.persisted = true;
    }
}

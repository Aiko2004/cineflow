package com.cineflow.booking.service;

import com.cineflow.booking.client.ScreeningData;
import com.cineflow.booking.client.ScreeningGrpcClient;
import com.cineflow.booking.client.SeatCatalogClient;
import com.cineflow.booking.client.dto.SeatFeignResponse;
import com.cineflow.booking.config.BookingProperties;
import com.cineflow.booking.dto.BookingResponse;
import com.cineflow.booking.dto.CreateBookingRequest;
import com.cineflow.booking.dto.HoldResponse;
import com.cineflow.booking.dto.SliceResponse;
import com.cineflow.booking.exception.BadRequestException;
import com.cineflow.booking.exception.BookingNotFoundException;
import com.cineflow.booking.exception.InvalidStatusTransitionException;
import com.cineflow.booking.exception.SeatTakenException;
import com.cineflow.booking.model.Booking;
import com.cineflow.booking.model.BookingSeat;
import com.cineflow.booking.model.BookingStatus;
import com.cineflow.booking.model.HallSnapshot;
import com.cineflow.booking.model.MovieSnapshot;
import com.cineflow.booking.model.TheaterSnapshot;
import com.cineflow.booking.repository.BookingRepository;
import com.cineflow.booking.repository.BookingSeatRepository;
import com.cineflow.booking.util.QrTokenGenerator;
import com.github.f4b6a3.uuid.UuidCreator;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BookingService {

    // Имена из V1-миграции. В константах — чтобы поиск по коду находил связанные места.
    static final String SEAT_TAKEN_CONSTRAINT = "uk_seat_taken";
    static final String IDEMPOTENCY_CONSTRAINT = "uk_booking_idempotency";

    private final ScreeningGrpcClient screeningGrpcClient;
    private final SeatCatalogClient seatCatalogClient;
    private final BookingPersistence bookingPersistence;
    private final BookingRepository bookingRepository;
    private final BookingSeatRepository bookingSeatRepository;
    private final RedisHoldService redisHoldService;
    private final QrTokenGenerator qrTokenGenerator;
    private final BookingProperties properties;

    // ── Redis-удержания на этапе выбора мест ────────────────────────────────────

    public HoldResponse holdSeats(UUID userId, UUID screeningId, List<Long> seatIds) {
        return new HoldResponse(redisHoldService.hold(screeningId, dedupe(seatIds), userId));
    }

    public void releaseSeats(UUID userId, UUID screeningId, List<Long> seatIds) {
        redisHoldService.release(screeningId, dedupe(seatIds), userId);
    }

    // ── Создание брони ──────────────────────────────────────────────────────────
    //
    // НЕ @Transactional: внешние вызовы (gRPC/Feign) идут ДО открытия транзакции,
    // сама вставка — в BookingPersistence.persist (там транзакция). Держать соединение
    // с БД во время сетевых вызовов нельзя.
    public BookingResponse create(UUID userId, CreateBookingRequest request, String idempotencyKey) {
        List<Long> seatIds = dedupe(request.seatIds());
        if (seatIds.size() > properties.maxSeatsPerBooking()) {
            throw new BadRequestException("Too many seats: max " + properties.maxSeatsPerBooking());
        }

        // Идемпотентность: тот же ключ от того же пользователя → та же бронь, без второй вставки.
        if (idempotencyKey != null) {
            var existing = bookingRepository.findWithSeatsByUserIdAndIdempotencyKey(userId, idempotencyKey);
            if (existing.isPresent()) {
                return toResponse(existing.get());
            }
        }

        // Внешние вызовы ДО транзакции.
        ScreeningData screening = screeningGrpcClient.getScreening(request.screeningId().toString());

        Booking booking = buildBooking(userId, request.screeningId(), screening, idempotencyKey);

        BigDecimal total = BigDecimal.ZERO;
        for (Long seatId : seatIds) {
            SeatFeignResponse seat = seatCatalogClient.fetchSeat(seatId);
            if (!screening.hallId().equals(String.valueOf(seat.hallId()))) {
                throw new BadRequestException("Seat " + seatId + " does not belong to the screening hall");
            }
            BigDecimal price = screening.pricesByType().get(seat.type());
            if (price == null) {
                throw new BadRequestException("No price for seat type " + seat.type() + " in this screening");
            }
            booking.addSeat(new BookingSeat(
                    UuidCreator.getTimeOrderedEpoch(),
                    request.screeningId(),
                    seatId,
                    seat.rowNumber(),
                    seat.seatNumber(),
                    seat.type(),
                    price));
            total = total.add(price);
        }
        booking.setTotalPrice(total);

        try {
            return toResponse(bookingPersistence.persist(booking));
        } catch (DataIntegrityViolationException e) {
            if (isConstraint(e, SEAT_TAKEN_CONSTRAINT)) {
                throw new SeatTakenException();
            }
            if (isConstraint(e, IDEMPOTENCY_CONSTRAINT) && idempotencyKey != null) {
                // Гонка двух одинаковых запросов: второй проиграл на uk_booking_idempotency —
                // значит первый уже создал бронь. Возвращаем её (идемпотентность).
                return bookingRepository.findWithSeatsByUserIdAndIdempotencyKey(userId, idempotencyKey)
                        .map(this::toResponse)
                        .orElseThrow(() -> e);
            }
            throw e;
        } finally {
            // Бронь создана (или это была гонка) — снимаем свои Redis-удержания на эти места.
            redisHoldService.release(request.screeningId(), seatIds, userId);
        }
    }

    // ── Подтверждение ────────────────────────────────────────────────────────────

    @Transactional
    public BookingResponse confirm(UUID userId, UUID bookingId) {
        // Проверка владельца: чужая/несуществующая → 404.
        bookingRepository.findByIdAndUserId(bookingId, userId)
                .orElseThrow(() -> new BookingNotFoundException(bookingId));

        // Атомарный переход PENDING → CONFIRMED (+ проверка, что удержание не истекло).
        int updated = bookingRepository.confirm(bookingId, qrTokenGenerator.generate(), OffsetDateTime.now());
        if (updated == 0) {
            throw new InvalidStatusTransitionException(
                    "Booking cannot be confirmed: not pending or hold expired");
        }
        return toResponse(reload(bookingId));
    }

    // ── Отмена ────────────────────────────────────────────────────────────────────

    @Transactional
    public BookingResponse cancel(UUID userId, UUID bookingId) {
        bookingRepository.findByIdAndUserId(bookingId, userId)
                .orElseThrow(() -> new BookingNotFoundException(bookingId));

        int updated = bookingRepository.cancel(bookingId, OffsetDateTime.now());
        if (updated == 0) {
            throw new InvalidStatusTransitionException("Booking is already terminal");
        }
        // Освобождаем места этой брони — released_at, место снова продаётся.
        bookingSeatRepository.releaseByBooking(bookingId, OffsetDateTime.now());
        return toResponse(reload(bookingId));
    }

    // ── Чтение ────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public BookingResponse getById(UUID userId, UUID bookingId) {
        return bookingRepository.findWithSeatsByIdAndUserId(bookingId, userId)
                .map(this::toResponse)
                .orElseThrow(() -> new BookingNotFoundException(bookingId));
    }

    @Transactional(readOnly = true)
    public SliceResponse<BookingResponse> getMine(UUID userId, Pageable pageable) {
        return SliceResponse.from(
                bookingRepository.findByUserId(userId, pageable).map(this::toResponse));
    }

    // ── helpers ────────────────────────────────────────────────────────────────────

    private Booking reload(UUID id) {
        return bookingRepository.findById(id)
                .orElseThrow(() -> new BookingNotFoundException(id));
    }

    private Booking buildBooking(UUID userId, UUID screeningId, ScreeningData sc, String idempotencyKey) {
        Booking booking = new Booking();
        booking.setId(UuidCreator.getTimeOrderedEpoch());
        booking.setUserId(userId);
        booking.setScreeningId(screeningId);
        booking.setStatus(BookingStatus.PENDING);
        booking.setHoldExpiresAt(OffsetDateTime.now().plus(properties.bookingHoldTtl()));
        booking.setScreeningDate(sc.screeningDate());
        // Время сеанса из startAt в UTC — согласовано с тем, как screening считает дату (UTC).
        booking.setScreeningTime(LocalTime.ofInstant(sc.startAt(), ZoneOffset.UTC));
        booking.setMovie(new MovieSnapshot(sc.movieId(), sc.movieTitle(), sc.movieSlug(), sc.movieBanner()));
        booking.setTheater(new TheaterSnapshot(sc.theaterId(), sc.theaterName(), sc.theaterAddress()));
        booking.setHall(new HallSnapshot(sc.hallId(), sc.hallName()));
        booking.setIdempotencyKey(idempotencyKey);
        return booking;
    }

    // Убираем дубликаты, сохраняя порядок.
    private List<Long> dedupe(List<Long> seatIds) {
        return List.copyOf(new LinkedHashSet<>(seatIds));
    }

    static boolean isConstraint(DataIntegrityViolationException ex, String name) {
        return ex.getCause() instanceof ConstraintViolationException cve
                && name.equals(cve.getConstraintName());
    }

    private BookingResponse toResponse(Booking b) {
        List<BookingResponse.SeatView> seats = b.getSeats().stream()
                .map(s -> new BookingResponse.SeatView(
                        s.getSeatId(), s.getRowNumber(), s.getSeatNumber(), s.getSeatType(), s.getPrice()))
                .toList();
        return new BookingResponse(
                b.getId().toString(),
                b.getStatus().name(),
                b.getScreeningDate(),
                b.getScreeningTime(),
                b.getTotalPrice(),
                new BookingResponse.MovieView(
                        b.getMovie().getId(), b.getMovie().getTitle(),
                        b.getMovie().getSlug(), b.getMovie().getBanner()),
                new BookingResponse.TheaterView(
                        b.getTheater().getId(), b.getTheater().getName(), b.getTheater().getAddress()),
                new BookingResponse.HallView(
                        b.getHall().getId(), b.getHall().getName()),
                seats,
                b.getQrCode());
    }
}

package com.cineflow.booking.controller;

import com.cineflow.booking.dto.BookingResponse;
import com.cineflow.booking.dto.CreateBookingRequest;
import com.cineflow.booking.dto.HoldRequest;
import com.cineflow.booking.dto.HoldResponse;
import com.cineflow.booking.dto.SliceResponse;
import com.cineflow.booking.service.BookingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/bookings")
@RequiredArgsConstructor
public class BookingController {

    private final BookingService bookingService;

    // Мягкое удержание мест при выборе на схеме зала.
    @PostMapping("/seats/hold")
    public HoldResponse hold(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody HoldRequest request) {
        return bookingService.holdSeats(userId(jwt), request.screeningId(), request.seatIds());
    }

    @DeleteMapping("/seats/hold")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unhold(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody HoldRequest request) {
        bookingService.releaseSeats(userId(jwt), request.screeningId(), request.seatIds());
    }

    // Idempotency-Key — необязательный заголовок; повтор с тем же ключом вернёт ту же бронь.
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BookingResponse create(@AuthenticationPrincipal Jwt jwt,
                                  @Valid @RequestBody CreateBookingRequest request,
                                  @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return bookingService.create(userId(jwt), request, idempotencyKey);
    }

    @PostMapping("/{id}/confirm")
    public BookingResponse confirm(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return bookingService.confirm(userId(jwt), id);
    }

    @PostMapping("/{id}/cancel")
    public BookingResponse cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return bookingService.cancel(userId(jwt), id);
    }

    @GetMapping("/@me")
    public SliceResponse<BookingResponse> mine(
            @AuthenticationPrincipal Jwt jwt,
            @ParameterObject @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {
        return bookingService.getMine(userId(jwt), pageable);
    }

    @GetMapping("/{id}")
    public BookingResponse getById(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return bookingService.getById(userId(jwt), id);
    }

    // user_id — ТОЛЬКО из claim sub токена, никогда из тела запроса (BOOKING_DESIGN.md §7).
    private UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}

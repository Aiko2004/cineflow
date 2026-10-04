package com.cineflow.theater.controller;

import com.cineflow.theater.dto.page.PageResponse;
import com.cineflow.theater.dto.seat.CreateSeatRequest;
import com.cineflow.theater.dto.seat.SeatResponse;
import com.cineflow.theater.model.Seat;
import com.cineflow.theater.service.SeatService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/seats")
@RequiredArgsConstructor
public class SeatController {

    private final SeatService seatService;

    @PostMapping
    public SeatResponse create(@Valid @RequestBody CreateSeatRequest request) {
        return seatService.create(request);
    }

    @GetMapping("/{id}")
    public SeatResponse getById(@PathVariable Long id) {
        return seatService.getById(id);
    }

    @GetMapping("/hall/{hallId}")
    public PageResponse<SeatResponse> getByHallId(
            @PathVariable Long hallId,
            @ParameterObject @PageableDefault(size = 50, sort = "id") Pageable pageable
    ) {
        return seatService.getByHallId(hallId, pageable);
    }
}

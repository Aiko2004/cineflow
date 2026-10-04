package com.cineflow.screening.controller;

import com.cineflow.screening.dto.page.CursorPageResponse;
import com.cineflow.screening.dto.page.SliceResponse;
import com.cineflow.screening.dto.screening.CreateScreeningRequest;
import com.cineflow.screening.dto.screening.ScreeningResponse;
import com.cineflow.screening.service.ScreeningService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/screenings")
@RequiredArgsConstructor
@Validated
public class ScreeningController {

    private final ScreeningService screeningService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ScreeningResponse create(@Valid @RequestBody CreateScreeningRequest request) {
        return screeningService.create(request);
    }

    @GetMapping("/{id}")
    public ScreeningResponse getById(@PathVariable String id) {
        return screeningService.getById(id);
    }

    // Два сценария через один эндпоинт:
    //   GET /api/screenings?theaterId=X&date=2026-09-20  → расписание кинотеатра на дату
    //   GET /api/screenings?movieId=X                    → все сеансы фильма
    // Оба возвращают Slice (без count).
    @GetMapping
    public SliceResponse<ScreeningResponse> query(
            @RequestParam(required = false) String theaterId,
            @RequestParam(required = false) String date,
            @RequestParam(required = false) String movieId,
            @ParameterObject @PageableDefault(size = 20, sort = "startAt") Pageable pageable
    ) {
        if (theaterId != null && date != null) {
            return screeningService.getByTheaterAndDate(
                    theaterId, LocalDate.parse(date), pageable);
        }
        if (movieId != null) {
            return screeningService.getByMovie(movieId, pageable);
        }
        return new SliceResponse<>(List.of(), pageable.getPageNumber(), pageable.getPageSize(), true);
    }

    // Keyset-пагинация для бесконечной прокрутки (infinite scroll на фронте).
    // cursor — непрозрачный токен из предыдущего ответа (nextCursor).
    // Первый запрос делается без cursor. Если nextCursor в ответе null — страниц больше нет.
    //
    // Почему отдельный эндпоинт, а не ?cursor=... в общем query:
    //   Scroll и обычный Slice — разные контракты ответа (CursorPageResponse vs SliceResponse).
    //   Смешивать их в одном методе значит либо одному из форматов делать исключение,
    //   либо возвращать generic Object — оба варианта хуже.
    @GetMapping("/scroll")
    public CursorPageResponse<ScreeningResponse> scroll(
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return screeningService.scroll(cursor, size);
    }
}

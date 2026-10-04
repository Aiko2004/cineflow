package com.cineflow.theater.controller;

import com.cineflow.theater.dto.page.PageResponse;
import com.cineflow.theater.dto.theater.CreateTheaterRequest;
import com.cineflow.theater.dto.theater.TheaterResponse;
import com.cineflow.theater.service.TheaterService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/theaters")
@RequiredArgsConstructor
public class TheaterController {

    private final TheaterService theaterService;

    @PostMapping
    public TheaterResponse create(@Valid @RequestBody CreateTheaterRequest request) {
        return theaterService.create(request);
    }

    @GetMapping("/{id}")
    public TheaterResponse getById(@PathVariable Long id) {
        return theaterService.getById(id);
    }

    @GetMapping
    public PageResponse<TheaterResponse> getAll(
            @ParameterObject @PageableDefault(size = 20, sort = "id") Pageable pageable
    ) {
        return theaterService.getAll(pageable);
    }
}

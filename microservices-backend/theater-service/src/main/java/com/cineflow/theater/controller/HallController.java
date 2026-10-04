package com.cineflow.theater.controller;

import com.cineflow.theater.dto.hall.CreateHallRequest;
import com.cineflow.theater.dto.hall.HallResponse;
import com.cineflow.theater.dto.page.PageResponse;
import com.cineflow.theater.service.HallService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/halls")
@RequiredArgsConstructor
public class HallController {

    private final HallService hallService;

    @PostMapping
    public HallResponse create(@Valid @RequestBody CreateHallRequest request) {
        return hallService.create(request);
    }

    @GetMapping("/{id}")
    public HallResponse getById(@PathVariable Long id) {
        return hallService.getById(id);
    }

    @GetMapping("/theater/{theaterId}")
    public PageResponse<HallResponse> getByTheaterId(
            @PathVariable Long theaterId,
            @ParameterObject @PageableDefault(size = 20, sort = "id") Pageable pageable
    ) {
        return hallService.getByTheaterId(theaterId, pageable);
    }
}

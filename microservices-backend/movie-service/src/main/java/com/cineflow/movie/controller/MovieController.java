package com.cineflow.movie.controller;

import com.cineflow.movie.dto.movie.CreateMovieRequest;
import com.cineflow.movie.dto.movie.MovieResponse;
import com.cineflow.movie.dto.page.PageResponse;
import com.cineflow.movie.service.MovieService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/movies")
@RequiredArgsConstructor
public class MovieController {

    private final MovieService movieService;

    // Параметры: page (0-based), size (max 100 — конфигурируется в application.yaml),
    // sort (например "title,asc"). @ParameterObject раскрывает Pageable в OpenAPI-доках
    // как отдельные query-параметры (не как один объект).
    @GetMapping
    public PageResponse<MovieResponse> getAll(
            @ParameterObject @PageableDefault(size = 20, sort = "id") Pageable pageable
    ) {
        return movieService.getAll(pageable);
    }

    @GetMapping("/{id}")
    public MovieResponse getById(@PathVariable Long id) {
        return movieService.getById(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MovieResponse create(@Valid @RequestBody CreateMovieRequest request) {
        return movieService.create(request);
    }
}

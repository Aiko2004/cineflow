package com.cineflow.movie.dto.movie;

import java.time.LocalDate;
import java.util.List;

// Соответствует GetAllMoviesResponse из контракта фронтенда.
public record MovieResponse(
        Long id,
        String title,
        String slug,
        String description,
        String poster,
        String banner,
        String ratingAge,
        String trailer,
        LocalDate releaseDate,
        List<CategoryView> categories
) {
    public record CategoryView(Long id, String name) {
    }
}

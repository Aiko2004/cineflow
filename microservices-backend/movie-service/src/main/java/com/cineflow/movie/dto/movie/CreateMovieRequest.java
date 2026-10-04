package com.cineflow.movie.dto.movie;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.Set;

public record CreateMovieRequest(
        @NotBlank String title,
        String slug,
        @Size(max = 1500) String description,
        String poster,
        String banner,
        String ratingAge,
        String trailer,
        LocalDate releaseDate,
        Set<Long> categoryIds
) {
}

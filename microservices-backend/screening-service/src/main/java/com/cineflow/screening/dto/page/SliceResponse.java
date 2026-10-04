package com.cineflow.screening.dto.page;

import org.springframework.data.domain.Slice;

import java.util.List;

// Конверт пагинации без totalElements (Slice-based).
// Используется там, где клиенту нужно только «есть ли ещё страницы»,
// а не полное количество — count-запрос на больших коллекциях дорог и не нужен.
// Форма зафиксирована в CLAUDE.md.
public record SliceResponse<T>(
        List<T> content,
        int page,
        int size,
        boolean last
) {
    public static <T> SliceResponse<T> from(Slice<T> slice) {
        return new SliceResponse<>(
                slice.getContent(),
                slice.getNumber(),
                slice.getSize(),
                slice.isLast()
        );
    }
}
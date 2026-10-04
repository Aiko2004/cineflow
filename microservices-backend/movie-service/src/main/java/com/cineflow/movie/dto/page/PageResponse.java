package com.cineflow.movie.dto.page;

import org.springframework.data.domain.Page;

import java.util.List;

// Стандартный конверт пагинации (Page-based, с totalElements).
// Используется для административных списков, где клиенту нужно знать общее число страниц.
// Форма зафиксирована в CLAUDE.md — все сервисы используют идентичную структуру.
public record PageResponse<T>(
        List<T> content,
        int page,           // 0-based, соответствует Spring Data convention
        int size,
        long totalElements,
        int totalPages,
        boolean last
) {
    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isLast()
        );
    }
}

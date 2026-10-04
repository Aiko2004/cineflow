package com.cineflow.theater.dto.page;

import org.springframework.data.domain.Page;

import java.util.List;

// Стандартный конверт пагинации (Page-based, с totalElements).
// Форма зафиксирована в CLAUDE.md — все сервисы используют идентичную структуру.
public record PageResponse<T>(
        List<T> content,
        int page,
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

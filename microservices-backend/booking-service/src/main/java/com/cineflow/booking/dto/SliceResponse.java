package com.cineflow.booking.dto;

import org.springframework.data.domain.Slice;

import java.util.List;

// Slice без totalElements — для /bookings/@me счётчик всех броней не нужен.
// Форма зафиксирована в CLAUDE.md (как в screening-service).
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
                slice.isLast());
    }
}

package com.cineflow.screening.dto.page;

import java.util.List;

// Конверт keyset-пагинации.
// Курсор кодирует позицию последнего элемента страницы — клиент передаёт его в следующем запросе.
// nextCursor = null означает последнюю страницу.
// Форма зафиксирована в CLAUDE.md.
public record CursorPageResponse<T>(
        List<T> content,
        int size,
        String nextCursor
) {}

package com.cineflow.screening.client.dto;

// Форма ответа theater-service GET /api/halls/{id}.
// theaterId нужен для проверки: зал должен принадлежать указанному кинотеатру.
public record HallFeignResponse(Long id, Long theaterId, String name) {}

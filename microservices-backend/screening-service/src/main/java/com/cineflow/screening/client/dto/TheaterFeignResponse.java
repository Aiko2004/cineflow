package com.cineflow.screening.client.dto;

// Форма ответа theater-service GET /api/theaters/{id}.
// city не нужен для снапшота, но десериализуется без ошибок — лишние поля Jackson игнорирует.
public record TheaterFeignResponse(Long id, String name, String address, String city) {}

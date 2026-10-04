package com.cineflow.screening.client.dto;

// Форма ответа movie-service GET /movies/{id} — только текущие поля.
// slug и banner пока отсутствуют в movie-service (технический долг);
// в снапшоте они будут null до обновления movie-service.
public record MovieFeignResponse(Long id, String title, String description, String posterUrl, String category) {}

package com.cineflow.screening.document;

// Anti-corruption layer: не переиспользуем DTO movie-service напрямую.
// Если movie-service изменит форму ответа, это не пробьётся в схему наших документов.
public record MovieSnapshot(String id, String title, String slug, String banner) {}

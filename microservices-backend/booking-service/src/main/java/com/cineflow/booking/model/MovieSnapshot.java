package com.cineflow.booking.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Снапшот фильма внутри брони: GET /bookings/@me отдаёт название без обращения
// к movie/screening. Явные имена колонок — чтобы три снапшота не конфликтовали полями.
@Embeddable
@Getter
@Setter
@NoArgsConstructor
public class MovieSnapshot {

    @Column(name = "movie_id", nullable = false, length = 64)
    private String id;

    @Column(name = "movie_title", nullable = false)
    private String title;

    @Column(name = "movie_slug")
    private String slug;

    @Column(name = "movie_banner")
    private String banner;

    public MovieSnapshot(String id, String title, String slug, String banner) {
        this.id = id;
        this.title = title;
        this.slug = slug;
        this.banner = banner;
    }
}

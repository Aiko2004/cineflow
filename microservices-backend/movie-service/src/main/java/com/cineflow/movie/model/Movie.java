package com.cineflow.movie.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.BatchSize;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "movies")
@Getter
@Setter
@NoArgsConstructor
public class Movie {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    private String slug;

    @Column(length = 1500)
    private String description;

    private String poster;
    private String banner;
    private String ratingAge;
    private String trailer;
    private LocalDate releaseDate;

    // LAZY — не загружаем категории, пока они не нужны.
    // @BatchSize: когда сервис обращается к categories у нескольких Movie в цикле,
    // Hibernate загружает их одним IN-запросом (не N отдельных запросов).
    @ManyToMany(fetch = FetchType.LAZY)
    @BatchSize(size = 50)
    @JoinTable(
            name = "movie_categories",
            joinColumns = @JoinColumn(name = "movie_id"),
            inverseJoinColumns = @JoinColumn(name = "category_id")
    )
    private Set<Category> categories = new HashSet<>();
}

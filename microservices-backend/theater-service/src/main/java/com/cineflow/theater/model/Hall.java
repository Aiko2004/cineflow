package com.cineflow.theater.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(
        name = "halls",
        indexes = {
                @Index(name = "idx_hall_theater_id", columnList = "theater_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
public class Hall {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "theater_id",
            nullable = false,
            foreignKey = @ForeignKey(name="fk_hall_theater")
    )
    private Theater theater;

    @Column(nullable = false)
    private String name;
}

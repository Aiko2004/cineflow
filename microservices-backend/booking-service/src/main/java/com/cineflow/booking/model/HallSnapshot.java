package com.cineflow.booking.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Embeddable
@Getter
@Setter
@NoArgsConstructor
public class HallSnapshot {

    @Column(name = "hall_id", nullable = false, length = 64)
    private String id;

    @Column(name = "hall_name", nullable = false)
    private String name;

    public HallSnapshot(String id, String name) {
        this.id = id;
        this.name = name;
    }
}

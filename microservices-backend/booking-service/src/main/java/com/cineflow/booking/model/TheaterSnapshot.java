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
public class TheaterSnapshot {

    @Column(name = "theater_id", nullable = false, length = 64)
    private String id;

    @Column(name = "theater_name", nullable = false)
    private String name;

    @Column(name = "theater_address", length = 512)
    private String address;

    public TheaterSnapshot(String id, String name, String address) {
        this.id = id;
        this.name = name;
        this.address = address;
    }
}

package com.cineflow.theater.repository;

import com.cineflow.theater.model.Seat;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SeatRepository extends JpaRepository<Seat, Long> {
    Page<Seat> findByHallId(Long hallId, Pageable pageable);
}

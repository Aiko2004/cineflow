package com.cineflow.theater.repository;

import com.cineflow.theater.model.Hall;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HallRepository extends JpaRepository<Hall, Long> {
    Page<Hall> findByTheaterId(Long theaterId, Pageable pageable);
}

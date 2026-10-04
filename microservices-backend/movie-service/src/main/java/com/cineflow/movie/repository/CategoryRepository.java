package com.cineflow.movie.repository;

import com.cineflow.movie.model.Category;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.Set;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    Set<Category> findAllByIdIn(Collection<Long> ids);
}

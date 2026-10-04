package com.cineflow.movie.exception;

import java.util.Collection;

public class CategoryNotFoundException extends ResourceNotFoundException {
    public CategoryNotFoundException(Collection<Long> ids) {
        super("Categories not found: " + ids);
    }
}

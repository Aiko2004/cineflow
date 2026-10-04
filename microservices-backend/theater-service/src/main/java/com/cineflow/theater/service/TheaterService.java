package com.cineflow.theater.service;

import com.cineflow.theater.dto.page.PageResponse;
import com.cineflow.theater.dto.theater.CreateTheaterRequest;
import com.cineflow.theater.dto.theater.TheaterResponse;
import com.cineflow.theater.exception.TheaterNotFoundException;
import com.cineflow.theater.model.Theater;
import com.cineflow.theater.repository.TheaterRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TheaterService {

    private final TheaterRepository theaterRepository;

    public TheaterResponse create(CreateTheaterRequest request) {
        Theater theater = new Theater();
        theater.setName(request.name());
        theater.setAddress(request.address());
        theater.setCity(request.city());
        return toResponse(theaterRepository.save(theater));
    }

    @Transactional(readOnly = true)
    public TheaterResponse getById(Long id) {
        return theaterRepository.findById(id)
                .map(this::toResponse)
                .orElseThrow(() -> new TheaterNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public PageResponse<TheaterResponse> getAll(Pageable pageable) {
        return PageResponse.from(theaterRepository.findAll(pageable).map(this::toResponse));
    }

    private TheaterResponse toResponse(Theater theater) {
        return new TheaterResponse(
                theater.getId(),
                theater.getName(),
                theater.getAddress(),
                theater.getCity()
        );
    }
}

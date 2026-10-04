package com.cineflow.theater.service;

import com.cineflow.theater.dto.hall.CreateHallRequest;
import com.cineflow.theater.dto.hall.HallResponse;
import com.cineflow.theater.dto.page.PageResponse;
import com.cineflow.theater.exception.HallNotFoundException;
import com.cineflow.theater.exception.TheaterNotFoundException;
import com.cineflow.theater.model.Hall;
import com.cineflow.theater.model.Theater;
import com.cineflow.theater.repository.HallRepository;
import com.cineflow.theater.repository.TheaterRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class HallService {

    private final HallRepository hallRepository;
    private final TheaterRepository theaterRepository;

    public HallResponse create(CreateHallRequest request) {
        Theater theater = theaterRepository.findById(request.theaterId())
                .orElseThrow(() -> new TheaterNotFoundException(request.theaterId()));
        Hall hall = new Hall();
        hall.setTheater(theater);
        hall.setName(request.name());
        return toResponse(hallRepository.save(hall));
    }

    @Transactional(readOnly = true)
    public HallResponse getById(Long id) {
        return hallRepository.findById(id)
                .map(this::toResponse)
                .orElseThrow(() -> new HallNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public PageResponse<HallResponse> getByTheaterId(Long theaterId, Pageable pageable) {
        if (!theaterRepository.existsById(theaterId)) {
            throw new TheaterNotFoundException(theaterId);
        }
        return PageResponse.from(
                hallRepository.findByTheaterId(theaterId, pageable).map(this::toResponse));
    }

    private HallResponse toResponse(Hall hall) {
        return new HallResponse(
                hall.getId(),
                hall.getTheater().getId(),
                hall.getName()
        );
    }
}

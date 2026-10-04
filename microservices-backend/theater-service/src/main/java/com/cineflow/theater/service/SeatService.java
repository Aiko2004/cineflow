package com.cineflow.theater.service;

import com.cineflow.theater.dto.page.PageResponse;
import com.cineflow.theater.dto.seat.CreateSeatRequest;
import com.cineflow.theater.dto.seat.SeatResponse;
import com.cineflow.theater.exception.HallNotFoundException;
import com.cineflow.theater.exception.SeatNotFoundException;
import com.cineflow.theater.model.Hall;
import com.cineflow.theater.model.Seat;
import com.cineflow.theater.repository.HallRepository;
import com.cineflow.theater.repository.SeatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SeatService {

    private final SeatRepository seatRepository;
    private final HallRepository hallRepository;

    public SeatResponse create(CreateSeatRequest request) {
        Hall hall = hallRepository.findById(request.hallId())
                .orElseThrow(() -> new HallNotFoundException(request.hallId()));
        Seat seat = new Seat();
        seat.setHall(hall);
        seat.setRowNumber(request.rowNumber());
        seat.setSeatNumber(request.seatNumber());
        seat.setType(request.type());
        return toResponse(seatRepository.save(seat));
    }

    @Transactional(readOnly = true)
    public SeatResponse getById(Long id) {
        return seatRepository.findById(id)
                .map(this::toResponse)
                .orElseThrow(() -> new SeatNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public PageResponse<SeatResponse> getByHallId(Long hallId, Pageable pageable) {
        if (!hallRepository.existsById(hallId)) {
            throw new HallNotFoundException(hallId);
        }
        return PageResponse.from(
                seatRepository.findByHallId(hallId, pageable).map(this::toResponse));
    }

    private SeatResponse toResponse(Seat seat) {
        return new SeatResponse(
                seat.getId(),
                seat.getHall().getId(),
                seat.getRowNumber(),
                seat.getSeatNumber(),
                seat.getType()
        );
    }
}

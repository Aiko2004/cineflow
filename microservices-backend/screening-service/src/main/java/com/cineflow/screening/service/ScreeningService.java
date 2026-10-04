package com.cineflow.screening.service;

import com.cineflow.screening.client.dto.HallFeignResponse;
import com.cineflow.screening.client.dto.MovieFeignResponse;
import com.cineflow.screening.client.dto.TheaterFeignResponse;
import com.cineflow.screening.document.HallSnapshot;
import com.cineflow.screening.document.MovieSnapshot;
import com.cineflow.screening.document.Screening;
import com.cineflow.screening.document.SeatTypePrice;
import com.cineflow.screening.document.TheaterSnapshot;
import com.cineflow.screening.dto.page.CursorPageResponse;
import com.cineflow.screening.dto.page.SliceResponse;
import com.cineflow.screening.dto.screening.CreateScreeningRequest;
import com.cineflow.screening.dto.screening.ScreeningResponse;
import com.cineflow.screening.exception.BadRequestException;
import com.cineflow.screening.exception.HallTheaterMismatchException;
import com.cineflow.screening.exception.InvalidTimeRangeException;
import com.cineflow.screening.exception.ScreeningConflictException;
import com.cineflow.screening.exception.ScreeningNotFoundException;
import com.cineflow.screening.repository.ScreeningRepository;
import com.github.f4b6a3.uuid.UuidCreator;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ScreeningService {

    private final ScreeningRepository screeningRepository;
    private final ScreeningEnrichmentService enrichmentService;

    // Сортировка keyset-страниц: (startAt ASC, id ASC).
    // "id" Spring Data MongoDB транслирует в "_id" при формировании MongoDB-запроса.
    private static final Sort SCROLL_SORT =
            Sort.by(Sort.Direction.ASC, "startAt", "id");

    public ScreeningResponse create(CreateScreeningRequest request) {
        if (!request.endAt().isAfter(request.startAt())) {
            throw new InvalidTimeRangeException();
        }
        MovieFeignResponse movie = enrichmentService.fetchMovie(request.movieId());
        HallFeignResponse hall = enrichmentService.fetchHall(request.hallId());
        TheaterFeignResponse theater = enrichmentService.fetchTheater(request.theaterId());

        if (!String.valueOf(hall.theaterId()).equals(request.theaterId())) {
            throw new HallTheaterMismatchException(request.hallId(), request.theaterId());
        }
        if (screeningRepository.existsByHallIdAndStartAtLessThanAndEndAtGreaterThan(
                request.hallId(), request.endAt(), request.startAt())) {
            throw new ScreeningConflictException(request.hallId());
        }

        Screening screening = new Screening();
        screening.setId(UuidCreator.getTimeOrderedEpoch().toString());
        screening.setMovieId(request.movieId());
        screening.setTheaterId(request.theaterId());
        screening.setHallId(request.hallId());
        screening.setStartAt(request.startAt());
        screening.setEndAt(request.endAt());
        screening.setScreeningDate(request.startAt().atZone(ZoneOffset.UTC).toLocalDate());
        screening.setMovie(new MovieSnapshot(
                String.valueOf(movie.id()), movie.title(), null, null));
        screening.setTheater(new TheaterSnapshot(
                String.valueOf(theater.id()), theater.name(), theater.address()));
        screening.setHall(new HallSnapshot(
                String.valueOf(hall.id()), hall.name()));
        screening.setSeatTypes(request.seatTypes().stream()
                .map(s -> new SeatTypePrice(s.type(), s.price()))
                .toList());

        return toResponse(screeningRepository.save(screening));
    }

    public ScreeningResponse getById(String id) {
        return screeningRepository.findById(id)
                .map(this::toResponse)
                .orElseThrow(() -> new ScreeningNotFoundException(id));
    }

    // Slice, не Page: count-запрос на расписании не нужен.
    //
    // Почему здесь Slice, а не Page:
    //   Page делает два запроса — данные + COUNT(*). Для расписания кинотеатра
    //   на конкретную дату клиенту нужно только «есть ли ещё страницы» (last=true/false),
    //   а не «сколько всего сеансов». COUNT на проиндексированной паре (theaterId, screeningDate)
    //   быстрый, но избыточный: мы платим за запрос, который никто не использует.
    public SliceResponse<ScreeningResponse> getByTheaterAndDate(
            String theaterId, LocalDate date, Pageable pageable) {
        return SliceResponse.from(
                screeningRepository
                        .findByTheaterIdAndScreeningDate(theaterId, date, pageable)
                        .map(this::toResponse));
    }

    public SliceResponse<ScreeningResponse> getByMovie(String movieId, Pageable pageable) {
        return SliceResponse.from(
                screeningRepository.findByMovieId(movieId, pageable).map(this::toResponse));
    }

    // Keyset-пагинация для бесконечной прокрутки (infinite scroll).
    //
    // Почему keyset, а не offset:
    //   Offset: SELECT … SKIP N LIMIT K. При вставке нового сеанса между страницами
    //   все последующие элементы смещаются — элементы либо дублируются, либо выпадают.
    //   Keyset: вместо «пропусти N строк» храним позицию последнего показанного элемента
    //   и запрашиваем «дай следующие после (startAt, id)».
    //   Вставка нового элемента до курсора — он невидим для текущей сессии (ОК для scroll).
    //   Вставка после курсора — он появится на следующей странице без пропусков.
    //
    // Overfetch-трюк: запрашиваем size+1. Если получили size+1 — есть следующая страница,
    // последний элемент превращается в курсор и отрезается от content.
    // Если получили ≤ size — это последняя страница, nextCursor = null.
    public CursorPageResponse<ScreeningResponse> scroll(String cursor, int size) {
        int fetch = size + 1;
        PageRequest page = PageRequest.of(0, fetch, SCROLL_SORT);

        List<Screening> results;
        if (cursor == null) {
            // Первая страница: просто берём первые fetch элементов из всей коллекции.
            // findAll(Pageable) делает лишний COUNT — принимаемый компромисс только для первого запроса.
            results = screeningRepository.findAll(page).getContent();
        } else {
            ScreeningCursor sc;
            try {
                sc = ScreeningCursor.decode(cursor);
            } catch (Exception e) {
                throw new BadRequestException("Invalid cursor");
            }
            results = screeningRepository.findAfterCursor(sc.startAt(), sc.id(), page);
        }

        boolean hasNext = results.size() > size;
        List<Screening> content = hasNext ? results.subList(0, size) : results;
        String nextCursor = hasNext
                ? ScreeningCursor.of(content.get(content.size() - 1)).encode()
                : null;

        return new CursorPageResponse<>(
                content.stream().map(this::toResponse).toList(),
                size,
                nextCursor);
    }

    private ScreeningResponse toResponse(Screening s) {
        return new ScreeningResponse(
                s.getId(),
                s.getStartAt(),
                s.getEndAt(),
                s.getHallId(),
                new ScreeningResponse.TheaterView(
                        s.getTheater().id(), s.getTheater().name(), s.getTheater().address()),
                new ScreeningResponse.HallView(
                        s.getHall().id(), s.getHall().name()),
                new ScreeningResponse.MovieView(
                        s.getMovie().id(), s.getMovie().title(),
                        s.getMovie().slug(), s.getMovie().banner()),
                s.getSeatTypes().stream()
                        .map(st -> new ScreeningResponse.SeatTypeView(st.type(), st.price()))
                        .toList()
        );
    }

    // Кодирует позицию элемента в непрозрачный URL-safe строку.
    // Формат: base64url(startAt + "|" + id).
    // "|" не встречается ни в ISO-8601 Instant, ни в UUID — безопасный разделитель.
    private record ScreeningCursor(Instant startAt, String id) {

        static ScreeningCursor of(Screening s) {
            return new ScreeningCursor(s.getStartAt(), s.getId());
        }

        String encode() {
            byte[] raw = (startAt.toString() + "|" + id)
                    .getBytes(StandardCharsets.UTF_8);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        }

        static ScreeningCursor decode(String encoded) {
            String raw = new String(
                    Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            int sep = raw.indexOf('|');
            if (sep < 0) throw new IllegalArgumentException("Bad cursor format");
            return new ScreeningCursor(
                    Instant.parse(raw.substring(0, sep)),
                    raw.substring(sep + 1));
        }
    }
}

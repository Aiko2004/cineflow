package com.cineflow.screening.repository;

import com.cineflow.screening.document.Screening;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public interface ScreeningRepository extends MongoRepository<Screening, String> {

    // Slice, не Page: count-запрос на расписании за день не нужен.
    // Клиенту достаточно знать «есть ли ещё» — это покрывает Slice.last().
    Slice<Screening> findByTheaterIdAndScreeningDate(
            String theaterId, LocalDate screeningDate, Pageable pageable);

    Slice<Screening> findByMovieId(String movieId, Pageable pageable);

    // Keyset-пагинация: условие строго после позиции (startAt, _id) курсора.
    //
    // Семантика: «дай мне все сеансы, которые идут после позиции (cursorStartAt, cursorId)».
    // startAt > cursorStartAt  — всё, что начинается позже
    // ИЛИ
    // startAt = cursorStartAt AND _id > cursorId  — в той же секунде, но с большим id
    //
    // Почему _id входит в условие: startAt — не уникальный ключ
    // (два сеанса в одном кинотеатре начинаются в одну минуту, разные залы).
    // Без _id порядок сеансов с одинаковым startAt недетерминирован,
    // и граница страницы «плавает» — элементы попадают в обе страницы или не попадают ни в одну.
    // _id (UUIDv7) — монотонно растущий и уникальный: сортировка (startAt, id) всегда однозначна.
    @Query("{ '$or': [ { 'startAt': { '$gt': ?0 } }," +
           "           { '$and': [ { 'startAt': ?0 }, { '_id': { '$gt': ?1 } } ] } ] }")
    List<Screening> findAfterCursor(Instant cursorStartAt, String cursorId, Pageable pageable);

    // Проверка пересечения интервалов [A,B] ∩ [C,D] ≠ ∅ ⟺ A < D AND B > C.
    boolean existsByHallIdAndStartAtLessThanAndEndAtGreaterThan(
            String hallId, Instant newEndAt, Instant newStartAt);
}

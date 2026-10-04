package com.cineflow.screening.document;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Document(collection = "screenings")
@CompoundIndexes({
        // главный read-путь: расписание кинотеатра на дату
        @CompoundIndex(name = "idx_theater_date", def = "{'theaterId': 1, 'screeningDate': 1}"),
        // для Kafka-события movie.updated — найти все сеансы фильма
        @CompoundIndex(name = "idx_movie", def = "{'movieId': 1}"),
        // проверка пересечения сеансов в зале при создании
        @CompoundIndex(name = "idx_hall_start", def = "{'hallId': 1, 'startAt': 1}")
})
@Getter
@Setter
@NoArgsConstructor
public class Screening {

    @Id
    private String id;

    private Instant startAt;
    private Instant endAt;

    // Денормализация: LocalDate выводится из startAt в сервисе.
    // Выделено как отдельное поле, чтобы индекс {theaterId, screeningDate}
    // работал без вычислений — MongoDB не умеет индексировать производные поля.
    private LocalDate screeningDate;

    // Плоские поля для индексов и Kafka-фильтрации
    private String movieId;
    private String theaterId;
    private String hallId;

    // Снапшоты — денормализованные копии данных на момент создания сеанса.
    // Позволяют отдать ScreeningResponse без синхронных вызовов к соседним сервисам.
    private MovieSnapshot movie;
    private TheaterSnapshot theater;
    private HallSnapshot hall;

    private List<SeatTypePrice> seatTypes;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}

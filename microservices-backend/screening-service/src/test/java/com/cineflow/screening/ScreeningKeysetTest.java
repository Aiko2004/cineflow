package com.cineflow.screening;

import com.cineflow.contracts.SeatType;
import com.cineflow.screening.document.HallSnapshot;
import com.cineflow.screening.document.MovieSnapshot;
import com.cineflow.screening.document.Screening;
import com.cineflow.screening.document.SeatTypePrice;
import com.cineflow.screening.document.TheaterSnapshot;
import com.cineflow.screening.dto.page.CursorPageResponse;
import com.cineflow.screening.dto.screening.ScreeningResponse;
import com.cineflow.screening.exception.BadRequestException;
import com.cineflow.screening.repository.ScreeningRepository;
import com.cineflow.screening.service.ScreeningEnrichmentService;
import com.cineflow.screening.service.ScreeningService;
import com.github.f4b6a3.uuid.UuidCreator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.mongodb.test.autoconfigure.DataMongoTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// @DataMongoTest загружает только MongoDB-слой: репозитории, MongoTemplate, конвертеры.
// @Import(ScreeningService.class) добавляет сервис без полного контекста приложения.
// @MockitoBean ScreeningEnrichmentService — в тестах документы вставляются напрямую
// через репозиторий, поэтому Feign-клиенты не нужны.
@DataMongoTest
@Testcontainers
@Import(ScreeningService.class)
@TestPropertySource(properties = "eureka.client.enabled=false")
class ScreeningKeysetTest {

    @Container
    @ServiceConnection
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7");

    @Autowired ScreeningService screeningService;
    @Autowired ScreeningRepository screeningRepository;
    @MockitoBean ScreeningEnrichmentService enrichmentService;

    @BeforeEach
    void clean() {
        screeningRepository.deleteAll();
    }

    // ── Базовые сценарии ──────────────────────────────────────────────────────

    @Test
    void emptyCollection_noCursor_returnsEmptyPage_notError() {
        // Пустой результат — это 200 с пустым content и nextCursor: null, не ошибка.
        var page = screeningService.scroll(null, 20);

        assertThat(page.content()).isEmpty();
        assertThat(page.nextCursor()).isNull();
        assertThat(page.size()).isEqualTo(20);
    }

    @Test
    void firstPage_noCursor_returnsFirstItems() {
        saveAt("10:00", "11:00", "12:00", "13:00", "14:00");

        var page = screeningService.scroll(null, 3);

        assertThat(page.content()).hasSize(3);
        assertThat(page.nextCursor()).isNotNull();
        assertStartTimes(page, "10:00", "11:00", "12:00");
    }

    @Test
    void secondPage_cursorFromFirstPage() {
        saveAt("10:00", "11:00", "12:00", "13:00", "14:00");

        var page1 = screeningService.scroll(null, 2);
        var page2 = screeningService.scroll(page1.nextCursor(), 2);

        assertStartTimes(page2, "12:00", "13:00");
        assertThat(page2.nextCursor()).isNotNull();
    }

    @Test
    void lastPage_nextCursorIsNull() {
        saveAt("10:00", "11:00", "12:00");

        var page1 = screeningService.scroll(null, 2);
        var page2 = screeningService.scroll(page1.nextCursor(), 2);

        assertThat(page2.content()).hasSize(1);
        assertThat(page2.nextCursor())
                .as("Последняя страница — nextCursor должен быть null")
                .isNull();
    }

    @Test
    void size1_allPagesAreSingleElement_noGapsNoDuplicates() {
        saveAt("10:00", "11:00", "12:00");

        var p1 = screeningService.scroll(null, 1);
        var p2 = screeningService.scroll(p1.nextCursor(), 1);
        var p3 = screeningService.scroll(p2.nextCursor(), 1);

        assertThat(p3.nextCursor()).isNull();
        assertThat(List.of(
                p1.content().get(0).id(),
                p2.content().get(0).id(),
                p3.content().get(0).id()
        )).doesNotHaveDuplicates();
    }

    // ── Стабильность при вставке между страницами ─────────────────────────────

    @Test
    void keysetStable_insertBeforeCursor_noDuplicate() {
        // Демонстрация ключевого преимущества keyset перед offset.
        //
        // Начальный порядок:     [10:00  11:00  12:00  13:00  14:00]
        // Страница 1 (size=2):   [10:00  11:00]    cursor = позиция 11:00
        //
        // Вставляем 10:30:
        //   Новый порядок:       [10:00  10:30  11:00  12:00  13:00  14:00]
        //   (UUIDv7 нового сеанса > UUIDv7 11:00, т.к. создан позже;
        //    но startAt=10:30 < startAt=11:00 → встаёт ПЕРЕД 11:00 в sort (startAt, id))
        //
        // OFFSET page 2 (SKIP 2):  [11:00  12:00]  ← 11:00 уже был на стр. 1, дубликат!
        //
        // KEYSET cursor > (11:00, id_11:00):
        //   10:30 — startAt=10:30 < 11:00 → не проходит условие
        //   12:00 — startAt=12:00 > 11:00 → проходит
        //   → [12:00  13:00]  ← нет дублей, нет пропусков ✓

        saveAt("10:00", "11:00", "12:00", "13:00", "14:00");

        var page1 = screeningService.scroll(null, 2);
        String idAt11 = page1.content().get(1).id();
        assertStartTimes(page1, "10:00", "11:00");

        // Вставляем между 10:00 и 11:00
        saveScreeningAt(dayInstant("10:30"));

        var page2 = screeningService.scroll(page1.nextCursor(), 2);

        // Keyset не должен дублировать 11:00
        assertThat(page2.content())
                .extracting(ScreeningResponse::id)
                .as("11:00 уже был на странице 1 — keyset не должен его повторить")
                .doesNotContain(idAt11);
        assertStartTimes(page2, "12:00", "13:00");
    }

    @Test
    void keysetStable_insertAfterCursor_appearsOnNextPage() {
        // Вставка ПОСЛЕ курсора: новый элемент появляется на следующей странице.
        //
        // Начальный порядок:    [10:00  11:00  12:00  13:00]
        // Страница 1 (size=2):  [10:00  11:00]   cursor = 11:00
        //
        // Вставляем 11:30:
        //   Новый порядок:      [10:00  11:00  11:30  12:00  13:00]
        //   (startAt=11:30 > startAt=11:00 → встаёт ПОСЛЕ 11:00 в sort)
        //
        // KEYSET cursor > (11:00, id_11:00):
        //   11:30 — startAt=11:30 > 11:00 → проходит
        //   → [11:30  12:00]  ← новый элемент виден, без пропусков ✓

        saveAt("10:00", "11:00", "12:00", "13:00");

        var page1 = screeningService.scroll(null, 2);
        assertStartTimes(page1, "10:00", "11:00");

        Screening inserted = saveScreeningAt(dayInstant("11:30"));

        var page2 = screeningService.scroll(page1.nextCursor(), 2);

        assertThat(page2.content())
                .extracting(ScreeningResponse::id)
                .as("Вставленный после курсора 11:30 должен появиться на стр. 2")
                .contains(inserted.getId());
        assertStartTimes(page2, "11:30", "12:00");
    }

    @Test
    void invalidCursor_throwsBadRequest() {
        assertThatThrownBy(() -> screeningService.scroll("notBase64!@#", 10))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void malformedCursor_noSeparator_throwsBadRequest() {
        // Base64-строка без разделителя "|" — декодируется, но парсинг курсора упадёт.
        String noSep = java.util.Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString("nodivider".getBytes());
        assertThatThrownBy(() -> screeningService.scroll(noSep, 10))
                .isInstanceOf(BadRequestException.class);
    }

    // ── Вспомогательные методы ────────────────────────────────────────────────

    private List<Screening> saveAt(String... times) {
        return Arrays.stream(times)
                .map(t -> saveScreeningAt(dayInstant(t)))
                .toList();
    }

    private Screening saveScreeningAt(Instant startAt) {
        Screening s = new Screening();
        s.setId(UuidCreator.getTimeOrderedEpoch().toString());
        s.setStartAt(startAt);
        s.setEndAt(startAt.plus(2, ChronoUnit.HOURS));
        s.setScreeningDate(startAt.atZone(ZoneOffset.UTC).toLocalDate());
        s.setMovieId("movie-1");
        s.setTheaterId("theater-1");
        s.setHallId("hall-1");
        s.setMovie(new MovieSnapshot("movie-1", "Test Movie", null, null));
        s.setTheater(new TheaterSnapshot("theater-1", "Test Theater", "Test City"));
        s.setHall(new HallSnapshot("hall-1", "Hall 1"));
        s.setSeatTypes(List.of(new SeatTypePrice(SeatType.NORMAL, BigDecimal.valueOf(500))));
        return screeningRepository.save(s);
    }

    private static Instant dayInstant(String time) {
        return Instant.parse("2026-09-20T" + time + ":00Z");
    }

    private void assertStartTimes(CursorPageResponse<ScreeningResponse> page, String... times) {
        assertThat(page.content())
                .extracting(r -> r.startAt().toString())
                .containsExactly(Arrays.stream(times)
                        .map(t -> "2026-09-20T" + t + ":00Z")
                        .toArray(String[]::new));
    }
}

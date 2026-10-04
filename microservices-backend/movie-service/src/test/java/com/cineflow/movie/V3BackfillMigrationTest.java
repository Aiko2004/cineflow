package com.cineflow.movie;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Тесты data-миграции V3: backfill slug из title.
//
// Почему не @DataJpaTest:
//   @DataJpaTest запускает все миграции сразу (V1+V2+V3).
//   Здесь нужно ОСТАНОВИТЬСЯ на V2, вставить тестовые данные (с NULL slug),
//   а потом применить V3 и проверить результат.
//   Для этого используем программный Flyway API: target("2") → insert → migrate().
//
// Контейнер static — запускается один раз для всего класса.
// @BeforeEach clean() — откатываем схему перед каждым тестом.
@Testcontainers
class V3BackfillMigrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private HikariDataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(postgres.getJdbcUrl());
        cfg.setUsername(postgres.getUsername());
        cfg.setPassword(postgres.getPassword());
        dataSource = new HikariDataSource(cfg);
        jdbc = new JdbcTemplate(dataSource);

        // Чистим схему перед каждым тестом, чтобы тесты не влияли друг на друга.
        flyway(null).clean();
    }

    @AfterEach
    void tearDown() {
        dataSource.close();
    }

    // ── Сценарий 1: пустая база — V1+V2+V3 проходят без ошибок ──────────────

    @Test
    void fresh_db_all_migrations_apply_cleanly() {
        flyway(null).migrate();

        Integer movieCount = jdbc.queryForObject("SELECT COUNT(*) FROM movies", Integer.class);
        assertThat(movieCount).isZero();

        // slug NOT NULL работает: вставка без slug должна упасть
        assertThatThrownBy(
                () -> jdbc.execute("INSERT INTO movies (title) VALUES ('No Slug')")
        ).isInstanceOf(Exception.class);
    }

    // ── Сценарий 2: база с данными — slug заполняется у всех строк ───────────

    @Test
    void v3_backfills_slug_for_all_null_rows() {
        flyway("2").migrate(); // останавливаемся на V2 — slug ещё nullable

        // Фильмы с NULL slug — «старые» строки до V2
        jdbc.execute("INSERT INTO movies (title, slug) VALUES ('Inception', NULL)");
        jdbc.execute("INSERT INTO movies (title, slug) VALUES ('The Dark Knight', NULL)");
        jdbc.execute("INSERT INTO movies (title, slug) VALUES ('Interstellar', NULL)");

        flyway(null).migrate(); // применяем V3

        List<Map<String, Object>> movies = jdbc.queryForList(
                "SELECT id, title, slug FROM movies ORDER BY id");

        assertThat(movies).allMatch(m -> m.get("slug") != null);
        assertThat(slugOf(movies, "Inception")).isEqualTo("inception");
        assertThat(slugOf(movies, "The Dark Knight")).isEqualTo("the-dark-knight");
        assertThat(slugOf(movies, "Interstellar")).isEqualTo("interstellar");
    }

    // ── Сценарий 3: коллизия в batch — оба дублирующих title получают -id суффикс

    @Test
    void v3_resolves_duplicate_titles_with_id_suffix() {
        flyway("2").migrate();

        jdbc.execute("INSERT INTO movies (title, slug) VALUES ('Inception', NULL)");
        jdbc.execute("INSERT INTO movies (title, slug) VALUES ('Inception', NULL)");

        flyway(null).migrate();

        List<Map<String, Object>> inceptions = jdbc.queryForList(
                "SELECT id, slug FROM movies WHERE title = 'Inception' ORDER BY id");

        assertThat(inceptions).hasSize(2);

        Set<String> slugs = inceptions.stream()
                .map(m -> (String) m.get("slug"))
                .collect(Collectors.toSet());

        // Оба slug уникальны
        assertThat(slugs).hasSize(2);

        // batch_count = 2 для обеих строк → pass 1 не трогает ни одну.
        // Pass 2: обе получают "inception-{id}" (суффикс гарантирует уникальность).
        assertThat(slugs).doesNotContain("inception");
        assertThat(slugs).allMatch(s -> s.startsWith("inception-"));
    }

    // ── Сценарий 4: коллизия с существующим slug ─────────────────────────────

    @Test
    void v3_avoids_collision_with_existing_slug() {
        flyway("2").migrate();

        // Фильм уже имеет slug "batman" (был установлен явно при создании)
        jdbc.execute("INSERT INTO movies (title, slug) VALUES ('Batman Begins', 'batman')");
        // Новый фильм с title "Batman" и NULL slug — base_slug тоже будет "batman"
        jdbc.execute("INSERT INTO movies (title, slug) VALUES ('Batman', NULL)");

        flyway(null).migrate();

        String batmanSlug = (String) jdbc.queryForMap(
                "SELECT slug FROM movies WHERE title = 'Batman'").get("slug");

        // "batman" уже занят → pass 2 → "batman-{id}"
        assertThat(batmanSlug).isNotEqualTo("batman");
        assertThat(batmanSlug).startsWith("batman-");
    }

    // ── Сценарий 5: уже заполненные slug не трогаются ────────────────────────

    @Test
    void v3_does_not_overwrite_existing_slugs() {
        flyway("2").migrate();

        jdbc.execute("INSERT INTO movies (title, slug) VALUES ('Dune', 'dune-2021')");

        flyway(null).migrate();

        String slug = (String) jdbc.queryForMap(
                "SELECT slug FROM movies WHERE title = 'Dune'").get("slug");

        assertThat(slug).isEqualTo("dune-2021"); // не изменился
    }

    // ── Сценарий 6: title из одних спецсимволов — fallback на "movie-{id}" ──

    @Test
    void v3_handles_title_with_only_special_chars() {
        flyway("2").migrate();

        jdbc.execute("INSERT INTO movies (title, slug) VALUES ('!!! ???', NULL)");

        flyway(null).migrate();

        String slug = (String) jdbc.queryForMap(
                "SELECT slug FROM movies WHERE title = '!!! ???'").get("slug");

        assertThat(slug).isNotNull().isNotBlank();
        assertThat(slug).startsWith("movie-"); // fallback на "movie-{id}"
    }

    // ── Сценарий 7: все slug уникальны после V3 ──────────────────────────────

    @Test
    void v3_all_slugs_unique() {
        flyway("2").migrate();

        jdbc.execute("INSERT INTO movies (title, slug) VALUES ('Film A', NULL)");
        jdbc.execute("INSERT INTO movies (title, slug) VALUES ('Film A', NULL)");
        jdbc.execute("INSERT INTO movies (title, slug) VALUES ('Film A', NULL)");
        jdbc.execute("INSERT INTO movies (title, slug) VALUES ('Film B', 'film-b')");

        flyway(null).migrate();

        List<String> slugs = jdbc.queryForList("SELECT slug FROM movies", String.class);
        Set<String> unique = Set.copyOf(slugs);

        assertThat(slugs).hasSameSizeAs(unique); // нет дублей
    }

    // ── Утилиты ───────────────────────────────────────────────────────────────

    private Flyway flyway(String target) {
        var cfg = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false); // clean() нужен для изоляции тестов
        if (target != null) cfg.target(target);
        return cfg.load();
    }

    private String slugOf(List<Map<String, Object>> rows, String title) {
        return rows.stream()
                .filter(r -> title.equals(r.get("title")))
                .findFirst()
                .map(r -> (String) r.get("slug"))
                .orElseThrow();
    }
}

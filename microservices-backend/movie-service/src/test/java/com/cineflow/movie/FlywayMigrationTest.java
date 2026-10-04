package com.cineflow.movie;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Проверяем, что Flyway-миграции применяются корректно.
//
// Как это работает:
//   @DataJpaTest        — поднимает только JPA-слой (entities + Flyway), без контроллеров и сервисов.
//   @AutoConfigureTestDatabase(replace = NONE) — не заменяем datasource на H2.
//                         H2 не поддерживает Postgres-синтаксис (BIGSERIAL, RENAME COLUMN).
//   @Testcontainers     — JUnit-расширение, запускает @Container-поля перед тестами.
//   @ServiceConnection  — Spring Boot берёт jdbcUrl/user/password прямо из контейнера.
//
// Контейнер создаётся один раз на класс (static field), уничтожается после всех тестов.
// Каждый тест работает с одной и той же БД (Flyway запускает миграции при старте контекста).
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@TestPropertySource(properties = "eureka.client.enabled=false")
class FlywayMigrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void v2_renames_poster_url_to_poster() {
        List<String> columns = getColumnNames("movies");
        assertThat(columns).contains("poster");
        assertThat(columns).doesNotContain("poster_url");
    }

    @Test
    void v2_adds_new_fields() {
        List<String> columns = getColumnNames("movies");
        assertThat(columns).containsAll(List.of("slug", "banner", "rating_age", "trailer", "release_date"));
    }

    @Test
    void v2_drops_old_category_column() {
        List<String> columns = getColumnNames("movies");
        assertThat(columns).doesNotContain("category");
    }

    @Test
    void v2_creates_categories_table() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'categories'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void v2_creates_movie_categories_join_table() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'movie_categories'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void categories_table_has_unique_constraint_on_name() {
        jdbc.execute("INSERT INTO categories (name) VALUES ('Drama')");
        assertThatThrownBy(() -> jdbc.execute("INSERT INTO categories (name) VALUES ('Drama')"))
                .isInstanceOf(Exception.class);
    }

    @Test
    void slug_has_unique_constraint() {
        jdbc.execute("INSERT INTO movies (title, slug) VALUES ('Film A', 'film-a')");
        assertThatThrownBy(() -> jdbc.execute("INSERT INTO movies (title, slug) VALUES ('Film B', 'film-a')"))
                .isInstanceOf(Exception.class);
    }

    // ── V3 ────────────────────────────────────────────────────────────────────

    @Test
    void v3_creates_index_on_movie_categories_category_id() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes WHERE tablename = 'movie_categories' AND indexname = 'idx_movie_categories_category_id'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void v3_makes_slug_not_nullable() {
        String isNullable = jdbc.queryForObject(
                "SELECT is_nullable FROM information_schema.columns WHERE table_name = 'movies' AND column_name = 'slug'",
                String.class);
        assertThat(isNullable).isEqualTo("NO");
    }

    @Test
    void v3_insert_without_slug_is_rejected() {
        // После V3 slug NOT NULL — попытка вставить строку без slug должна упасть.
        assertThatThrownBy(() -> jdbc.execute("INSERT INTO movies (title) VALUES ('No Slug')"))
                .isInstanceOf(Exception.class);
    }

    @Test
    void all_migrations_recorded_in_flyway_history() {
        List<String> versions = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history ORDER BY installed_rank",
                String.class);
        assertThat(versions).containsExactly("1", "2", "3");
    }

    private List<String> getColumnNames(String table) {
        return jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = ?",
                String.class, table);
    }
}

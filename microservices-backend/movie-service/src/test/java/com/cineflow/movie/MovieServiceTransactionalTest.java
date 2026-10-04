package com.cineflow.movie;

import com.cineflow.movie.dto.movie.CreateMovieRequest;
import com.cineflow.movie.dto.page.PageResponse;
import com.cineflow.movie.exception.CategoryNotFoundException;
import com.cineflow.movie.exception.MovieNotFoundException;
import com.cineflow.movie.model.Category;
import com.cineflow.movie.model.Movie;
import com.cineflow.movie.repository.CategoryRepository;
import com.cineflow.movie.repository.MovieRepository;
import com.cineflow.movie.service.MovieService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import org.springframework.data.domain.PageRequest;

import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// @Import(MovieService.class): @DataJpaTest не поднимает @Service-бины.
// Добавляем MovieService вручную, чтобы не поднимать весь Spring-контекст.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(MovieService.class)
@TestPropertySource(properties = "eureka.client.enabled=false")
class MovieServiceTransactionalTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired private MovieService movieService;
    @Autowired private MovieRepository movieRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager txManager;

    // Проблема изоляции: MovieService.create() использует REQUIRES_NEW — данные
    // коммитятся вне транзакции теста и не откатываются вместе с ней.
    // Решение: перед каждым тестом очищаем таблицы в собственной committed-транзакции
    // (REQUIRES_NEW), чтобы следующий тест видел чистую базу при READ_COMMITTED.
    @BeforeEach
    void cleanDb() {
        inNewTx(() -> {
            jdbc.execute("TRUNCATE movie_categories, movies, categories RESTART IDENTITY CASCADE");
            return null;
        });
    }

    // ── Базовое создание ──────────────────────────────────────────────────────

    @Test
    void create_savesMovieWithCategories() {
        // Категорию коммитим до вызова create(): create() использует REQUIRES_NEW,
        // которая видит только уже закоммиченные данные (READ_COMMITTED).
        // Если сохранить категорию в транзакции теста (ещё не закоммиченной),
        // REQUIRES_NEW её не увидит и бросит CategoryNotFoundException.
        Category drama = inNewTx(() -> categoryRepository.save(newCategory("Drama")));

        CreateMovieRequest request = new CreateMovieRequest(
                "Inception", "inception", "desc",
                "inception.jpg", "inception-banner.jpg",
                "12+", "trailer.mp4", null,
                Set.of(drama.getId())
        );

        var response = movieService.create(request);

        assertThat(response.id()).isNotNull();
        assertThat(response.title()).isEqualTo("Inception");
        assertThat(response.categories()).hasSize(1);
        assertThat(response.categories().get(0).name()).isEqualTo("Drama");
    }

    // ── Откат при отсутствии категории ───────────────────────────────────────

    @Test
    void create_rollsBack_whenCategoryNotFound() {
        // CategoryNotFoundException бросается внутри REQUIRES_NEW до saveAndFlush —
        // REQUIRES_NEW откатывается, фильм не попадает в БД.
        CreateMovieRequest request = new CreateMovieRequest(
                "Ghost Film", "ghost-film", null,
                null, null, null, null, null,
                Set.of(999L)
        );

        assertThatThrownBy(() -> movieService.create(request))
                .isInstanceOf(CategoryNotFoundException.class);

        // @BeforeEach очистил базу, CategoryNotFoundException — до saveAndFlush:
        // count должен оставаться нулевым.
        assertThat(movieRepository.count()).isZero();
    }

    // ── readOnly: LazyInitializationException не должно возникать ────────────

    @Test
    void getAll_loadsCategories_withinReadOnlyTransaction() {
        // Этот тест не вызывает create() — сохраняем напрямую в транзакцию теста.
        // getAll() присоединяется к ней (Propagation.REQUIRED) и видит данные.
        // Без @Transactional(readOnly=true) на getAll() сессия закрылась бы
        // до обращения к LAZY-коллекции categories — LazyInitializationException.
        Category action = categoryRepository.save(newCategory("Action"));
        Movie movie = new Movie();
        movie.setTitle("Mad Max");
        movie.setSlug("mad-max");
        movie.setCategories(Set.of(action));
        movieRepository.save(movie);

        PageResponse<com.cineflow.movie.dto.movie.MovieResponse> page =
                movieService.getAll(PageRequest.of(0, 20));

        assertThat(page.content()).hasSize(1);
        assertThat(page.content().get(0).categories()).hasSize(1);
        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(page.last()).isTrue();
    }

    @Test
    void getById_throwsMovieNotFoundException_whenMissing() {
        assertThatThrownBy(() -> movieService.getById(999L))
                .isInstanceOf(MovieNotFoundException.class);
    }

    @Test
    void create_withNoCategories_savesMovieSuccessfully() {
        // slug null → baseSlugFromTitle("Simple Film") = "simple-film"
        CreateMovieRequest request = new CreateMovieRequest(
                "Simple Film", null, null,
                null, null, null, null, null,
                null
        );

        var response = movieService.create(request);

        assertThat(response.id()).isNotNull();
        assertThat(response.slug()).isEqualTo("simple-film");
        assertThat(response.categories()).isEmpty();
    }

    // ── Утилиты ───────────────────────────────────────────────────────────────

    private <T> T inNewTx(Supplier<T> action) {
        var tpl = new TransactionTemplate(txManager);
        tpl.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tpl.execute(status -> action.get());
    }

    private Category newCategory(String name) {
        Category c = new Category();
        c.setName(name);
        return c;
    }
}

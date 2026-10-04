package com.cineflow.movie;

import com.cineflow.movie.dto.movie.CreateMovieRequest;
import com.cineflow.movie.dto.movie.MovieResponse;
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

import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(MovieService.class)
@TestPropertySource(properties = "eureka.client.enabled=false")
class MovieServiceCollisionTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired private MovieService movieService;
    @Autowired private MovieRepository movieRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager txManager;

    @BeforeEach
    void cleanDb() {
        inNewTx(() -> {
            jdbc.execute("TRUNCATE movie_categories, movies, categories RESTART IDENTITY CASCADE");
            return null;
        });
    }

    // ── Сценарий 1: slug задан явно — используется как есть ──────────────────

    @Test
    void providedSlug_usedAsIs() {
        var response = movieService.create(new CreateMovieRequest(
                "Dune", "dune-part-one", null, null, null, null, null, null, null));
        assertThat(response.slug()).isEqualTo("dune-part-one");
    }

    // ── Сценарий 2: title из одних спецсимволов → fallback "movie" ───────────

    @Test
    void onlySpecialCharsTitle_fallsBackToMovie() {
        var response = movieService.create(new CreateMovieRequest(
                "!!! ???", null, null, null, null, null, null, null, null));
        assertThat(response.slug()).isEqualTo("movie");
    }

    // ── Сценарий 3: коллизия slug → суффикс -2 ───────────────────────────────

    @Test
    void collision_addsSuffix() {
        // Вставляем фильм с slug "inception" заранее через committed-транзакцию.
        inNewTx(() -> {
            Movie m = new Movie();
            m.setTitle("Inception");
            m.setSlug("inception");
            return movieRepository.saveAndFlush(m);
        });

        // create() с тем же title и без explicit slug получает коллизию:
        // первая попытка (slug="inception") → DataIntegrityViolationException.
        // Вторая попытка (slug="inception-2") → успех.
        var response = movieService.create(new CreateMovieRequest(
                "Inception", null, null, null, null, null, null, null, null));

        assertThat(response.slug()).isEqualTo("inception-2");
    }

    // ── Сценарий 4: коллизия уже-заданного slug не ретраится ─────────────────

    @Test
    void explicitSlugCollision_throwsImmediately() {
        inNewTx(() -> {
            Movie m = new Movie();
            m.setTitle("Dune First");
            m.setSlug("dune");
            return movieRepository.saveAndFlush(m);
        });

        // Если slug задан явно — не суффиксируем: клиент сам должен выбрать другой.
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                movieService.create(new CreateMovieRequest(
                        "Dune Second", "dune", null, null, null, null, null, null, null))
        ).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    // ── Сценарий 5: параллельное создание — оба запроса успешны ──────────────

    @Test
    void parallel_sameTitleCreate_bothSucceed() throws InterruptedException {
        // Два потока одновременно создают фильм с одним title и без slug.
        // Оба генерируют base_slug="concurrent-film".
        // При READ_COMMITTED обе транзакции читают «slug свободен».
        // Одна коммитится первой, вторая получает DataIntegrityViolationException,
        // ловит её, суффиксирует slug до "concurrent-film-2" и повторяет.
        // Итог: оба фильма сохранены с уникальными slug.
        var latch = new CountDownLatch(1);
        var results = new CopyOnWriteArrayList<MovieResponse>();
        var errors = new CopyOnWriteArrayList<Throwable>();

        Runnable task = () -> {
            try {
                latch.await();
                results.add(movieService.create(new CreateMovieRequest(
                        "Concurrent Film", null, null, null, null, null, null, null, null)));
            } catch (Throwable e) {
                errors.add(e);
            }
        };

        var t1 = new Thread(task);
        var t2 = new Thread(task);
        t1.start();
        t2.start();
        latch.countDown();
        t1.join(5000);
        t2.join(5000);

        assertThat(errors).as("No errors expected: %s", errors).isEmpty();
        assertThat(results).hasSize(2);

        Set<String> slugs = results.stream()
                .map(MovieResponse::slug)
                .collect(Collectors.toSet());
        assertThat(slugs).hasSize(2);
        assertThat(slugs).allMatch(s -> s.startsWith("concurrent-film"));
    }

    // ── Утилиты ───────────────────────────────────────────────────────────────

    private <T> T inNewTx(Supplier<T> action) {
        var tpl = new TransactionTemplate(txManager);
        tpl.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tpl.execute(status -> action.get());
    }
}

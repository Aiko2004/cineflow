package com.cineflow.movie.service;

import com.cineflow.movie.dto.movie.CreateMovieRequest;
import com.cineflow.movie.model.Movie;
import com.cineflow.movie.repository.CategoryRepository;
import com.cineflow.movie.repository.MovieRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.sql.SQLException;
import java.util.HashSet;

import static com.cineflow.movie.service.MovieService.isSlugConflict;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

// Чистые юнит-тесты — без Spring-контекста, без БД.
// Проверяют логику isSlugConflict() и поведение retry в create().
//
// Используется no-op PlatformTransactionManager:
//   TransactionTemplate выполняет callback, при RuntimeException вызывает rollback()
//   (no-op здесь) и перебрасывает исключение — ровно то поведение, что нужно тестировать.
@ExtendWith(MockitoExtension.class)
class MovieServiceRetryTest {

    @Mock MovieRepository movieRepository;
    @Mock CategoryRepository categoryRepository;

    private MovieService service;

    @BeforeEach
    void setUp() {
        service = new MovieService(movieRepository, categoryRepository, noOpTxManager());
    }

    // ── isSlugConflict() ─────────────────────────────────────────────────────

    @Test
    void isSlugConflict_trueForSlugConstraint() {
        assertThat(isSlugConflict(mkDive(MovieService.SLUG_CONSTRAINT))).isTrue();
    }

    @Test
    void isSlugConflict_falseForOtherConstraint() {
        // FK-нарушение — не slug-конфликт: суффикс к slug его не исправит.
        assertThat(isSlugConflict(mkDive("movie_categories_category_id_fkey"))).isFalse();
    }

    @Test
    void isSlugConflict_falseWhenNoCause() {
        // DataIntegrityViolationException без Hibernate-причины — тоже не slug-конфликт.
        assertThat(isSlugConflict(new DataIntegrityViolationException("no cause"))).isFalse();
    }

    // ── create(): поведение retry ─────────────────────────────────────────────

    @Test
    void fkViolation_notRetried_throwsImmediately() {
        // При FK-нарушении повторять вставку бессмысленно:
        // суффикс к slug не фиксит несуществующую category_id.
        // saveAndFlush должен быть вызван ровно один раз.
        var fkViolation = mkDive("movie_categories_category_id_fkey");
        when(movieRepository.saveAndFlush(any())).thenThrow(fkViolation);

        assertThatThrownBy(() -> service.create(new CreateMovieRequest(
                "Test Film", null, null, null, null, null, null, null, null)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .isSameAs(fkViolation);  // та же ссылка: не обёрнуто, не подменено

        verify(movieRepository, times(1)).saveAndFlush(any());
    }

    @Test
    void slugConflict_retriedWithSuffix() {
        // Первая попытка (slug="test-film") — коллизия uk_movies_slug.
        // Вторая попытка (slug="test-film-2") — успех.
        var slugViolation = mkDive(MovieService.SLUG_CONSTRAINT);
        Movie saved = savedMovieStub("test-film-2");
        when(movieRepository.saveAndFlush(any()))
                .thenThrow(slugViolation)
                .thenReturn(saved);

        var response = service.create(new CreateMovieRequest(
                "Test Film", null, null, null, null, null, null, null, null));

        assertThat(response.slug()).isEqualTo("test-film-2");
        verify(movieRepository, times(2)).saveAndFlush(any());
    }

    // ── Вспомогательные методы ────────────────────────────────────────────────

    /** DataIntegrityViolationException с Hibernate-причиной по указанному констрейнту. */
    private DataIntegrityViolationException mkDive(String constraintName) {
        var cause = new ConstraintViolationException(
                "constraint violation", new SQLException(), constraintName);
        return new DataIntegrityViolationException("constraint violation", cause);
    }

    private Movie savedMovieStub(String slug) {
        Movie m = new Movie();
        m.setId(1L);
        m.setTitle("Test Film");
        m.setSlug(slug);
        m.setCategories(new HashSet<>());
        return m;
    }

    /**
     * TransactionManager, который выполняет callback без реальных транзакций.
     * TransactionTemplate при RuntimeException вызывает rollback() (no-op) и перебрасывает.
     */
    private PlatformTransactionManager noOpTxManager() {
        return new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition def) {
                return new SimpleTransactionStatus();
            }
            @Override public void commit(TransactionStatus s) {}
            @Override public void rollback(TransactionStatus s) {}
        };
    }
}

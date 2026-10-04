package com.cineflow.movie.service;

import com.cineflow.movie.dto.movie.CreateMovieRequest;
import com.cineflow.movie.dto.movie.MovieResponse;
import com.cineflow.movie.dto.page.PageResponse;
import com.cineflow.movie.exception.CategoryNotFoundException;
import com.cineflow.movie.exception.MovieNotFoundException;
import com.cineflow.movie.model.Category;
import com.cineflow.movie.model.Movie;
import com.cineflow.movie.repository.CategoryRepository;
import com.cineflow.movie.repository.MovieRepository;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MovieService {

    // Имя констрейнта из V2-миграции: ADD CONSTRAINT uk_movies_slug UNIQUE (slug).
    // Хранится в константе — чтобы поиск по коду находил все места, связанные с ним.
    static final String SLUG_CONSTRAINT = "uk_movies_slug";

    // Колонка slug — VARCHAR(255). Максимальный суффикс при retry: "-10" (3 символа).
    // 200 оставляет 55 символов запаса — URL выглядит читаемо, переполнения нет.
    static final int SLUG_MAX_LENGTH = 200;

    private static final int MAX_SLUG_RETRIES = 9;
    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-z0-9]+");

    private final MovieRepository movieRepository;
    private final CategoryRepository categoryRepository;
    private final PlatformTransactionManager txManager;

    @Transactional(readOnly = true)
    public PageResponse<MovieResponse> getAll(Pageable pageable) {
        // page.map(this::toResponse) вызывается синхронно внутри @Transactional —
        // Hibernate-сессия открыта, lazy-загрузка categories работает.
        return PageResponse.from(movieRepository.findAll(pageable).map(this::toResponse));
    }

    @Transactional(readOnly = true)
    public MovieResponse getById(Long id) {
        return movieRepository.findById(id)
                .map(this::toResponse)
                .orElseThrow(() -> new MovieNotFoundException(id));
    }

    // @Transactional намеренно отсутствует на уровне метода.
    //
    // Атомарность movies + movie_categories обеспечена внутри каждой REQUIRES_NEW
    // попытки: saveAndFlush() генерирует один flush → оба INSERT в одной транзакции.
    //
    // Почему нужны REQUIRES_NEW-попытки, а не один @Transactional:
    //   DataIntegrityViolationException (коллизия uk_movies_slug) переводит активную
    //   транзакцию в rollback-only. Повторная попытка в той же транзакции невозможна.
    //   Решение — каждая попытка в своей транзакции; провалившаяся откатывается,
    //   не мешая следующей.
    //
    // Почему existsBySlug() перед вставкой не решает проблему:
    //   При READ_COMMITTED две параллельных транзакции обе прочитают «slug свободен»,
    //   обе попытаются вставить — одна упадёт на uk_movies_slug.
    //   DB-констрейнт — единственный надёжный арбитр; мы используем его именно так:
    //   пробуем вставить, ловим конфликт, суффиксируем slug и повторяем.
    public MovieResponse create(CreateMovieRequest request) {
        String baseSlug = request.slug() != null
                ? request.slug()
                : baseSlugFromTitle(request.title());

        var requiresNew = new TransactionTemplate(txManager);
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        DataIntegrityViolationException lastDive = null;
        for (int attempt = 0; attempt <= MAX_SLUG_RETRIES; attempt++) {
            // attempt=0 → "inception", attempt=1 → "inception-2", attempt=2 → "inception-3", ...
            final String slug = attempt == 0 ? baseSlug : baseSlug + "-" + (attempt + 1);
            try {
                return requiresNew.execute(status -> {
                    // Категории загружаем внутри REQUIRES_NEW: Hibernate требует, чтобы
                    // сущности в movie.categories были managed в том же persistence context,
                    // что и сохраняемый Movie — иначе join-таблица не заполнится.
                    Set<Category> categories = resolveCategories(request.categoryIds());
                    Movie movie = buildMovie(request, categories, slug);
                    return toResponse(movieRepository.saveAndFlush(movie));
                });
            } catch (DataIntegrityViolationException ex) {
                // Повторяем ТОЛЬКО при конфликте именно uk_movies_slug.
                // FK-нарушение, NOT NULL, переполнение VARCHAR — это другие проблемы,
                // которые суффикс не исправит. Пробрасываем их немедленно.
                if (!isSlugConflict(ex) || request.slug() != null) throw ex;
                lastDive = ex;
            }
        }
        // Все 10 попыток исчерпаны — GlobalExceptionHandler вернёт 409.
        throw lastDive;
    }

    // Package-private: доступен в тестах того же пакета без рефлексии.
    static boolean isSlugConflict(DataIntegrityViolationException ex) {
        if (ex.getCause() instanceof ConstraintViolationException cve) {
            return SLUG_CONSTRAINT.equals(cve.getConstraintName());
        }
        return false;
    }

    // Package-private и static — доступен для юнит-тестов без Spring-контекста.
    static String baseSlugFromTitle(String title) {
        if (title == null) return "movie";
        String slug = NON_ALPHANUMERIC.matcher(title.toLowerCase()).replaceAll("-")
                .replaceAll("^-+|-+$", "");
        if (slug.isEmpty()) return "movie";
        if (slug.length() > SLUG_MAX_LENGTH) {
            // Обрезаем по длине, затем убираем дефис на конце — он мог оказаться
            // прямо на границе среза (например, "title" → "aaaa...-" → "aaaa...").
            slug = slug.substring(0, SLUG_MAX_LENGTH).replaceAll("-+$", "");
            if (slug.isEmpty()) return "movie";
        }
        return slug;
    }

    private Set<Category> resolveCategories(Set<Long> categoryIds) {
        if (categoryIds == null || categoryIds.isEmpty()) return new HashSet<>();
        Set<Category> found = categoryRepository.findAllByIdIn(categoryIds);
        if (found.size() != categoryIds.size()) {
            Set<Long> foundIds = found.stream().map(Category::getId).collect(Collectors.toSet());
            Set<Long> missing = categoryIds.stream()
                    .filter(id -> !foundIds.contains(id))
                    .collect(Collectors.toSet());
            throw new CategoryNotFoundException(missing);
        }
        return found;
    }

    private Movie buildMovie(CreateMovieRequest request, Set<Category> categories, String slug) {
        Movie movie = new Movie();
        movie.setTitle(request.title());
        movie.setSlug(slug);
        movie.setDescription(request.description());
        movie.setPoster(request.poster());
        movie.setBanner(request.banner());
        movie.setRatingAge(request.ratingAge());
        movie.setTrailer(request.trailer());
        movie.setReleaseDate(request.releaseDate());
        movie.setCategories(categories);
        return movie;
    }

    private MovieResponse toResponse(Movie movie) {
        List<MovieResponse.CategoryView> categoryViews = movie.getCategories().stream()
                .map(c -> new MovieResponse.CategoryView(c.getId(), c.getName()))
                .toList();
        return new MovieResponse(
                movie.getId(),
                movie.getTitle(),
                movie.getSlug(),
                movie.getDescription(),
                movie.getPoster(),
                movie.getBanner(),
                movie.getRatingAge(),
                movie.getTrailer(),
                movie.getReleaseDate(),
                categoryViews
        );
    }
}

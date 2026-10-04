package com.cineflow.movie.service;

import org.junit.jupiter.api.Test;

import static com.cineflow.movie.service.MovieService.baseSlugFromTitle;
import static org.assertj.core.api.Assertions.assertThat;

// Чистые юнит-тесты — никаких Spring/DB/Testcontainers.
// baseSlugFromTitle() — package-private static метод, доступен напрямую.
class SlugTest {

    @Test
    void normalTitle_producesCleanSlug() {
        assertThat(baseSlugFromTitle("Inception")).isEqualTo("inception");
    }

    @Test
    void spaces_replacedWithHyphens() {
        assertThat(baseSlugFromTitle("The Dark Knight")).isEqualTo("the-dark-knight");
    }

    @Test
    void mixedCase_lowercased() {
        assertThat(baseSlugFromTitle("The MATRIX Resurrections")).isEqualTo("the-matrix-resurrections");
    }

    @Test
    void consecutiveSpecialChars_collapsedToOneHyphen() {
        assertThat(baseSlugFromTitle("Hello   World!!!Film")).isEqualTo("hello-world-film");
    }

    @Test
    void leadingTrailingSpecialChars_trimmed() {
        assertThat(baseSlugFromTitle("---Hello World---")).isEqualTo("hello-world");
    }

    // Следующие три теста покрывают edge-кейсы, идентичные V3-миграции:
    // при пустом base_slug SQL использует 'movie' как fallback.

    @Test
    void onlySpecialChars_fallsBackToMovie() {
        assertThat(baseSlugFromTitle("!!! ???")).isEqualTo("movie");
    }

    @Test
    void nullTitle_fallsBackToMovie() {
        assertThat(baseSlugFromTitle(null)).isEqualTo("movie");
    }

    @Test
    void emptyTitle_fallsBackToMovie() {
        assertThat(baseSlugFromTitle("")).isEqualTo("movie");
    }

    // ── Обрезка длинных slug ──────────────────────────────────────────────────

    @Test
    void longTitle_truncatedToMaxLength() {
        // 300 'a' → slug "aaa...aaa" (300 символов) → обрезается до SLUG_MAX_LENGTH
        String result = baseSlugFromTitle("a".repeat(300));
        assertThat(result.length()).isEqualTo(MovieService.SLUG_MAX_LENGTH);
        assertThat(result).doesNotEndWith("-");
    }

    @Test
    void longTitleWithHyphenAtCutPoint_trailingDashTrimmed() {
        // 199 'a' + 2 пробела + 'b' → slug "aaa...(199 штук)aaa-b" (201 символ)
        // После среза на позиции 200: "aaa...(199)aaa-" → дефис на конце удаляется.
        // Итог: 199 символов, без дефиса на конце.
        String title = "a".repeat(199) + "  b";
        String result = baseSlugFromTitle(title);
        assertThat(result).isEqualTo("a".repeat(199));
        assertThat(result).doesNotEndWith("-");
    }
}

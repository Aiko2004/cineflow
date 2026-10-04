package com.cineflow.movie;

import com.cineflow.movie.config.SecurityConfig;
import com.cineflow.movie.controller.MovieController;
import com.cineflow.movie.dto.page.PageResponse;
import com.cineflow.movie.service.MovieService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// @WebMvcTest загружает только веб-слой (контроллеры, MVC-конфиг).
// SpringDataWebAutoConfiguration включена в этот слайс и регистрирует
// PageableHandlerMethodArgumentResolver, который читает max-page-size из конфига.
// @Import(SecurityConfig.class): @WebMvcTest не подхватывает пользовательские
// SecurityFilterChain автоматически. Без импорта применилась бы дефолтная защита
// Spring Security и GET /movies вернул бы 401. JwtDecoder мокается — на permitAll-GET
// он не вызывается, но бин нужен для oauth2ResourceServer().jwt().
@WebMvcTest(MovieController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "eureka.client.enabled=false",
        "spring.data.web.pageable.max-page-size=100",
        "spring.data.web.pageable.default-page-size=20"
})
class MovieControllerPaginationTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean MovieService movieService;
    @MockitoBean JwtDecoder jwtDecoder;

    // Заглушка пустой страницы — возвращается на любой Pageable.
    private static final PageResponse<Object> EMPTY_PAGE =
            new PageResponse<>(List.of(), 0, 20, 0L, 0, true);

    @Test
    void getAll_defaultPageable_page0size20() throws Exception {
        when(movieService.getAll(any())).thenReturn(emptyPage(20));

        mockMvc.perform(get("/movies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.content").isArray());

        var captor = ArgumentCaptor.forClass(Pageable.class);
        verify(movieService).getAll(captor.capture());
        assertThat(captor.getValue().getPageNumber()).isEqualTo(0);
        assertThat(captor.getValue().getPageSize()).isEqualTo(20);
    }

    @Test
    void getAll_explicitPage1size5() throws Exception {
        when(movieService.getAll(any())).thenReturn(emptyPage(5));

        var captor = ArgumentCaptor.forClass(Pageable.class);
        mockMvc.perform(get("/movies").param("page", "1").param("size", "5"))
                .andExpect(status().isOk());

        verify(movieService).getAll(captor.capture());
        assertThat(captor.getValue().getPageNumber()).isEqualTo(1);
        assertThat(captor.getValue().getPageSize()).isEqualTo(5);
    }

    @Test
    void getAll_sizeExceedsMax_cappedAt100() throws Exception {
        // Ключевой тест: клиент просит 9999 элементов — должен получить максимум 100.
        // Ограничение задаётся в application.yaml, а не в коде контроллера:
        // PageableHandlerMethodArgumentResolver.setMaxPageSize() вызывается
        // автоматически через SpringDataWebAutoConfiguration.
        when(movieService.getAll(any())).thenReturn(emptyPage(100));

        var captor = ArgumentCaptor.forClass(Pageable.class);
        mockMvc.perform(get("/movies").param("size", "9999"))
                .andExpect(status().isOk());

        verify(movieService).getAll(captor.capture());
        assertThat(captor.getValue().getPageSize())
                .as("size должен быть ограничен max-page-size=100, а не переданным 9999")
                .isEqualTo(100);
    }

    @Test
    void getAll_sizeZero_fallsBackToDefault() throws Exception {
        // size=0 недопустим — SpringDataWebAutoConfiguration заменяет его дефолтом.
        when(movieService.getAll(any())).thenReturn(emptyPage(20));

        var captor = ArgumentCaptor.forClass(Pageable.class);
        mockMvc.perform(get("/movies").param("size", "0"))
                .andExpect(status().isOk());

        verify(movieService).getAll(captor.capture());
        assertThat(captor.getValue().getPageSize())
                .as("size=0 должен быть заменён default-page-size=20")
                .isEqualTo(20);
    }

    @SuppressWarnings("unchecked")
    private static <T> PageResponse<T> emptyPage(int size) {
        return (PageResponse<T>) new PageResponse<>(List.of(), 0, size, 0L, 0, true);
    }
}

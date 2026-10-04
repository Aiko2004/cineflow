package com.cineflow.apigateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

// Проверка токена на периметре. Без валидного JWT защищённые маршруты получают 401
// ещё на Gateway. Публичные маршруты — явный список (каталог, расписание, сам /auth).
//
// Это не отменяет проверку в самих сервисах (см. movie-service SecurityConfig):
// периметр — не единственная линия. Полагаться только на него — "твёрдая скорлупа
// при мягкой сердцевине": любой внутри сети ходил бы куда угодно (AUTH_DESIGN.md §8).
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // CSRF не нужен: stateless API на токенах, cookie-сессий нет.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Публично: получение токенов и раздача ключей.
                        .requestMatchers("/auth/**", "/.well-known/**").permitAll()
                        // Публично: только чтение каталогов/расписания (GET).
                        .requestMatchers(HttpMethod.GET,
                                "/movies/**",
                                "/api/theaters/**", "/api/halls/**", "/api/seats/**",
                                "/api/screenings/**").permitAll()
                        // Всё остальное (в т.ч. любые POST/PUT/DELETE) — только с токеном.
                        .anyRequest().authenticated())
                // jwk-set-uri берётся из spring.security.oauth2.resourceserver.jwt.
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {}));
        return http.build();
    }
}

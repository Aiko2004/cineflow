package com.cineflow.screening.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

// Защита ресурсного сервиса по образцу movie-service (AUTH_DESIGN.md §8, рецепт в CLAUDE.md).
// Расписание/сеансы читаются анонимно; создание сеанса (POST) требует токен. Проверка
// дублирует периметр Gateway.
//
// Только HTTP-слой. gRPC-сервер (порт 9083) — внутренний вызов booking→screening,
// живёт вне сервлетного контейнера и этой цепочкой не затрагивается.
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Расписание/сеансы (в т.ч. /scroll) — публичное чтение.
                        .requestMatchers(HttpMethod.GET, "/api/screenings/**").permitAll()
                        // Swagger/OpenAPI открыты для удобства разработки.
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        // Создание сеанса и прочая запись — только с токеном.
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt ->
                        jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));
        return http.build();
    }

    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }
}

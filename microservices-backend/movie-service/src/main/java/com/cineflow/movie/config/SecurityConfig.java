package com.cineflow.movie.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

// Образец защищённого ресурсного сервиса (AUTH_DESIGN.md §8).
// Чтение открыто, запись требует аутентификации. Проверка здесь дублирует проверку
// на Gateway намеренно: сервис не должен доверять тому, что "раз запрос дошёл — он проверен".
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Каталог читают анонимно.
                        .requestMatchers(HttpMethod.GET, "/movies/**").permitAll()
                        // Swagger/OpenAPI оставляем открытыми для удобства разработки.
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        // Создание фильма — только с токеном.
                        .requestMatchers(HttpMethod.POST, "/movies/**").authenticated()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt ->
                        jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));
        return http.build();
    }

    // Токены auth-service кладут роли в claim "roles" уже с префиксом ROLE_.
    // Настраиваем конвертер читать их оттуда (по умолчанию Spring смотрит "scope"/"scp").
    // Пригодится, когда появятся admin-роли и @PreAuthorize.
    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("");   // префикс ROLE_ уже внутри значения claim

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }
}

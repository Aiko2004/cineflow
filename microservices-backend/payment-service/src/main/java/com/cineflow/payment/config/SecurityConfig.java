package com.cineflow.payment.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

// Защита ресурсного сервиса по образцу movie/booking (AUTH_DESIGN.md §8).
// Публичных чтений нет: платёж — личные данные. Единственный публичный путь — webhook
// провайдера: токена у провайдера нет, подлинность доказывает HMAC-подпись тела.
//
// Dev-эндпоинты симулятора (/payment/sim/**, профиль kaspi-sim) НЕ открыты: «приложение
// Kaspi» требует токен любого пользователя — так они не становятся анонимной кнопкой
// «оплатить чужой счёт» даже в dev.
//
// /internal/** требует токен пользователя (из него берётся user_id), но это НЕ защита
// границы доверия: эндпоинт защищён тем, что gateway его не маршрутизирует (PAYMENT_DESIGN.md §7).
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Swagger/OpenAPI открыты для удобства разработки.
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        // Ровно один путь и один метод — не /payment/webhooks/**.
                        .requestMatchers(HttpMethod.POST, "/payment/webhooks/kaspi").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt ->
                        jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));
        return http.build();
    }

    // Роли из claim "roles" (значения уже с префиксом ROLE_) — задел под @PreAuthorize.
    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }
}

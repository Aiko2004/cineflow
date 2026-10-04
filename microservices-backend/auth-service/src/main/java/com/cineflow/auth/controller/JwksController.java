package com.cineflow.auth.controller;

import com.cineflow.auth.service.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

// Публичные ключи для проверки подписи. Каждый ресурсный сервис указывает этот URL
// в spring.security.oauth2.resourceserver.jwt.jwk-set-uri — Spring сам забирает ключи,
// кеширует и перезапрашивает при встрече неизвестного kid.
@RestController
@RequiredArgsConstructor
public class JwksController {

    private final JwtService jwtService;

    @GetMapping("/.well-known/jwks.json")
    public Map<String, Object> jwks() {
        return jwtService.jwkSetJson();
    }
}

package com.cineflow.auth.service;

import com.cineflow.auth.config.AuthProperties;
import com.cineflow.auth.exception.RateLimitExceededException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

// Два независимых лимита на запрос кода (AUTH_DESIGN.md §2):
//   по email — не заваливать письмами одного человека;
//   по IP    — иначе атакующий перебирает разные адреса с одной машины, и лимит
//              по email его не останавливает.
@Service
@RequiredArgsConstructor
public class RateLimitService {

    private static final String EMAIL_KEY = "otp:rate:email:";
    private static final String IP_KEY = "otp:rate:ip:";

    // INCR и EXPIRE атомарно в одном Lua-скрипте. Раздельные команды опасны: обрыв
    // соединения между INCR и EXPIRE оставил бы ключ без TTL — то есть навсегда,
    // и email/IP заблокировался бы необратимо. Redis выполняет скрипт целиком либо никак.
    // TTL ставим только при первом обращении (c == 1), чтобы окно не «продлевалось»
    // каждым запросом (иначе активный перебор держал бы блок бесконечно).
    private static final RedisScript<Long> INCR_WITH_TTL = new DefaultRedisScript<>("""
            local c = redis.call('INCR', KEYS[1])
            if c == 1 then
              redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return c
            """, Long.class);

    private final StringRedisTemplate redis;
    private final AuthProperties properties;

    public void checkEmail(String email) {
        AuthProperties.RateLimit.Limit limit = properties.rateLimit().email();
        hit(EMAIL_KEY + email.toLowerCase(), limit.max(), limit.window());
    }

    public void checkIp(String ip) {
        AuthProperties.RateLimit.Limit limit = properties.rateLimit().ip();
        hit(IP_KEY + ip, limit.max(), limit.window());
    }

    private void hit(String key, int max, Duration window) {
        Long count = redis.execute(
                INCR_WITH_TTL,
                List.of(key),
                String.valueOf(window.toSeconds()));

        if (count != null && count > max) {
            throw new RateLimitExceededException();
        }
    }
}

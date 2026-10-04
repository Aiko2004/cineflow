package com.cineflow.booking.service;

import com.cineflow.booking.config.BookingProperties;
import com.cineflow.booking.exception.SeatHoldConflictException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// Мягкие удержания мест на этапе выбора (BOOKING_DESIGN.md §4).
// Это СЛОЙ УДОБСТВА, не гарантия: корректность держит частичный индекс в БД.
// Если Redis перезапустится и потеряет ключи — двойной продажи всё равно не будет.
//
//   Клик по месту:  SET seat:{screeningId}:{seatId} {userId} NX EX ttl
//   Снятие:         DEL, но только если значение == свой userId
@Service
@RequiredArgsConstructor
public class RedisHoldService {

    private static final String KEY_PREFIX = "seat:";

    // Снятие удержания только владельцем: сравнение значения и DEL — атомарно в Lua.
    // Иначе между GET и DEL чужой TTL мог бы истечь, ключ перезахватить другой, и мы
    // удалили бы чужое удержание (BOOKING_DESIGN.md §4).
    private static final RedisScript<Long> RELEASE_IF_OWNER = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            else
              return 0
            end
            """, Long.class);

    private final StringRedisTemplate redis;
    private final BookingProperties properties;

    // Захватывает удержание на все места атомарно по одному (SET NX). Если хоть одно занято —
    // откатывает уже захваченные НАМИ и бросает 409. Возвращает срок жизни удержаний.
    public OffsetDateTime hold(UUID screeningId, List<Long> seatIds, UUID userId) {
        Duration ttl = properties.seatHoldTtl();
        String owner = userId.toString();
        List<Long> acquired = new ArrayList<>();

        for (Long seatId : seatIds) {
            Boolean ok = redis.opsForValue()
                    .setIfAbsent(key(screeningId, seatId), owner, ttl); // SET NX EX
            if (!Boolean.TRUE.equals(ok)) {
                release(screeningId, acquired, userId); // откат частичного захвата
                throw new SeatHoldConflictException();
            }
            acquired.add(seatId);
        }
        return OffsetDateTime.now().plus(ttl);
    }

    public void release(UUID screeningId, List<Long> seatIds, UUID userId) {
        String owner = userId.toString();
        for (Long seatId : seatIds) {
            redis.execute(RELEASE_IF_OWNER, List.of(key(screeningId, seatId)), owner);
        }
    }

    // Для тестов: кто держит место (null — свободно).
    public String currentOwner(UUID screeningId, Long seatId) {
        return redis.opsForValue().get(key(screeningId, seatId));
    }

    private String key(UUID screeningId, Long seatId) {
        return KEY_PREFIX + screeningId + ":" + seatId;
    }
}

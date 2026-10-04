package com.cineflow.booking.util;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

// QR-токен при подтверждении (BOOKING_DESIGN.md §8). 256 бит из SecureRandom, а НЕ
// производная от bookingId: иначе чужой билет подбирается по известному id.
// Проверка на входе в кинотеатр — просто поиск по индексу (в этом инкременте не реализована).
@Component
public class QrTokenGenerator {

    private final SecureRandom secureRandom = new SecureRandom();

    public String generate() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}

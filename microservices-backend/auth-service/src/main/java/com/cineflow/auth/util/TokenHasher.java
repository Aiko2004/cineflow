package com.cineflow.auth.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

// SHA-256 → hex (64 символа). Используется и для refresh-токенов, и для OTP-кодов:
// в хранилище (Postgres/Redis) лежит хеш, не исходное значение.
//
// Соль намеренно не используется: refresh-токен — это 256 бит из SecureRandom
// (не низкоэнтропийный пароль), перебор по радужным таблицам невозможен.
public final class TokenHasher {

    private TokenHasher() {
    }

    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 гарантирован спецификацией JVM — сюда не попадём.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    // Сравнение хешей за постоянное время. Обычный String.equals() возвращает результат,
    // как только встретит первый несовпавший символ — по времени ответа можно посимвольно
    // подобрать корректный хеш (timing attack). MessageDigest.isEqual() в современных JDK
    // реализован constant-time: время не зависит от позиции расхождения.
    public static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }
}

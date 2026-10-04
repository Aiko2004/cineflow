package com.cineflow.auth.service;

import com.cineflow.auth.config.AuthProperties;
import com.cineflow.auth.dto.OtpType;
import com.cineflow.auth.exception.InvalidOtpException;
import com.cineflow.auth.exception.OtpAttemptsExceededException;
import com.cineflow.auth.util.TokenHasher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.List;
import java.util.Map;

// OTP-коды: генерация, хранение в Redis с TTL, проверка с одноразовостью и лимитом попыток.
//
// В Redis (AUTH_DESIGN.md §2):
//   otp:{email}  Hash { codeHash, attempts }  TTL 5 мин
// Хранится хеш кода, не сам код. Ключ истекает сам — фоновой чистки не нужно.
@Service
@Slf4j
@RequiredArgsConstructor
public class OtpService {

    private static final String KEY_PREFIX = "otp:";
    private static final String FIELD_CODE_HASH = "codeHash";
    private static final String FIELD_ATTEMPTS = "attempts";

    // Запись кода атомарно: HSET обоих полей + EXPIRE в одном скрипте. Раздельные putAll()
    // и expire() опасны — обрыв между ними оставил бы код без TTL (жил бы до перезаписи,
    // а не 5 минут). Redis выполняет скрипт целиком либо никак.
    //
    // Модель Hash (а не одна строка) выбрана осознанно: счётчик attempts инкрементируется
    // при каждой неверной попытке через HINCRBY — это одна атомарная команда. В одной строке
    // тот же инкремент стал бы read-modify-write (гонка или ещё один скрипт). Hash здесь
    // ровно под задачу: секрет + атомарный счётчик перебора.
    private static final RedisScript<Long> STORE_OTP = new DefaultRedisScript<>("""
            redis.call('HSET', KEYS[1], 'codeHash', ARGV[1], 'attempts', '0')
            redis.call('EXPIRE', KEYS[1], ARGV[2])
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final RateLimitService rateLimitService;
    private final AuthProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();

    // Запрос кода. Лимиты проверяются ДО генерации. Существование пользователя здесь
    // не проверяется вообще — код кладётся по email независимо от того, есть ли аккаунт.
    // Именно поэтому /otp/send всегда отвечает 202: эндпоинт не должен выдавать,
    // зарегистрирован ли адрес (user enumeration).
    public void send(String email, OtpType type, String ip) {
        String normalized = normalize(email);
        rateLimitService.checkEmail(normalized);
        rateLimitService.checkIp(ip);

        String code = generateCode();
        String key = KEY_PREFIX + normalized;

        redis.execute(
                STORE_OTP,
                List.of(key),
                TokenHasher.sha256Hex(code),
                String.valueOf(properties.otp().ttl().toSeconds()));

        // Временно: код уходит в лог. Реальная доставка — с Notification Service (§9).
        log.info("OTP for {} (type={}): {}", normalized, type, code);
    }

    // Проверка кода. Возвращает нормализованный email при успехе.
    // Порядок проверок соответствует §6:
    //   нет ключа            → 400
    //   attempts >= max      → удалить ключ, 429
    //   код не совпал        → attempts++, 400
    //   совпал               → удалить ключ (одноразовость), успех
    public String verify(String email, String code) {
        String normalized = normalize(email);
        String key = KEY_PREFIX + normalized;

        Map<Object, Object> entry = redis.opsForHash().entries(key);
        if (entry.isEmpty()) {
            throw new InvalidOtpException();
        }

        int attempts = Integer.parseInt(String.valueOf(entry.get(FIELD_ATTEMPTS)));
        if (attempts >= properties.otp().maxAttempts()) {
            // Удаляем ключ целиком, а не просто отказываем: иначе атакующий запросил бы
            // новый код и продолжил перебор с тем же результатом.
            redis.delete(key);
            throw new OtpAttemptsExceededException();
        }

        String storedHash = String.valueOf(entry.get(FIELD_CODE_HASH));
        // Константно-временное сравнение — не даём подобрать хеш по времени ответа.
        if (!TokenHasher.constantTimeEquals(storedHash, TokenHasher.sha256Hex(code))) {
            redis.opsForHash().increment(key, FIELD_ATTEMPTS, 1);
            throw new InvalidOtpException();
        }

        // Успех — код одноразовый, удаляем немедленно.
        redis.delete(key);
        return normalized;
    }

    private String generateCode() {
        int length = properties.otp().codeLength();
        int bound = (int) Math.pow(10, length);       // codeLength=6 → 000000..999999
        int number = secureRandom.nextInt(bound);
        return String.format("%0" + length + "d", number);
    }

    private String normalize(String email) {
        return email.trim().toLowerCase();
    }
}

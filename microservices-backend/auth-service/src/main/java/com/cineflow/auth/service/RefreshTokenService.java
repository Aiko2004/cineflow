package com.cineflow.auth.service;

import com.cineflow.auth.config.AuthProperties;
import com.cineflow.auth.exception.InvalidRefreshTokenException;
import com.cineflow.auth.model.RefreshToken;
import com.cineflow.auth.repository.RefreshTokenRepository;
import com.cineflow.auth.util.TokenHasher;
import com.github.f4b6a3.uuid.UuidCreator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.UUID;

// Жизненный цикл refresh-токенов: выпуск, ротация с обнаружением кражи, отзыв.
//
// Ротация (AUTH_DESIGN.md §7): каждый refresh одноразовый. При обновлении старый
// помечается revoked, выдаётся новый с тем же family_id. Если предъявляют уже
// отозванный токен — это либо кража, либо гонка; различить нельзя, поэтому отзывается
// вся family (и вор, и владелец разлогиниваются; владелец войдёт заново по коду).
@Service
@Slf4j
@RequiredArgsConstructor
public class RefreshTokenService {

    private final RefreshTokenRepository repository;
    private final AuthProperties properties;
    private final PlatformTransactionManager txManager;
    private final SecureRandom secureRandom = new SecureRandom();

    // Результат ротации: кому выдан токен и новый сырой refresh для клиента.
    public record Rotation(UUID userId, String newRefreshToken) {}

    // Выпуск нового refresh-токена в указанной family. Возвращает СЫРОЙ токен —
    // в БД уходит только его хеш. Сырое значение существует лишь в этом ответе.
    @Transactional
    public String issue(UUID userId, UUID familyId) {
        String raw = generateRawToken();

        RefreshToken token = new RefreshToken();
        token.setId(UuidCreator.getTimeOrderedEpoch());
        token.setUserId(userId);
        token.setTokenHash(TokenHasher.sha256Hex(raw));
        token.setFamilyId(familyId);
        token.setExpiresAt(OffsetDateTime.now().plus(properties.jwt().refreshTtl()));
        repository.save(token);

        return raw;
    }

    @Transactional
    public Rotation rotate(String rawToken) {
        RefreshToken token = repository.findByTokenHash(TokenHasher.sha256Hex(rawToken))
                .orElseThrow(InvalidRefreshTokenException::new);

        if (token.isExpired()) {
            throw new InvalidRefreshTokenException();
        }

        // Одноразовость + защита от гонки: атомарно отзываем ИМЕННО этот токен через
        // условный UPDATE (revoked_at IS NULL). Проверку isRevoked() в Java не используем —
        // она читает устаревший снимок и допускает гонку (два параллельных rotate() обоих
        // проходят). Здесь арбитр — БД: ровно один UPDATE затронет строку.
        int revoked = repository.revokeIfActive(token.getId(), OffsetDateTime.now());
        if (revoked == 0) {
            // 0 строк = токен уже был отозван к моменту нашей записи → повтор или гонка.
            // ТРЕВОГА: отзываем всю family (в отдельной транзакции, чтобы throw её не откатил).
            revokeFamilyInNewTransaction(token.getFamilyId());
            throw new InvalidRefreshTokenException();
        }

        // Мы выиграли право на ротацию — выпускаем новый токен в той же family.
        String newRaw = issue(token.getUserId(), token.getFamilyId());
        return new Rotation(token.getUserId(), newRaw);
    }

    // Отзыв family — в ОТДЕЛЬНОЙ транзакции (REQUIRES_NEW). Иначе throw InvalidRefreshTokenException
    // сразу после вызова откатил бы вместе с внешней транзакцией и этот отзыв — тревога не сработала бы.
    private void revokeFamilyInNewTransaction(UUID familyId) {
        var tx = new TransactionTemplate(txManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            int revoked = repository.revokeFamily(familyId, OffsetDateTime.now());
            log.warn("Refresh token reuse detected for family {} — revoked {} active token(s)",
                    familyId, revoked);
        });
    }

    // Logout идемпотентен: нет токена или уже отозван — молча выходим (контроллер вернёт 204).
    // Отзываем только предъявленный токен, не всю family: это штатный выход с одного
    // устройства, а не инцидент.
    @Transactional
    public void logout(String rawToken) {
        repository.findByTokenHash(TokenHasher.sha256Hex(rawToken))
                .filter(t -> !t.isRevoked())
                .ifPresent(t -> {
                    t.setRevokedAt(OffsetDateTime.now());
                    repository.save(t);
                });
    }

    // 256 бит энтропии из SecureRandom → base64url без паддинга. Это и есть refresh-токен.
    private String generateRawToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}

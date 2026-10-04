package com.cineflow.auth.repository;

import com.cineflow.auth.model.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    // Условный атомарный отзыв одного токена: помечаем revoked ТОЛЬКО если он ещё активен.
    // Возврат 1 — мы успели первыми (легитимная ротация). Возврат 0 — кто-то уже отозвал
    // его между нашим чтением и записью: повторное использование или конкурентная гонка.
    //
    // Именно этот WHERE revoked_at IS NULL закрывает race: два параллельных rotate() одного
    // токена сериализуются на блокировке строки; после коммита первого второй UPDATE
    // перепроверяет условие по актуальной версии строки (Postgres EvalPlanQual при
    // READ COMMITTED) → 0 строк. Проверка isRevoked() в Java этого не давала — она читала
    // устаревший снимок до записи конкурента.
    @Modifying
    @Query("""
            UPDATE RefreshToken rt
               SET rt.revokedAt = :now
             WHERE rt.id = :id
               AND rt.revokedAt IS NULL
            """)
    int revokeIfActive(@Param("id") UUID id, @Param("now") OffsetDateTime now);

    // Отзыв всей family одним UPDATE — при обнаружении повторного использования (§7).
    // Трогаем только ещё активные токены (revoked_at IS NULL), чтобы не перезаписывать
    // время у уже отозванных.
    @Modifying
    @Query("""
            UPDATE RefreshToken rt
               SET rt.revokedAt = :now
             WHERE rt.familyId = :familyId
               AND rt.revokedAt IS NULL
            """)
    int revokeFamily(@Param("familyId") UUID familyId, @Param("now") OffsetDateTime now);
}

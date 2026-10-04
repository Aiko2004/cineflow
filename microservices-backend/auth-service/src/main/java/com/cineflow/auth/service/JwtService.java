package com.cineflow.auth.service;

import com.cineflow.auth.config.AuthProperties;
import com.cineflow.auth.model.User;
import com.github.f4b6a3.uuid.UuidCreator;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

// Выпуск access-токенов (RS256) и раздача публичного ключа через JWKS.
//
// Подпись асимметричная (RS256): приватный ключ только здесь, ресурсные сервисы
// проверяют публичным из JWKS. При HS256 любой, кто умеет проверять токен, умел бы
// и подделать его (см. AUTH_DESIGN.md §4).
@Service
@Slf4j
public class JwtService {

    private final AuthProperties.Jwt props;
    private final RSAKey rsaKey;          // приватный ключ (с публичной частью)
    private final RSASSASigner signer;

    public JwtService(AuthProperties properties) throws JOSEException {
        this.props = properties.jwt();
        // Ключ генерируется при старте. Для прода это открытый вопрос (AUTH_DESIGN.md §9):
        // после перезапуска все выданные токены станут невалидны, а несколько экземпляров
        // сервиса подпишут разными ключами. Нужен внешний источник ключа.
        this.rsaKey = new RSAKeyGenerator(2048)
                .keyID(props.keyId())
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.RS256)
                .generate();
        this.signer = new RSASSASigner(rsaKey);
        log.warn("JWT signing key generated in-memory (kid={}). Tokens are invalidated on restart — "
                + "provide an external key before production.", props.keyId());
    }

    public String issueAccessToken(User user) {
        Instant now = Instant.now();
        Instant exp = now.plus(props.accessTtl());

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(props.issuer())
                .subject(user.getId().toString())
                .audience(props.audience())
                .claim("email", user.getEmail())
                // роль в БД хранится как "USER" → в токене префикс ROLE_ (конвенция Spring Security)
                .claim("roles", List.of("ROLE_" + user.getRole()))
                // jti: уникальный id токена — задел под чёрный список, если понадобится
                .jwtID(UuidCreator.getTimeOrderedEpoch().toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(exp))
                .build();

        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256)
                        .keyID(props.keyId())        // kid → ресурсный сервер выберет нужный ключ из JWKS
                        .type(JOSEObjectType.JWT)
                        .build(),
                claims);
        try {
            jwt.sign(signer);
        } catch (JOSEException e) {
            throw new IllegalStateException("Failed to sign JWT", e);
        }
        return jwt.serialize();
    }

    public long accessTtlSeconds() {
        return props.accessTtl().toSeconds();
    }

    // Только публичная часть — приватный ключ наружу не уходит.
    public JWKSet publicJwkSet() {
        return new JWKSet(rsaKey.toPublicJWK());
    }

    // Готовый JSON для эндпоинта /.well-known/jwks.json.
    public Map<String, Object> jwkSetJson() {
        return publicJwkSet().toJSONObject();
    }
}

package com.cineflow.auth;

import com.cineflow.auth.config.AuthProperties;
import com.cineflow.auth.model.User;
import com.cineflow.auth.service.JwtService;
import com.github.f4b6a3.uuid.UuidCreator;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.BadJWTException;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Юнит-тесты выпуска и проверки access-токенов. Без Spring-контекста и Docker:
// JwtService самодостаточен (генерирует ключ в конструкторе), проверку делаем
// «настоящим» ресурсным-серверным путём — через Nimbus DefaultJWTProcessor поверх JWKS.
class JwtServiceTest {

    private static final String ISSUER = "https://auth.cineflow.local";
    private static final String AUDIENCE = "cineflow-api";

    private static AuthProperties props(Duration accessTtl, String kid) {
        return new AuthProperties(
                new AuthProperties.Jwt(ISSUER, AUDIENCE, accessTtl, Duration.ofDays(30), kid),
                null, null);
    }

    private static User user() {
        User u = new User();
        u.setId(UuidCreator.getTimeOrderedEpoch());
        u.setEmail("user@example.com");
        u.setRole("USER");
        return u;
    }

    // Процессор проверки: подпись по JWKS сервиса + обязательные claim'ы + issuer + exp.
    // Именно так токен валидирует ресурсный сервер (Spring под капотом делает то же).
    private static DefaultJWTProcessor<SecurityContext> processor(JwtService service) {
        DefaultJWTProcessor<SecurityContext> proc = new DefaultJWTProcessor<>();
        proc.setJWSKeySelector(new JWSVerificationKeySelector<>(
                JWSAlgorithm.RS256, new ImmutableJWKSet<>(service.publicJwkSet())));
        proc.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<>(
                new JWTClaimsSet.Builder().issuer(ISSUER).build(),
                Set.of("sub", "iat", "exp")));
        return proc;
    }

    @Test
    void issuedToken_passesValidation_andCarriesExpectedClaims() throws Exception {
        JwtService service = new JwtService(props(Duration.ofMinutes(15), "kid-1"));
        User user = user();

        String token = service.issueAccessToken(user);
        JWTClaimsSet claims = processor(service).process(token, null);

        assertThat(claims.getIssuer()).isEqualTo(ISSUER);
        assertThat(claims.getSubject()).isEqualTo(user.getId().toString());
        assertThat(claims.getAudience()).containsExactly(AUDIENCE);
        assertThat(claims.getStringClaim("email")).isEqualTo("user@example.com");
        assertThat(claims.getStringListClaim("roles")).containsExactly("ROLE_USER");
        assertThat(claims.getJWTID()).isNotBlank();
    }

    @Test
    void expiredToken_isRejected() throws Exception {
        // accessTtl отрицательный → exp в прошлом (за пределами допустимого clock skew).
        JwtService service = new JwtService(props(Duration.ofSeconds(-120), "kid-1"));

        String token = service.issueAccessToken(user());

        assertThatThrownBy(() -> processor(service).process(token, null))
                .isInstanceOf(BadJWTException.class)
                .hasMessageContaining("Expired");
    }

    @Test
    void tokenWithForeignSignature_isRejected() throws Exception {
        // Токен подписан ключом одного сервиса, проверяется по JWKS другого.
        // Это моделирует и подделку подписи, и попытку самому выписать токен без ключа auth.
        JwtService issuer = new JwtService(props(Duration.ofMinutes(15), "kid-1"));
        JwtService verifier = new JwtService(props(Duration.ofMinutes(15), "kid-1"));

        String token = issuer.issueAccessToken(user());

        assertThatThrownBy(() -> processor(verifier).process(token, null))
                .isInstanceOf(BadJOSEException.class);
    }

    @Test
    void jwkSet_exposesOnlyPublicMaterial() throws Exception {
        JwtService service = new JwtService(props(Duration.ofMinutes(15), "kid-1"));

        // Приватная часть (private exponent 'd') не должна утекать в JWKS.
        assertThat(service.jwkSetJson().toString()).doesNotContain("\"d\"");
        assertThat(service.publicJwkSet().getKeys()).hasSize(1);
    }
}

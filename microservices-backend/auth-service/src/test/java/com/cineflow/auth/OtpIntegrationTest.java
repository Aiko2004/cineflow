package com.cineflow.auth;

import com.cineflow.auth.dto.OtpType;
import com.cineflow.auth.exception.InvalidOtpException;
import com.cineflow.auth.exception.OtpAttemptsExceededException;
import com.cineflow.auth.exception.RateLimitExceededException;
import com.cineflow.auth.service.OtpService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// OTP: одноразовость кода, лимит попыток, оба rate limit (email и IP).
// Значения лимитов берутся из application.yaml (email=5, ip=20, max-attempts=5).
class OtpIntegrationTest extends AbstractAuthIntegrationTest {

    @Autowired
    OtpService otpService;

    @BeforeEach
    void setUp() {
        flushRedis();
    }

    @Test
    void correctCode_succeeds_andIsSingleUse() {
        seedOtp("alice@example.com", "123456", 0);

        // Первый ввод верного кода — успех, возвращается нормализованный email.
        assertThat(otpService.verify("Alice@Example.com", "123456"))
                .isEqualTo("alice@example.com");

        // Повторный ввод того же кода — ключ уже удалён (одноразовость) → 400.
        assertThatThrownBy(() -> otpService.verify("alice@example.com", "123456"))
                .isInstanceOf(InvalidOtpException.class);
    }

    @Test
    void wrongCode_exhaustsAttempts_thenLocksOut() {
        seedOtp("bob@example.com", "123456", 0);

        // 5 неверных попыток — каждая 400 (InvalidOtp).
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> otpService.verify("bob@example.com", "000000"))
                    .as("attempt %d", i + 1)
                    .isInstanceOf(InvalidOtpException.class);
        }

        // 6-я попытка — лимит исчерпан: ключ удаляется, 429.
        assertThatThrownBy(() -> otpService.verify("bob@example.com", "000000"))
                .isInstanceOf(OtpAttemptsExceededException.class);

        // Даже верный код после лок-аута не сработает — ключа больше нет.
        assertThatThrownBy(() -> otpService.verify("bob@example.com", "123456"))
                .isInstanceOf(InvalidOtpException.class);
    }

    @Test
    void emailRateLimit_blocksAfterMax() {
        String email = "carol@example.com";
        String ip = "10.0.0.1";

        // Лимит по email = 5: первые 5 запросов проходят.
        for (int i = 0; i < 5; i++) {
            int n = i + 1;
            assertThatCode(() -> otpService.send(email, OtpType.EMAIL, ip))
                    .as("send %d", n)
                    .doesNotThrowAnyException();
        }

        // 6-й запрос на тот же email — 429.
        assertThatThrownBy(() -> otpService.send(email, OtpType.EMAIL, ip))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void ipRateLimit_blocksAfterMax_acrossDifferentEmails() {
        String ip = "203.0.113.7";

        // Меняем email каждый раз, чтобы не упереться в лимит по email — проверяем именно IP.
        // Лимит по IP = 20: первые 20 запросов проходят.
        for (int i = 0; i < 20; i++) {
            int n = i;
            assertThatCode(() -> otpService.send("user" + n + "@example.com", OtpType.EMAIL, ip))
                    .as("send %d", n + 1)
                    .doesNotThrowAnyException();
        }

        // 21-й запрос с того же IP — 429, хотя email новый.
        assertThatThrownBy(() -> otpService.send("user999@example.com", OtpType.EMAIL, ip))
                .isInstanceOf(RateLimitExceededException.class);
    }
}

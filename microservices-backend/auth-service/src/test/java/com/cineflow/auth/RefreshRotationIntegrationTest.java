package com.cineflow.auth;

import com.cineflow.auth.dto.OtpType;
import com.cineflow.auth.dto.TokenResponse;
import com.cineflow.auth.exception.InvalidRefreshTokenException;
import com.cineflow.auth.model.User;
import com.cineflow.auth.repository.RefreshTokenRepository;
import com.cineflow.auth.repository.UserRepository;
import com.cineflow.auth.service.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Refresh-ротация: одноразовость, продолжение цепочки и обнаружение повторного
// использования с отзывом всей family (AUTH_DESIGN.md §7).
class RefreshRotationIntegrationTest extends AbstractAuthIntegrationTest {

    @Autowired AuthService authService;
    @Autowired UserRepository userRepository;
    @Autowired RefreshTokenRepository refreshTokenRepository;

    @BeforeEach
    void setUp() {
        flushRedis();
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    private TokenResponse login(String email, String code) {
        seedOtp(email, code, 0);
        return authService.verifyAndLogin(email, code, OtpType.EMAIL);
    }

    @Test
    void verifyLogin_createsUser_andIssuesPair() {
        TokenResponse tokens = login("dave@example.com", "111111");

        assertThat(tokens.accessToken()).isNotBlank();
        assertThat(tokens.refreshToken()).isNotBlank();
        assertThat(tokens.expiresIn()).isEqualTo(900);   // access TTL = 15 мин

        User user = userRepository.findByEmail("dave@example.com").orElseThrow();
        assertThat(user.getRole()).isEqualTo("USER");
    }

    @Test
    void refresh_rotatesToken_andChainContinues() {
        TokenResponse t1 = login("erin@example.com", "222222");

        TokenResponse t2 = authService.refresh(t1.refreshToken());
        TokenResponse t3 = authService.refresh(t2.refreshToken());

        // Каждая ротация выдаёт новый refresh — старый больше не используется.
        assertThat(t2.refreshToken()).isNotEqualTo(t1.refreshToken());
        assertThat(t3.refreshToken()).isNotEqualTo(t2.refreshToken());
        assertThat(t3.accessToken()).isNotBlank();
    }

    @Test
    void reuseOfRotatedToken_revokesEntireFamily() {
        TokenResponse t1 = login("frank@example.com", "333333");

        // Штатная ротация: t1 → t2. Теперь t1 отозван.
        TokenResponse t2 = authService.refresh(t1.refreshToken());

        // Повторное предъявление уже отозванного t1 — тревога: отзывается вся family.
        assertThatThrownBy(() -> authService.refresh(t1.refreshToken()))
                .isInstanceOf(InvalidRefreshTokenException.class);

        // t2 был валиден, но принадлежит той же family → тоже отозван.
        assertThatThrownBy(() -> authService.refresh(t2.refreshToken()))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    // Гонка: два параллельных rotate() ОДНОГО refresh-токена. Это сигнатура кражи
    // (владелец и вор обновляются почти одновременно). Корректное поведение:
    // ровно один rotate успешен, второй ловит повтор и отзывает всю family — в итоге
    // даже свежий токен победителя мёртв, оба должны залогиниться заново.
    //
    // На старом коде (read → isRevoked() → save, без атомарности) оба потока читают
    // токен как активный и оба успешно обновляются → два валидных токена, повтор не пойман.
    @Test
    void concurrentRotate_onlyOneSucceeds_otherRevokesFamily() throws Exception {
        TokenResponse t1 = login("harry@example.com", "555555");

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads); // оба стартуют одновременно

        List<Future<TokenResponse>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Callable<TokenResponse> task = () -> {
                barrier.await();
                return authService.refresh(t1.refreshToken());
            };
            futures.add(pool.submit(task));
        }

        int succeeded = 0;
        int invalid = 0;
        TokenResponse winner = null;
        for (Future<TokenResponse> f : futures) {
            try {
                winner = f.get(10, TimeUnit.SECONDS);
                succeeded++;
            } catch (ExecutionException ex) {
                assertThat(ex.getCause()).isInstanceOf(InvalidRefreshTokenException.class);
                invalid++;
            }
        }
        pool.shutdownNow();

        assertThat(succeeded).as("успешным должен быть ровно один rotate").isEqualTo(1);
        assertThat(invalid).as("второй rotate должен поймать повтор").isEqualTo(1);

        // Победитель получил новый токен, но проигравший отозвал family → он тоже мёртв.
        TokenResponse finalWinner = winner;
        assertThatThrownBy(() -> authService.refresh(finalWinner.refreshToken()))
                .as("family должна быть отозвана целиком")
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void logout_revokesToken_soRefreshFails() {
        TokenResponse t1 = login("gina@example.com", "444444");

        authService.logout(t1.refreshToken());

        assertThatThrownBy(() -> authService.refresh(t1.refreshToken()))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }
}

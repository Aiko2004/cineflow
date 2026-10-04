package com.cineflow.auth.service;

import com.cineflow.auth.dto.OtpType;
import com.cineflow.auth.dto.TokenResponse;
import com.cineflow.auth.exception.InvalidRefreshTokenException;
import com.cineflow.auth.model.User;
import com.cineflow.auth.repository.UserRepository;
import com.github.f4b6a3.uuid.UuidCreator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Оркестрация сценариев входа. OTP-проверка, refresh-ротация и подпись токенов
// делегируются специализированным сервисам — здесь только связывание.
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final String DEFAULT_ROLE = "USER";

    private final OtpService otpService;
    private final RefreshTokenService refreshTokenService;
    private final JwtService jwtService;
    private final UserRepository userRepository;

    // Запрос кода. Всегда 202 (см. OtpService.send / AUTH_DESIGN.md §6).
    public void sendOtp(String email, OtpType type, String ip) {
        otpService.send(email, type, ip);
    }

    // Проверка кода → вход. Пользователь создаётся при первом успешном входе
    // (неявная регистрация). Каждый вход открывает НОВУЮ family.
    @Transactional
    public TokenResponse verifyAndLogin(String email, String code, OtpType type) {
        String normalizedEmail = otpService.verify(email, code);

        User user = userRepository.findByEmail(normalizedEmail)
                .orElseGet(() -> createUser(normalizedEmail));

        String refreshToken = refreshTokenService.issue(user.getId(), UuidCreator.getTimeOrderedEpoch());
        String accessToken = jwtService.issueAccessToken(user);

        return new TokenResponse(accessToken, refreshToken, jwtService.accessTtlSeconds());
    }

    // Обновление пары. Ротация (с обнаружением кражи) — в RefreshTokenService.
    @Transactional
    public TokenResponse refresh(String refreshToken) {
        RefreshTokenService.Rotation rotation = refreshTokenService.rotate(refreshToken);

        User user = userRepository.findById(rotation.userId())
                .orElseThrow(InvalidRefreshTokenException::new);

        String accessToken = jwtService.issueAccessToken(user);
        return new TokenResponse(accessToken, rotation.newRefreshToken(), jwtService.accessTtlSeconds());
    }

    public void logout(String refreshToken) {
        refreshTokenService.logout(refreshToken);
    }

    private User createUser(String email) {
        User user = new User();
        user.setId(UuidCreator.getTimeOrderedEpoch());
        user.setEmail(email);
        user.setRole(DEFAULT_ROLE);
        return userRepository.save(user);
    }
}

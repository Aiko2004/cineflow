package com.cineflow.auth.controller;

import com.cineflow.auth.config.AuthProperties;
import com.cineflow.auth.dto.LogoutRequest;
import com.cineflow.auth.dto.OtpSendRequest;
import com.cineflow.auth.dto.OtpSendResponse;
import com.cineflow.auth.dto.OtpVerifyRequest;
import com.cineflow.auth.dto.RefreshRequest;
import com.cineflow.auth.dto.TokenResponse;
import com.cineflow.auth.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final AuthProperties properties;

    // 202 Accepted всегда: приняли заявку, но не подтверждаем существование адреса
    // (иначе эндпоинт стал бы инструментом user enumeration). См. AUTH_DESIGN.md §6.
    @PostMapping("/otp/send")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public OtpSendResponse sendOtp(@Valid @RequestBody OtpSendRequest request,
                                   HttpServletRequest http) {
        authService.sendOtp(request.email(), request.type(), clientIp(http));
        return new OtpSendResponse(properties.otp().ttl().toSeconds());
    }

    @PostMapping("/otp/verify")
    public TokenResponse verifyOtp(@Valid @RequestBody OtpVerifyRequest request) {
        return authService.verifyAndLogin(request.email(), request.code(), request.type());
    }

    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return authService.refresh(request.refreshToken());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody LogoutRequest request) {
        authService.logout(request.refreshToken());
    }

    // За Gateway реальный адрес клиента приходит в X-Forwarded-For (первый в списке).
    // Без прокси берём remoteAddr. Rate limit по IP опирается на это значение.
    private String clientIp(HttpServletRequest http) {
        String forwarded = http.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) {
            return forwarded.split(",")[0].trim();
        }
        return http.getRemoteAddr();
    }
}

package com.cineflow.payment.controller;

import com.cineflow.payment.dto.PaymentResponse;
import com.cineflow.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

// Публичная (через gateway) часть API: статус платежа владельцу.
@RestController
@RequestMapping("/payment")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @GetMapping("/{id}")
    public PaymentResponse getById(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return paymentService.getOwned(UUID.fromString(jwt.getSubject()), id);
    }
}

package com.cineflow.payment.controller;

import com.cineflow.payment.dto.CreateInvoiceRequest;
import com.cineflow.payment.dto.InvoiceResponse;
import com.cineflow.payment.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

// ВНУТРЕННИЙ эндпоинт: его вызывает booking-service, не пользователь. Gateway путь /internal/**
// не маршрутизирует (маршрут только /payment/**), поэтому снаружи он недоступен.
//
// Это граница доверия — как gRPC-порт 9083: сумма (amount) приходит ОТ ВЫЗЫВАЮЩЕГО. Будь
// эндпоинт доступен клиенту, пользователь выставил бы себе счёт на 1 тенге. Ограничения этой
// защиты (нет межсервисной аутентификации) описаны в PAYMENT_DESIGN.md §7.
@RestController
@RequestMapping("/internal/payments")
@RequiredArgsConstructor
public class InternalPaymentController {

    private final PaymentService paymentService;

    // 201 на любой успешный ответ, включая идемпотентный повтор: вызывающему важен результат
    // («счёт есть»), а не то, создан он сейчас или раньше.
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public InvoiceResponse createInvoice(@AuthenticationPrincipal Jwt jwt,
                                         @Valid @RequestBody CreateInvoiceRequest request) {
        // user_id — ТОЛЬКО из claim sub токена (booking пробрасывает токен пользователя).
        return paymentService.createInvoice(UUID.fromString(jwt.getSubject()), request);
    }
}

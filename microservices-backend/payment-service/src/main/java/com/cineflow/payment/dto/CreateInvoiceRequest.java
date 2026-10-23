package com.cineflow.payment.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.util.UUID;

// Внутренний запрос «выставить счёт» (POST /internal/payments). user_id в теле НЕТ — только
// из claim sub токена. amount приходит от вызывающего сервиса (booking), поэтому эндпоинт
// и закрыт от внешнего мира на уровне маршрутизации (PAYMENT_DESIGN.md §7).
public record CreateInvoiceRequest(
        @NotNull UUID bookingId,
        @NotNull @DecimalMin(value = "0.01") @Digits(integer = 8, fraction = 2) BigDecimal amount,
        // Казахстанский/международный номер: необязательный +, 10–15 цифр.
        @NotNull @Pattern(regexp = "^\\+?[0-9]{10,15}$", message = "must be 10-15 digits, optional leading +")
        String phone
) {
}

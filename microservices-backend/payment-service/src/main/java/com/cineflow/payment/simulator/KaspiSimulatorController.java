package com.cineflow.payment.simulator;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// Dev-эндпоинты симулятора — «кнопки в приложении Kaspi» (PAYMENT_DESIGN.md §6). Существуют
// только в профиле kaspi-sim: без него бин не создаётся, путь отвечает 404.
//
// Требуют токен (как любой непубличный путь сервиса, SecurityConfig). Владелец счёта не
// проверяется — это имитация чужого приложения, а не наш API.
//
// 202 Accepted: статус счёта у «провайдера» уже сменился, а webhook будет доставлен асинхронно.
@RestController
@Profile("kaspi-sim")
@RequestMapping("/payment/sim/invoices")
@RequiredArgsConstructor
public class KaspiSimulatorController {

    private final KaspiSimulatorApp app;

    @PostMapping("/{invoiceId}/pay")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public SimInvoiceActionResponse pay(@PathVariable String invoiceId,
                                        @RequestParam(defaultValue = "0") @Min(0) @Max(3600) int delaySeconds) {
        return app.pay(invoiceId, delaySeconds);
    }

    @PostMapping("/{invoiceId}/decline")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public SimInvoiceActionResponse decline(@PathVariable String invoiceId) {
        return app.decline(invoiceId);
    }
}

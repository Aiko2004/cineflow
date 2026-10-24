package com.cineflow.payment.simulator;

import lombok.Getter;
import org.springframework.http.HttpStatus;

// Отказ dev-эндпоинта симулятора: счёта нет (404) или его нельзя оплатить/отклонить (409 —
// уже оплачен, отменён, истёк). Живёт в пакете симулятора и обрабатывается его же advice:
// в профиле без kaspi-sim этих классов в работе нет.
@Getter
public class SimulatorRequestException extends RuntimeException {

    private final HttpStatus status;

    public SimulatorRequestException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }
}

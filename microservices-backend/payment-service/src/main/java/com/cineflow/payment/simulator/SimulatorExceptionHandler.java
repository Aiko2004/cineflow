package com.cineflow.payment.simulator;

import com.cineflow.payment.dto.ErrorResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

// Ошибки dev-эндпоинтов симулятора — в том же формате ErrorResponse, что и остальной API.
@RestControllerAdvice(assignableTypes = KaspiSimulatorController.class)
@Profile("kaspi-sim")
public class SimulatorExceptionHandler {

    @ExceptionHandler(SimulatorRequestException.class)
    public ResponseEntity<ErrorResponse> handle(SimulatorRequestException ex) {
        return ResponseEntity.status(ex.getStatus())
                .body(new ErrorResponse(ex.getStatus().value(), ex.getMessage()));
    }
}

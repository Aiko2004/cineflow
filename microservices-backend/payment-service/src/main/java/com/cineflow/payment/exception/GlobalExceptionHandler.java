package com.cineflow.payment.exception;

import com.cineflow.payment.dto.ErrorResponse;
import com.cineflow.payment.gateway.PaymentProviderException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(PaymentNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(PaymentNotFoundException ex) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(PaymentConflictException.class)
    public ResponseEntity<ErrorResponse> handleConflict(PaymentConflictException ex) {
        return build(HttpStatus.CONFLICT, ex.getMessage());
    }

    // Провайдер недоступен: платёж остался PENDING без счёта, повтор запроса безопасен.
    @ExceptionHandler(PaymentProviderException.class)
    public ResponseEntity<ErrorResponse> handleProvider(PaymentProviderException ex) {
        return build(HttpStatus.SERVICE_UNAVAILABLE, "Payment provider is unavailable");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return build(HttpStatus.BAD_REQUEST, message);
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(new ErrorResponse(status.value(), message));
    }
}

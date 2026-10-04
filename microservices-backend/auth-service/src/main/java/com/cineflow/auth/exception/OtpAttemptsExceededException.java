package com.cineflow.auth.exception;

// 429. Исчерпан лимит попыток ввода кода. Ключ при этом удаляется целиком,
// чтобы атакующий не мог продолжить перебор, просто запросив новый код (§6).
public class OtpAttemptsExceededException extends RuntimeException {
    public OtpAttemptsExceededException() {
        super("Too many attempts, request a new code");
    }
}

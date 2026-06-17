package com.geminibank.ledger;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Translates domain exceptions into the {@code {"error": ...}} JSON responses. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(LedgerException.class)
    public ResponseEntity<Map<String, String>> handleLedger(LedgerException ex) {
        return ResponseEntity.status(ex.status()).body(Map.of("error", ex.error()));
    }
}

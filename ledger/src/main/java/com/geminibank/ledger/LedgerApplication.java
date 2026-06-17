package com.geminibank.ledger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Ledger service — owns money.
 *
 * Java/Spring Boot 3 rewrite of the original Flask service. Same port (8082),
 * same REST endpoints, and the same request/response JSON shapes so the Python
 * {@code statements} and {@code frontend} services keep working unchanged.
 */
@SpringBootApplication
public class LedgerApplication {
    public static void main(String[] args) {
        SpringApplication.run(LedgerApplication.class, args);
    }
}

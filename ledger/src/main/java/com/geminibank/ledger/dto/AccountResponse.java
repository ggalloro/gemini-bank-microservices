package com.geminibank.ledger.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * JSON shape for an account. Money is serialized as a decimal (cents / 100.0)
 * to match the original Flask output exactly (e.g. 0.0, 250.0, 75.5).
 */
@JsonPropertyOrder({"id", "user_id", "iban", "name", "balance", "created_at"})
public record AccountResponse(
        long id,
        long user_id,
        String iban,
        String name,
        double balance,
        String created_at) {
}

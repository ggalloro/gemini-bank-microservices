package com.geminibank.ledger.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * JSON shape for a transaction, consumed by the Python statements service.
 * Fields counterparty/description may be null and are still emitted (matching
 * Flask's jsonify, which renders missing values as null).
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
@JsonPropertyOrder({"id", "account_id", "type", "amount", "counterparty", "description", "created_at"})
public record TransactionResponse(
        long id,
        long account_id,
        String type,
        double amount,
        String counterparty,
        String description,
        String created_at) {
}

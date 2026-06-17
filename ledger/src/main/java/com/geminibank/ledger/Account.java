package com.geminibank.ledger;

/** Internal account row. Balance is integer cents (as stored). */
public record Account(long id, long userId, String iban, String name, long balanceCents, String createdAt) {
}

package com.geminibank.ledger;

/** Internal transaction row. Amount is integer cents, always positive. */
public record Transaction(long id, long accountId, String type, long amountCents,
                          String counterparty, String description, String createdAt) {
}

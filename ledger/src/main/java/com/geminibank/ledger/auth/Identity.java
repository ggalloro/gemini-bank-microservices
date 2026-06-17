package com.geminibank.ledger.auth;

/** Logical claims carried by the bearer token. */
public record Identity(long userId, String fullName) {
}

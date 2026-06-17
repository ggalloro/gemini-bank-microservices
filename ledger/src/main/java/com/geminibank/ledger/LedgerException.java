package com.geminibank.ledger;

/**
 * Maps directly to an HTTP error response of {@code {"error": <code>}} with the
 * given status, mirroring the Flask service's (status, {"error": ...}) returns.
 */
public class LedgerException extends RuntimeException {
    private final int status;
    private final String error;

    public LedgerException(int status, String error) {
        super(error);
        this.status = status;
        this.error = error;
    }

    public int status() {
        return status;
    }

    public String error() {
        return error;
    }
}

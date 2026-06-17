package com.geminibank.ledger;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Money helpers: parse/validate decimal amounts to integer cents and back. */
public final class Amounts {

    private Amounts() {
    }

    /**
     * Validate a positive 2-digit decimal amount; return integer cents, or
     * {@code null} for zero/negative/malformed/over-2-decimal amounts.
     * Mirrors the Flask {@code parse_amount_cents}.
     */
    public static Long parseCents(Object raw) {
        if (raw == null) {
            return null;
        }
        BigDecimal amount;
        try {
            amount = new BigDecimal(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            return null;
        }
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        // Reject more than 2 decimal places.
        if (amount.scale() > 2) {
            return null;
        }
        return amount.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
    }

    /** Serialize integer cents as a decimal (e.g. 0 -> 0.0, 25000 -> 250.0). */
    public static double toDecimal(long cents) {
        return cents / 100.0;
    }
}

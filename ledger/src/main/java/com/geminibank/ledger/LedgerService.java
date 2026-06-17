package com.geminibank.ledger;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Business logic for accounts, deposits and payments. */
@Service
public class LedgerService {

    private final LedgerRepository repo;

    public LedgerService(LedgerRepository repo) {
        this.repo = repo;
    }

    // Matches Python datetime.now(timezone.utc).isoformat(): 6-digit microseconds and a +00:00 offset.
    private static final DateTimeFormatter ISO_MICROS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSxxx");

    private static String nowIso() {
        return OffsetDateTime.now(ZoneOffset.UTC).format(ISO_MICROS);
    }

    private static String generateIban() {
        // Synthetic IBAN: country code + 20 random digits. Demo only.
        StringBuilder sb = new StringBuilder("DE");
        for (int i = 0; i < 20; i++) {
            sb.append(ThreadLocalRandom.current().nextInt(10));
        }
        return sb.toString();
    }

    /**
     * Pre-commit approval check for a payment.
     *
     * Called on the payment path *before* any funds move. Returns {@code true}
     * (approve) by default; a risk or approval policy can be implemented here to
     * hold or reject a payment before balances change.
     */
    boolean approvePayment(Map<String, Object> payment) {
        return true;
    }

    public Account openAccount(long userId, String name) {
        if (name == null || name.isBlank()) {
            throw new LedgerException(400, "missing_fields");
        }
        // Retry IBAN generation on the (very unlikely) collision.
        for (int i = 0; i < 5; i++) {
            Account created = repo.insertAccount(userId, generateIban(), name.strip(), nowIso());
            if (created != null) {
                return created;
            }
        }
        throw new LedgerException(500, "iban_generation_failed");
    }

    public List<Account> listAccounts(long userId) {
        return repo.findAccountsByUser(userId);
    }

    public Account accountDetail(long accountId) {
        Account account = repo.findAccount(accountId);
        if (account == null) {
            throw new LedgerException(404, "not_found");
        }
        return account;
    }

    @Transactional
    public Transaction deposit(long accountId, Object rawAmount, String description) {
        Long cents = Amounts.parseCents(rawAmount);
        if (cents == null) {
            throw new LedgerException(400, "invalid_amount");
        }
        String desc = blankToNull(description);
        Account account = repo.findAccount(accountId);
        if (account == null) {
            throw new LedgerException(404, "not_found");
        }
        repo.adjustBalance(accountId, cents);
        return repo.insertTransaction(accountId, "deposit", cents, null, desc, nowIso());
    }

    @Transactional
    public Transaction payment(long accountId, Object rawAmount, String description,
                               Map<String, Object> beneficiary) {
        Long cents = Amounts.parseCents(rawAmount);
        if (cents == null) {
            throw new LedgerException(400, "invalid_amount");
        }
        String desc = blankToNull(description);
        if (beneficiary == null) {
            beneficiary = Map.of();
        }
        Object btypeRaw = beneficiary.get("type");
        String btype = btypeRaw == null ? null : btypeRaw.toString();
        if (!"internal".equals(btype) && !"external".equals(btype)) {
            throw new LedgerException(400, "invalid_beneficiary");
        }

        Account source = repo.findAccount(accountId);
        if (source == null) {
            throw new LedgerException(404, "not_found");
        }

        // Resolve the destination for internal transfers up front.
        Account dest = null;
        String counterparty;
        if ("internal".equals(btype)) {
            Object destIdRaw = beneficiary.get("account_id");
            if (destIdRaw == null) {
                throw new LedgerException(400, "invalid_beneficiary");
            }
            long destId = ((Number) destIdRaw).longValue();
            dest = repo.findAccount(destId);
            if (dest == null) {
                throw new LedgerException(404, "beneficiary_not_found");
            }
            if (dest.id() == source.id()) {
                throw new LedgerException(400, "invalid_beneficiary");
            }
            counterparty = dest.iban();
        } else {
            String iban = strip(beneficiary.get("iban"));
            String bname = strip(beneficiary.get("name"));
            if (iban.isEmpty()) {
                throw new LedgerException(400, "invalid_beneficiary");
            }
            counterparty = bname.isEmpty() ? iban : bname;
        }

        // Approval check runs before any funds move.
        if (!approvePayment(Map.of(
                "account_id", accountId,
                "amount", cents,
                "beneficiary", beneficiary,
                "description", desc == null ? "" : desc))) {
            throw new LedgerException(403, "payment_rejected");
        }

        if (cents > source.balanceCents()) {
            throw new LedgerException(422, "insufficient_funds");
        }

        String ts = nowIso();
        // Atomic: debit source; for internal, also credit destination.
        repo.adjustBalance(accountId, -cents);
        Transaction out = repo.insertTransaction(accountId, "payment_out", cents, counterparty, desc, ts);
        if ("internal".equals(btype)) {
            repo.adjustBalance(dest.id(), cents);
            repo.insertTransaction(dest.id(), "payment_in", cents, source.iban(), desc, ts);
        }
        return out;
    }

    public List<Transaction> internalTransactions(long accountId, String from, String to) {
        return repo.findTransactions(accountId, from, to);
    }

    private static String blankToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.strip();
        return t.isEmpty() ? null : t;
    }

    private static String strip(Object o) {
        return o == null ? "" : o.toString().strip();
    }
}

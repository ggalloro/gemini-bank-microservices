package com.geminibank.ledger;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.geminibank.ledger.auth.AuthInterceptor;
import com.geminibank.ledger.auth.Identity;
import com.geminibank.ledger.dto.AccountResponse;
import com.geminibank.ledger.dto.TransactionResponse;

/**
 * REST endpoints for the ledger service. Paths, status codes and JSON shapes
 * match the original Flask implementation byte-for-byte.
 */
@RestController
public class LedgerController {

    private final LedgerService service;

    public LedgerController(LedgerService service) {
        this.service = service;
    }

    @GetMapping("/healthz")
    public Map<String, String> healthz() {
        return Map.of("status", "ok");
    }

    @PostMapping("/accounts")
    public ResponseEntity<AccountResponse> openAccount(
            @RequestBody(required = false) Map<String, Object> body,
            @RequestAttribute(AuthInterceptor.IDENTITY_ATTR) Identity identity) {
        String name = strip(body == null ? null : body.get("name"));
        Account account = service.openAccount(identity.userId(), name);
        return ResponseEntity.status(HttpStatus.CREATED).body(toAccountResponse(account));
    }

    @GetMapping("/accounts")
    public List<AccountResponse> listAccounts(@RequestParam(name = "user_id", required = false) Long userId) {
        if (userId == null) {
            throw new LedgerException(400, "missing_user_id");
        }
        return service.listAccounts(userId).stream().map(LedgerController::toAccountResponse).toList();
    }

    @GetMapping("/accounts/{accountId}")
    public AccountResponse accountDetail(@PathVariable long accountId) {
        return toAccountResponse(service.accountDetail(accountId));
    }

    @PostMapping("/accounts/{accountId}/deposits")
    public ResponseEntity<TransactionResponse> deposit(
            @PathVariable long accountId,
            @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        Transaction tx = service.deposit(accountId, b.get("amount"), asString(b.get("description")));
        return ResponseEntity.status(HttpStatus.CREATED).body(toTransactionResponse(tx));
    }

    @PostMapping("/accounts/{accountId}/payments")
    @SuppressWarnings("unchecked")
    public ResponseEntity<TransactionResponse> payment(
            @PathVariable long accountId,
            @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        Object benObj = b.get("beneficiary");
        Map<String, Object> beneficiary = benObj instanceof Map ? (Map<String, Object>) benObj : null;
        Transaction tx = service.payment(accountId, b.get("amount"), asString(b.get("description")), beneficiary);
        return ResponseEntity.status(HttpStatus.CREATED).body(toTransactionResponse(tx));
    }

    @GetMapping("/internal/transactions")
    public Map<String, Object> internalTransactions(
            @RequestParam(name = "account_id", required = false) Long accountId,
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to) {
        if (accountId == null) {
            throw new LedgerException(400, "missing_account_id");
        }
        List<TransactionResponse> txns = service.internalTransactions(accountId, from, to).stream()
                .map(LedgerController::toTransactionResponse).toList();
        // LinkedHashMap to preserve the field order {account_id, transactions}.
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("account_id", accountId);
        result.put("transactions", txns);
        return result;
    }

    private static AccountResponse toAccountResponse(Account a) {
        return new AccountResponse(a.id(), a.userId(), a.iban(), a.name(),
                Amounts.toDecimal(a.balanceCents()), a.createdAt());
    }

    private static TransactionResponse toTransactionResponse(Transaction t) {
        return new TransactionResponse(t.id(), t.accountId(), t.type(),
                Amounts.toDecimal(t.amountCents()), t.counterparty(), t.description(), t.createdAt());
    }

    private static String strip(Object o) {
        return o == null ? "" : o.toString().strip();
    }

    private static String asString(Object o) {
        return o == null ? null : o.toString();
    }
}

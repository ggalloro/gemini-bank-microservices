# Gemini Bank — Statements Modernization — Technical Specification

> Technical specification for modernizing the legacy `statements` service. This is a
> **contract-preserving re-platform**, not a feature change: nothing the customer sees
> changes. The discipline (characterization-tests-first) is part of the spec.

## Goal

Re-platform the `statements` service from **Python / Flask to Go**, keeping its HTTP
contract **byte-for-byte identical**. The frontend and the ledger are not modified and
must not notice the swap (strangler-fig: replace one service, preserve the contract).

## Why this service

`statements` is read-only reporting with **no database of its own** — it fetches from the
ledger's internal transaction feed and aggregates in memory. It is the codebase's
designated legacy service (legacy style, no tests), which makes it the ideal modernization
target: small surface, no persistence, a clear contract to pin down.

## The contract to preserve

The Go service must listen on **port 8083**, read the ledger base URL from the
**`LEDGER_URL`** environment variable, and expose exactly these endpoints. It sources data
from the ledger's `GET /internal/transactions?account_id=&from=&to=`, which returns
`{"transactions": [ { id, type, amount, counterparty, description, created_at }, … ]}`.
If that call is not HTTP 200, treat it as **no transactions** (empty result, not an error).

### `GET /healthz`
Returns `{"status": "ok"}`.

### `GET /statements/<account_id>?from=&to=&type=`
- Passes `from` / `to` through to the ledger feed when present.
- Optional `type` filters the **detail list** to matching transaction types only.
- Maps each transaction to: `id`, `type`, `amount`, `counterparty`, `description`, and
  **`date`** (sourced from the ledger's `created_at`).
- Response: `{ account_id, from, to, transactions: [ …mapped rows… ], count }`, where
  `from`/`to` echo the query params (may be null) and `count` is the length of the list.

### `GET /statements/<account_id>/summary?months=N`
- `months` defaults to **6**; a non-integer value falls back to **6**.
- Fetches **all** transactions (ignores `from`/`to`/`type`), buckets them by calendar month
  (`YYYY-MM`) using each transaction's `created_at`; transactions with no `created_at` are
  skipped.
- Per bucket: `total_in` = sum of `deposit` + `payment_in` amounts; `total_out` = sum of
  `payment_out` amounts; `net` = `total_in − total_out`; all rounded to 2 decimals; a
  missing amount counts as 0.
- Returns the **most recent N months**, newest first:
  `{ account_id, months: [ { month, total_in, total_out, net }, … ] }`.

These subtle behaviours (the `created_at`→`date` rename, the type filter applying only to
the detail endpoint, summary ignoring date filters, the rounding and default-6 fallback,
empty-on-ledger-error) are exactly what the characterization tests must lock down.

## Discipline — characterization-tests-first (mandated order)

Do these in order; do not start the rewrite before the tests are green against the old service.

1. **Characterize the live legacy service.** With the existing Python `statements` running
   (and the ledger seeded with data), **record its real responses as golden fixtures** for a
   representative set of requests (both endpoints; with/without `type`; varying `months`;
   an unknown account; date-filtered ranges). Write **language-agnostic HTTP tests** that
   replay those requests against the running service on `:8083` and assert the responses
   equal the recorded fixtures. **Show them green against the old Python service.**
2. **Rewrite to Go.** Implement the contract above; new Dockerfile; swap the `statements`
   service image/build in `docker-compose.yml` (same service name, same port 8083, same
   `LEDGER_URL` wiring, same healthcheck).
3. **Verify.** Run the **same, unchanged** tests against the new Go service → they must be
   green. Equal fixtures = preserved contract.

The tests assert against responses **captured from the old service**, not against the new
implementation's assumptions — that is what makes the equivalence trustworthy.

## Constraints

- Frontend and ledger are **not modified**.
- Same service name, port (8083), `LEDGER_URL` env var, and a healthcheck in compose.
- No new endpoints, no behaviour changes, **no database** (still stateless aggregation).
- Error/empty behaviour preserved (ledger non-200 → empty result).

## Non-goals

- No new features, pagination, caching, auth, or schema changes.
- No changes to how the ledger stores or exposes transactions.

## Done when

- Golden-fixture characterization tests pass against the **old Python** service.
- The same tests pass against the **new Go** service.
- `docker compose up` brings the full stack healthy; the frontend's statements views are
  unchanged to the user.

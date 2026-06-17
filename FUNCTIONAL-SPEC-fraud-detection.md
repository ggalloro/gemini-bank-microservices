# Gemini Bank — Fraud Protection — Functional Specification

> Functional specification for adding AI-driven fraud protection to Gemini Bank. It describes the capability from the customer's point of view. All data used is synthetic.

## Purpose

Gemini Bank today executes every payment that has sufficient funds. We want to protect customers by having **AI assess each payment for fraud risk** before any money moves, **holding suspicious ones for review** instead of letting them through. The AI is guided by the bank's risk criteria (below) and reasons over the full payment context — including the free-text description and beneficiary name — to return a structured verdict. When a payment is held, the customer is told in plain language why, and can resolve it from a simple review screen.

> **How the AI is used:** the risk criteria, fraud taxonomy, and red-flag categories in this spec are supplied to the AI as guidance at assessment time — the application sends the AI the payment together with these criteria, and the AI applies them to reach a verdict. This mirrors the "Truffa o No?" demo, where the analysis criteria travel in the request to the model.

---

## User Journeys

### Journey 1 — A normal payment
1. The customer makes a payment from one of their accounts (to another Gemini Bank customer or to an external beneficiary).
2. The AI assesses it as low risk, so it is approved and completed exactly as before.

### Journey 2 — A suspicious payment is held
1. The customer makes a payment the AI judges risky — by the numbers (e.g. far larger than they normally send) and/or by the language (e.g. an urgent gift-card request, a crypto "opportunity", or pressure to keep it secret).
2. Instead of completing, the payment is **held for review**. No money moves yet.
3. The customer sees a clear "held for review" message with the **risk level**, the **suspected fraud type**, the specific **red flags** found, and a short plain-language **explanation**.

### Journey 3 — Resolving a held payment
1. The customer opens their list of held payments.
2. For each, they see the details, the verdict, and the explanation.
3. The customer can **approve** the payment (it then completes) or **reject** it (it is discarded). *(A simplified stand-in for the bank's internal review console.)*

---

## Features

### AI risk assessment at payment time
Every outgoing payment is assessed by the AI before any money moves. The decision is based only on the **payment itself** (amount, beneficiary, description) and the **current account balance** — not the customer's past transactions or other accounts — together with the bank's risk criteria. The AI examines the **unstructured fields** — the payment description/memo and the beneficiary name — for social-engineering cues, and returns a structured verdict.

### Structured verdict
For each payment the AI returns:
- **Risk level** — one of the *Verdict Levels* below.
- **Action** — `hold` or `approve`, derived from the risk level.
- **Suspected fraud type** — the single most likely category from the *Payment-Fraud Taxonomy* (or "Legitimate" / "Undetermined").
- **Red flags** — zero or more specific signals found, each named (from the *Red-Flag Criteria*) and explained in one or two plain sentences saying why it's suspicious and where it appears.
- **Plain-language explanation** — a short, reassuring explanation for the account owner, not a fraud analyst.

### Held-payments review screen
A screen listing the customer's held payments with their verdict, details, and status, where each can be approved or rejected.

### If the AI is unavailable
If the AI can't return a verdict (timeout, error, missing key), the payment is held for review rather than approved unchecked — external payments are held; internal transfers between Gemini Bank customers may proceed. Protection must never silently switch off.

---

## Verdict Levels

| Risk level | Label | Action | Colour |
|---|---|---|---|
| High | **High risk** | Hold for review | Red |
| Medium | **Suspicious** | Hold for review | Amber |
| Low | **No clear risk** | Approve | Green |

Even at Low, the experience reminds the customer that this is an automated check, not a guarantee.

---

## Payment-Fraud Taxonomy

The AI classifies the payment into the single most likely category:

- **Authorized push payment (APP) fraud** — the customer is tricked into sending money themselves
- **Invoice / mandate redirection** — a "supplier" or "landlord" with changed bank details
- **Impersonation** — someone posing as the bank, police, or a government agency demanding payment
- **Investment / crypto scam** — too-good-to-be-true returns, "act now" pressure
- **Romance scam** — emotional manipulation leading to money requests
- **Purchase / marketplace scam** — payment for goods that won't arrive
- **Money-mule** — moving funds on someone else's behalf
- **Undetermined** — suspicious but no clear category
- **Legitimate** — no fraud indicators

---

## Risk Criteria & Red Flags (supplied to the AI)

The bank's risk policy, passed to the AI as guidance.

**Behavioural signals (from the payment amount and the current balance only):**
- Payment is large relative to the account balance (e.g. more than ~80%).
- A large external payment.

The behavioural signals rely only on the payment and the current balance; the language red flags below carry the rest.

**Contextual / language red flags (from the free-text memo & beneficiary):**
- **Urgency & pressure** — "act now", "within the hour", threats of loss.
- **Secrecy** — "don't tell the bank/anyone", "keep this confidential".
- **Gift cards / vouchers** — requests to pay via gift cards or codes.
- **Investment / crypto bait** — guaranteed or unusually high returns.
- **Impersonation cues** — claims to be the bank's fraud team, police, or a public agency.
- **Romance / emotional manipulation** — a relationship leading to a money request.
- **Unusual beneficiary** — a personal name where a known merchant is expected, or a clearly odd reference.

The AI weighs these together; any can contribute to a `hold`, and the language red flags can hold a payment that looks fine by the numbers alone.

---

## Business Rules

1. The AI's verdict determines the action: High/Medium → hold; Low → approve.
2. A held payment moves **no money** until it is explicitly approved.
3. Approving a held payment completes it as a normal payment; rejecting it discards it. An approved payment is **not re-assessed** when it executes.
4. Existing rules still apply first: a payment without sufficient funds is refused immediately, before any fraud assessment.
5. If the AI cannot return a verdict, the payment is held rather than approved unchecked — external payments held, internal transfers may proceed.

---

## Visual Design

Consistent with the existing Gemini Bank UI (light theme, Bootstrap, the 🏦 brand). The held-for-review screen should feel calm and reassuring rather than alarming — this is protection, not an accusation. The verdict level is conveyed by label and colour (red / amber / green).

---

## Out of Scope

No real anti-fraud models, no external fraud databases, no customer notifications by email/SMS. Synthetic data only — the example dataset should include a few payments with socially-engineered descriptions so the contextual (language) detection is demonstrable.

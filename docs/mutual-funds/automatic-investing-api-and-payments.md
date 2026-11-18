# MintTick — Mutual funds, automatic purchases and payments

Status (2026-11-18): **Implementation foundation only, NOT live-enabled.** Customer draft-plan API, guarded data model and optional PREPARED-intent scheduler exist on the feature branch. No bank debit, actual mutual fund order, app UI, broker order forwarding, or verified mandate integration has been implemented. No SSM secret has been read or provisioned.

## Verified API/provider matrix

| Provider | Published capability | Integration decision |
| --- | --- | --- |
| NSE MF Invest | Mutual fund purchase/SIP transactions, onboarding, mandates, UPI AutoPay payment aggregation, webhook/API statuses | Strong exchange option; member/eligible distributor access required; use actual granted partner API docs |
| BSE StAR MF | Mutual fund order entry, eNACH mandate authentication/status, order-status/allotment web services | Exchange fallback; member login/permissions required; verify current StAR MF 2.0 API before writing an adapter |
| Fintech Primitives (Cybrilla) | MF purchase, recurring SIP (automatically creates purchase, debits and submits), payment gateways, mandate services | Preferred evaluation for fastest complete partner-managed SIP; requires commercial/provider authorization |
| Upstox developer MF endpoints | Retrieve order book/details, SIP registrations, holdings | READ-ONLY tracking integration; **do not** use Upstox equity place-order API for a mutual fund |

Official sources:
- https://www.nseindia.in/static/products-services/about-nmf-platform-key-benefits
- https://www.nseindia.in/static/products-services/about-nmf-getting-on-to
- https://www.bsestarmf.in/APIFileStructure.pdf (historical version 3.5; revalidate current scheme before integration)
- https://docs.fintechprimitives.com/mf-transactions/recurring-purchases/
- https://docs.fintechprimitives.com/mf-transactions/onetime-purchases/
- https://docs.fintechprimitives.com/payments/overview
- https://upstox.com/developer/api-documentation/announcements/mutual-fund-apis/

**Payment detail:** FP's generic payment overview lists UPI AutoPay/eNACH. Its recurring SIP guide currently specifically describes the provider-managed NACH flow. Confirm UPI AutoPay availability with the actual contracted SIP API, not just the generic payment page. Never claim Razorpay subscription payment is a settled mutual-fund purchase.

## Real, end-to-end recurring SIP (future provider integration)

1. Customer explicitly chooses an available fund scheme and amount. Validate provider's scheme eligibility, scheme ID/ISIN, minimum SIP, direct/regular plan, growth/distribution option, NAV/holiday rules.
2. Complete investor KYC and provider investor/investment-account registration. Verify ownership of investor bank account using the approved provider's TPV and other controls. Store tokenized references only.
3. Customer authorizes a mandate in provider's hosted/authenticated flow. Verify provider status using signed webhook or server-to-server fetch. Do **not** treat browser redirect as success.
4. Create the recurring SIP with the provider's SIP create API using registered investor, scheme, schedule, mandate and explicit consent. Record immutable provider SIP ID, consent receipt and current state. Provider-managed SIP execution is authoritative; **do not** independently create a second monthly debit/order.
5. Reconcile individual installment ID, payment ID, purchase order ID, exchange/AMC status, folio, actual units and actual NAV. Record partial, rejected, returned, delayed and refund states separately. Only show ALLOTTED after provider confirms units.
6. Pause/cancel/resume/change amount MUST call the provider and await acknowledgement. A local PAUSED flag without remote confirmation cannot stop a mandate debit.
7. Use broker Upstox holdings only as a separate, read-only reconciliation surface; never claim they are MintTick's executed orders.

## Additional dip-buy / occasional automatic lumpsum

Use a separate, explicitly opted-in, self-directed trigger agreement and **one-time MF purchase API**, not duplicate recurring SIPs. Enforce per-investor/aggregate cash limits, daily/weekly/monthly budgets, active mandate spending ceiling, cooling-off, scheme eligibility, payment TPV, user-specific authorization, stale-signal guard, emergency stop and regulatory review. A generic SIP mandate may not authorize variable ad-hoc debits; obtain an appropriately scoped mandate and payment-provider confirmation. Market index movement is only a trigger: purchase NAV is determined later under applicable cutoffs.

## Current MintTick API (draft only)

- `GET /api/customer/mutual-funds/capability` — truthful live payment readiness.
- `POST /api/customer/mutual-funds/plans` — self-directed draft. Requires customer JWT and `Idempotency-Key: <8-64 ASCII chars>` with JSON fields `isin`, `schemeName`, `amountPaise`, `maxMonthlyPaise`, `sipDay`. All amounts integer paise, initial system limits ₹100 to ₹1,00,000 per plan. No payment is made.
- `GET /api/customer/mutual-funds/plans` — list own drafts.
- `GET /api/customer/mutual-funds/plans/{id}` — own draft/plan.
- `POST /api/customer/mutual-funds/plans/{id}/pause-draft` — local draft pause ONLY.
- `POST /api/customer/mutual-funds/plans/{id}/resume-draft` — local draft resume ONLY. Does not activate.
- `minttick.mf.preparation.enabled=false` — prep-intent scheduler off by default. Turning it on alone cannot place orders or debit funds. Intent rows are PREPARED receipts, not payment/fill receipts.

## Provider integration contract and data model

Implement separate `MfTransactionPartner` interfaces with:
- `verifyInvestorAndScheme()` (KYC, scheme eligibility, bank TPV),
- `createMandateAuthorization()` / `getMandateStatus()` (one-time explicit user approval, provider attestation),
- `createSip()` / `getSip()` / `pauseSip()` / `cancelSip()` (provider-authoritative automatic SIP),
- `createOneTimePurchase()` and `initiatePayment()` (additional authorized investments only),
- `getPaymentStatus()`, `getPurchaseStatus()`, `getAllotment()`, and verified webhook processing.

Persist `customer_mf_plan`, `customer_mf_mandate`, `customer_mf_purchase_intent` and future provider receipt/event tables, all owner-scoped with unique idempotency keys. Never write raw bank information, UPI PIN, passwords or fund credentials to these tables. Store partner credentials in AWS SSM SecureString with IAM least privilege. Use versioned webhook signature verification, event replay deduplication and immutable audit receipts. Never blindly retry an ambiguous payment or purchase submission: reconcile by provider-issued id first.

## Gates before enabling real purchases

- Signed onboarding/contract/credentials with an authorized execution partner.
- Product suitability: required AMFI/SEBI eligibility, whether MintTick is a personal-use client or distributes to others, direct-versus-regular plan permissions, and investment-advice boundary.
- Verified backend APIs in partner UAT for investor, scheme, SIP, mandate, payment, webhook, purchase, rejection, refund and allotment.
- Real bank mandate consent, third-party verification, authorization, spending limits and revocation.
- Dry-run tests: two schedulers same day; retries/timeouts; webhook before redirect; payment success without allocation; partial rejection/refund; mandate expiry; pause/cancel race; downtime/reconciliation; scheduled holiday.
- Only then enable a separate server-side live gate and implement Flutter screens. No data or synthetic demo order is presented as live.

## Next implementation order

1. Get NSE MF Invest/FP partner sandbox and credentials; confirm selected partner and regulatory arrangement.
2. Add secure provider-specific adapter, webhook signature validation, idempotent remote SIP creation, and remote SIP pause/cancel.
3. Add reconciliation, immutable receipts, trusted source-of-truth for NAV/holdings, and operability alerts.
4. Add Flutter Mutual Funds tab, scheme details, consent/mandate flow, investment plan details and order/payment status.
5. Keep live execution disabled until UAT evidence and user authorization are complete.

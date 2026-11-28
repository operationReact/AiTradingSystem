# LIVE intraday cash-equity dispatch: enablement runbook

Status: dispatch is **disabled** (`minttick.live-equity.dispatch-enabled` defaults to `false`).
Nothing in this runbook is performed automatically. Each step needs an operator, real
evidence, or an approved release. Do not skip a step or record a placeholder as evidence.

## 1. Read-only broker field verification (authorized test account only)

Purpose: confirm the broker field names used by the parsers in
`backend/src/main/java/in/aitradingsystem/tradingrooms/` (`LiveEquityPositionParser`,
`AngelOneEquityOrderBookParser`, `UpstoxEquityOrderHistoryParser`).

Requirements: a broker session for an account the operator is authorized to use.
Do not paste credentials into chat or commit them.

1. Sign in to the MintTick customer app with the authorized account and connect the broker.
2. Call the existing read-only customer endpoints (no order endpoint is involved):
   - `GET /api/customer/brokers/angel-one/positions` and `GET /api/customer/brokers/angel-one/orders`
   - Upstox positions and order-history reads through the existing Upstox customer endpoints.
3. Compare **field names only** against the parser mappings. Record the key names and
   counts, never account identifiers, balances, or tokens.
4. Any mismatch (for example a renamed `netqty`, `realised`, `instrument_token`, or
   `order_id`) must be fixed in the parser and covered by a test before enablement.
   A mismatch currently fails closed, which blocks dispatch rather than allowing it.

Evidence to keep: date, broker, endpoint, sorted key names, and row counts.

## 2. Angel One hosting attestation (deployment egress IP)

Angel One has no verifiable static-IP API in this integration, so an operator attests it.

1. Confirm the production server's public egress IP.
2. Confirm in the Angel One developer console that this IP is registered for the API key.
3. Record the attestation through the operator API (ADMIN or OPERATOR key, `X-API-Key`):

   ```
   POST /api/operator/live-equity/hosting-attestations
   {"broker":"ANGEL_ONE","egressIp":"<confirmed IP>","operatorReference":"<ticket id>","validForSeconds":<<= 7776000>}
   ```

4. Readiness reads only an unexpired, unrevoked attestation. Revoke immediately with
   `DELETE /api/operator/live-equity/hosting-attestations/{id}` if the IP changes.

## 3. Per-customer account risk policy

Each customer who may trade needs a policy with values agreed by the customer and operator.
There are no default limits.

```
PUT /api/operator/live-equity/customers/{customerId}/risk-policy
{"maxAccountIntradayBuyInr":...,"maxOrderValueInr":...,"maxOrderQuantity":...,
 "dailyRealizedLossLimitInr":...,"operatorReference":"<ticket id>"}
```

Values are bounded by the server. Each save records the operator reference.

## 4. Operator resolution of an order stuck in SUBMITTING or UNKNOWN

Only for an order with no broker order id, after the operator has checked the broker's
own order book for the order's correlation tag and found no matching order:

```
POST /api/operator/live-equity/orders/{orderId}/resolve-not-placed
{"operatorReference":"<ticket id>","evidenceReference":"<export or screenshot reference>"}
```

Acknowledged, filled, and terminal orders cannot be released this way.

## 5. Enablement (requires release approval)

Enablement changes production behavior. It is a release decision, not a configuration
convenience. Only after steps 1 to 4, all CI checks green on the release commit, and the
approval recorded, set `minttick.live-equity.dispatch-enabled=true` in the target environment.
Then verify with `GET /api/customer/trading-rooms/{roomId}/live/equity/capability` for an
authorized account. Expect `orderRouteImplemented=true` only when the gate is on.

Rollback: set the property back to `false` and revoke any attestation. Orders already
claimed remain reserved until reconciled or operator-resolved.

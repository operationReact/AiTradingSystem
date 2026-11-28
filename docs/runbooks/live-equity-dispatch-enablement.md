# LIVE intraday cash-equity dispatch: enablement runbook

Status: dispatch is **disabled** (`minttick.live-equity.dispatch-enabled` defaults to `false`).
The PAPER Compose host forwards `MINTTICK_LIVE_EQUITY_DISPATCH_ENABLED` with a `false` default;
setting it to `true` only opens the master gate and does not bypass broker, account-risk, or
per-order checks. Nothing here runs automatically. Each step needs an authorized operator, real
evidence, or an approved release. Never record a placeholder as evidence, and never paste
credentials into chat, tickets or source control.

Operator calls below use the operator key in the `X-API-Key` header (ROLE_OPERATOR). They are
not reachable from the public customer account edge.

## 1. Read-only broker field verification

Purpose: confirm the broker field names the parsers read
(`backend/src/main/java/in/aitradingsystem/tradingrooms/`). A mismatch fails closed today: it
blocks SELL orders, the daily-loss check, or reconciliation. It never allows an order.

Who: the owner of an account the operator is authorized to use, signed in to the MintTick app
with that broker connected. These endpoints only read; none places, modifies or cancels orders.

| Broker | Endpoint (customer session) | Array location in response | Parser field names to confirm |
|---|---|---|---|
| Angel One | `GET /api/customer/brokers/angel-one/positions` | `data.data[]` | `exchange`, `symboltoken`, `producttype`, `netqty`, `realised` |
| Angel One | `GET /api/customer/brokers/angel-one/orders` | `data.data[]` | `orderid`, `ordertag`, `exchange`, `tradingsymbol`, `transactiontype`, `quantity`, `price`, `producttype`, `ordertype`, `duration`, `orderstatus` (or `status`), `filledshares`, `averageprice` |
| Upstox | `GET /api/customer/brokers/upstox/positions` | `data[]` | `exchange`, `instrument_token`, `product`, `quantity`, `realised` |
| Upstox | `GET /api/customer/brokers/upstox/orders` | `data[]` | `order_id`, `tag`, `exchange`, `trading_symbol`, `transaction_type`, `quantity`, `price`, `product`, `order_type`, `validity`, `status`, `filled_quantity`, `average_price` |

Notes:
- Upstox reconciliation reads `/v2/order/history?tag=...`. That endpoint is not exposed to
  customers, so `/orders` (Upstox `/v2/order/retrieve-all`) is the read-only proxy. Both are
  expected to use the same order field names; this proxy check does not prove it, so treat an
  Upstox reconciliation mismatch as a parser fix, not an order outcome.
- Empty arrays prove nothing about field names. Use an account with at least one position and
  one order for the day (an existing order, never one placed for this check).

Evidence to return: broker, endpoint, date, sorted key names of one array element, and the
element count. No account ids, symbols, quantities, prices, balances or tokens.

## 2. Hosting (static egress IP)

- **Upstox: no attestation.** Readiness checks the registered static IP automatically through
  the customer's own session (`GET /api/customer/brokers/upstox/registered-ips`).
- **Angel One: operator attestation required.**
  1. Confirm the production server's public egress IP.
  2. Confirm in the Angel One SmartAPI developer console that this IP is registered for the
     platform API key.
  3. Record it:

     ```
     POST /api/operator/live-equity/hosting-attestations
     {"broker":"ANGEL_ONE","egressIp":"<IPv4 or IPv6>","operatorReference":"<ticket id>",
      "validForSeconds":<1..7776000>}
     ```

     Response `201` returns the attestation `id` and `expiresAt` (at most 90 days).
  4. If the IP changes, revoke it immediately:
     `DELETE /api/operator/live-equity/hosting-attestations/{id}` with body
     `{"operatorReference":"<ticket id>"}`.

## 3. Per-customer account risk policy

There are no default limits; a customer without a policy cannot claim an order. The
`customerId` is the customer's `app_user.id` (UUID). There is no lookup API, so an operator gets
it from the production database or admin tooling.

```
PUT /api/operator/live-equity/customers/{customerId}/risk-policy
{"maxAccountIntradayBuyInr":<1..10000000>,"maxOrderValueInr":<1..10000000>,
 "maxOrderQuantity":<1..10000>,"dailyRealizedLossLimitInr":<0..10000000>,
 "operatorReference":"<ticket id>"}
```

Values are whole INR. Re-sending replaces the policy and records the new operator reference.
The loss check blocks when realized loss is greater than or equal to the limit, so a limit of
`0` blocks every new order.

## 4. Operator resolution of an order stuck in SUBMITTING or UNKNOWN

Only for an order with no broker order id, after checking the broker's own order book for the
order's correlation tag and finding no matching order:

```
POST /api/operator/live-equity/orders/{orderId}/resolve-not-placed
{"operatorReference":"<ticket id>","evidenceReference":"<broker export or screenshot reference>"}
```

The order becomes `REJECTED` with an audit row. Acknowledged, filled, partially filled and
terminal orders are refused with `409`.

## 5. Enablement (release approval required)

Only after steps 1 to 3 are complete, all CI checks are green on the release commit, and the
release approval is recorded:

1. Set `MINTTICK_LIVE_EQUITY_DISPATCH_ENABLED=true` (Spring property
   `minttick.live-equity.dispatch-enabled`) in the approved target environment only.
2. Verify, as the authorized customer, with
   `GET /api/customer/trading-rooms/{roomId}/live/equity/capability`:
   - `orderRouteImplemented` is `true`.
   - `executionEnabled` is `true` only when `blockers` is empty. Any remaining blocker names
     the gate still missing (for example `REGISTERED_STATIC_IP_NOT_VERIFIED`,
     `BROKER_APPROVED_HOSTING_NOT_VERIFIED`, `ACCOUNT_WIDE_RISK_POLICY_NOT_CONFIGURED`).
3. Verify with `GET /api/customer/trading-rooms/{roomId}/execution/readiness` that `blockers`
   is empty. Do not place an order to test enablement.

Rollback: set the property back to `false` (new claims are refused immediately) and revoke the
attestation. Orders already claimed stay reserved until reconciled or operator-resolved.

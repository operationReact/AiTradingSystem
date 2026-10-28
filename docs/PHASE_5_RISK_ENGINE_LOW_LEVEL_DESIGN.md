# Phase 5 — Deterministic Risk Engine Completion — Replacement Low-Level Design

> **Status:** AUTHORITATIVE PHASE-5 IMPLEMENTATION DESIGN  
> **Supersedes:** the Phase 5 / `P05-*` section in `docs/MASTER_LOW_LEVEL_IMPLEMENTATION_DESIGN.md`  
> **Scope authority:** `docs/MASTER_IMPLEMENTATION_PLAN.md`  
> **Repository:** `operationReact/AiTradingSystem`  
> **Integration branch:** `dev`  
> **Last reconciled:** 2026-10-28

---

# 1. Purpose

This document replaces the previous Phase 5 low-level design with an implementation-ready specification grounded in the current repository.

Phase 5 does **not** redesign the product or replace the existing risk path. It evolves the current deterministic PAPER risk implementation into a complete, versioned, auditable, composable risk engine that can safely serve:

1. current PAPER order submission;
2. Phase 6 PAPER accounting/lifecycle expansion;
3. Phase 7 MANUAL/AUTO/HYBRID approval policy;
4. Phase 14 production PAPER launch;
5. Phase 15 separately gated LIVE execution.

The phase remains aligned with reusable risk concepts adopted from the external day-trader reference, especially:

- minimum reward:risk;
- explicit maximum order notional;
- maximum trades per day;
- market-session gating;
- duplicate/pending exposure protection;
- deterministic risk-based position sizing.

Those concepts are adapted to our architecture: Java 21, Spring Boot, PostgreSQL, persisted NSE/BSE market data, Angel One provider boundary, deterministic PAPER execution, and Indian cash-equity constraints.

---

# 2. Current repository baseline — do not replace

The following current implementation is authoritative and must be migrated incrementally.

## 2.1 Current risk domain

Existing classes:

```text
backend/src/main/java/in/aitradingsystem/risk/
├── RiskDecision.java
├── RiskInput.java
├── RiskPolicy.java
└── RiskReason.java
```

Current `RiskPolicy` is deterministic and side-effect-free. It currently evaluates:

- emergency stop;
- invalid/future/stale signal timestamp;
- daily loss limit;
- BUY stop/target structure;
- per-trade risk budget;
- already-held symbol;
- maximum open positions;
- cash availability;
- holdings availability for SELL.

Current `RiskPolicy.VERSION = "risk-policy-v1"` must remain the historical version for existing rows.

## 2.2 Current configuration

Current `RiskProperties` contains:

```text
defaultCapital
riskPerTradePercent
maxDailyDrawdownPercent
maxOpenPositions
feeRate
slippageRate
signalMaxAgeMinutes
```

These remain bootstrap/default configuration values until a persisted policy is active.

## 2.3 Current execution path

Current order path:

```text
POST /api/paper-trades/simulate
        |
PaperTradeController.SimulateRequest
        |
OrderCommand
        |
PaperTradingService.placeOrder(...)
        |
SELECT paper_account ... FOR UPDATE
        |
load existing position/open position count/daily realized P&L/halt state
        |
new RiskInput(...)
        |
RiskPolicy.evaluate(...)
        |
INSERT risk_decision
        |
+--------------------------+
| APPROVED                 | REJECTED
|                          |
RISK_APPROVED              REJECTED
|                          |
apply PAPER fill           audit
|                          |
FILLED
```

Phase 5 must preserve the invariant that **risk evaluation happens inside the order transaction after the account lock and before any fill/cash/position mutation**.

## 2.4 Current persistence

Existing `risk_decision` columns:

```text
id UUID PK
order_id UUID UNIQUE FK paper_order
 decision
reason_codes JSONB
risk_budget
estimated_risk
estimated_notional
available_cash
open_positions
realized_pnl_today
daily_loss_limit
policy_version
evaluated_at
```

Existing `trading_control` remains the canonical emergency-stop state.

Existing `paper_account`, `paper_position`, `paper_order`, `paper_fill`, `paper_cash_entry` remain canonical. Phase 5 does not duplicate them.

## 2.5 Current risk API/UI

Current API:

```text
GET /api/risk/config
GET /api/risk/decisions
PUT /api/risk/emergency-stop
```

Current frontend:

```text
frontend/src/pages/RiskPage.jsx
```

Current page contains:

- limit KPI cards;
- PAPER/LIVE status badges;
- emergency-stop control;
- risk-decision table.

Phase 5 extends this page and later Phase 9 provides full dual-design composition.

---

# 3. Phase 5 target architecture

Final Phase 5 risk pipeline:

```text
Immutable OrderIntent / OrderCommand
        |
RiskContextFactory
        |--- active RiskPolicyConfig snapshot
        |--- trading_control
        |--- paper_account
        |--- open positions
        |--- symbol exposure
        |--- gross portfolio exposure
        |--- realized P&L today
        |--- trade count today
        |--- pending/open order intents
        |--- market session state
        |--- latest market/reference price freshness
        |--- candidate/signal provenance
        |
RiskPolicyEngine
        |
ordered List<RiskRule>
        |
RuleResult[]
        |
RiskDecisionV2
        |
Persist risk_decision + risk_decision_rule + exact policy snapshot
        |
if HARD_REJECT -> stop
        |
if PASS -> Phase 6 PAPER execution OR Phase 7 approval policy
```

No UI, AI, news summary, scanner score, backtest score, scenario probability or approval policy can directly alter the result of a `HARD_REJECT` rule.

---

# 4. Domain model

## P05-W01 — New common enums and records

Create under `in.aitradingsystem.risk`.

### `RiskOutcome.java`

```java
public enum RiskOutcome {
    PASS,
    HARD_REJECT,
    APPROVAL_REQUIRED
}
```

`APPROVAL_REQUIRED` is reserved for conditions explicitly classified as soft/operator-policy conditions. Core capital/safety constraints remain `HARD_REJECT`.

### `RiskRuleCode.java`

```java
public enum RiskRuleCode {
    EMERGENCY_STOP,
    SIGNAL_TIMESTAMP,
    SIGNAL_FRESHNESS,
    MARKET_STATE_FRESHNESS,
    MARKET_SESSION,
    PRICE_LEVELS,
    MIN_REWARD_RISK,
    PER_TRADE_RISK_BUDGET,
    MAX_ORDER_NOTIONAL,
    MAX_OPEN_POSITIONS,
    MAX_GROSS_EXPOSURE,
    MAX_SYMBOL_EXPOSURE,
    MAX_TRADES_PER_DAY,
    DUPLICATE_PENDING_EXPOSURE,
    EXISTING_SYMBOL_POSITION,
    CASH_AVAILABILITY,
    HOLDINGS_AVAILABILITY,
    DAILY_LOSS_LIMIT,
    INSTRUMENT_ELIGIBILITY
}
```

### `RiskRuleResult.java`

```java
public record RiskRuleResult(
    RiskRuleCode code,
    RiskOutcome outcome,
    String reasonCode,
    BigDecimal observedValue,
    BigDecimal limitValue,
    String unit,
    Map<String, Object> details
) {
    public boolean blocksExecution() {
        return outcome == RiskOutcome.HARD_REJECT;
    }
}
```

Rules must return machine-readable `reasonCode`; prose is generated separately for UI.

### `RiskDecisionClass.java`

```java
public enum RiskDecisionClass {
    APPROVED,
    APPROVAL_REQUIRED,
    REJECTED
}
```

### `RiskDecisionV2.java`

```java
public record RiskDecisionV2(
    UUID decisionId,
    RiskDecisionClass decisionClass,
    List<RiskRuleResult> ruleResults,
    BigDecimal effectiveFillPrice,
    BigDecimal grossNotional,
    BigDecimal estimatedFee,
    BigDecimal estimatedSlippage,
    BigDecimal estimatedRisk,
    BigDecimal riskBudget,
    BigDecimal rewardRiskRatio,
    BigDecimal grossExposureAfter,
    BigDecimal symbolExposureAfter,
    int tradesTodayAfter,
    UUID policyConfigId,
    int policyVersion,
    Instant evaluatedAt
) {
    public boolean approvedForRisk() {
        return decisionClass != RiskDecisionClass.REJECTED;
    }
}
```

The current `RiskDecision` stays temporarily for compatibility. Introduce a mapper during migration; do not break PaperTradingService and tests in the first PR.

---

# 5. Risk context

## P05-W02 — `RiskContext`

Replace the narrow `RiskInput` only after compatibility migration.

Create:

```java
public record RiskContext(
    UUID orderId,
    String correlationId,
    ExecutionMode executionMode,
    Side side,
    long instrumentId,
    String exchange,
    String tradingSymbol,
    String strategy,
    TradingStyle tradingStyle,
    int quantity,
    BigDecimal referencePrice,
    BigDecimal stopLoss,
    BigDecimal targetPrice,
    Instant signalGeneratedAt,
    Instant marketPriceAsOf,
    Instant now,

    BigDecimal startingCapital,
    BigDecimal availableCash,
    BigDecimal currentEquity,
    BigDecimal realizedPnlToday,

    int heldQuantity,
    BigDecimal heldMarketValue,
    int openPositionCount,
    BigDecimal currentGrossExposure,
    BigDecimal currentSymbolExposure,
    int tradesToday,
    boolean duplicatePendingExposure,

    boolean emergencyStopActive,
    MarketSessionState marketSessionState,
    boolean instrumentEligible,

    RiskPolicySnapshot policy
) {}
```

### `TradingStyle.java`

```java
INTRADAY,
SWING,
DELIVERY
```

### `MarketSessionState.java`

```java
PRE_OPEN,
OPEN,
POST_CLOSE,
CLOSED_DAY,
UNKNOWN
```

`UNKNOWN` must fail closed for any order requiring session validation.

---

# 6. Persisted risk-policy model

## P05-W03 — Migration: `risk_policy_config`

Use next available Flyway version when implemented.

Create:

```sql
CREATE TABLE risk_policy_config (
    id UUID PRIMARY KEY,
    policy_version INTEGER NOT NULL UNIQUE,
    policy_name VARCHAR(120) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT FALSE,

    risk_per_trade_percent NUMERIC(8,4) NOT NULL,
    max_daily_drawdown_percent NUMERIC(8,4) NOT NULL,
    max_open_positions INTEGER NOT NULL,
    min_reward_risk_ratio NUMERIC(10,4) NOT NULL,
    max_order_notional NUMERIC(20,2) NOT NULL,
    max_gross_exposure_percent NUMERIC(8,4) NOT NULL,
    max_symbol_exposure_percent NUMERIC(8,4) NOT NULL,
    max_trades_per_day INTEGER NOT NULL,
    signal_max_age_minutes INTEGER NOT NULL,
    market_state_max_age_seconds INTEGER NOT NULL,

    enforce_market_session BOOLEAN NOT NULL DEFAULT TRUE,
    reject_existing_symbol_position BOOLEAN NOT NULL DEFAULT TRUE,
    reject_duplicate_pending_exposure BOOLEAN NOT NULL DEFAULT TRUE,

    fee_rate NUMERIC(10,8) NOT NULL,
    slippage_rate NUMERIC(10,8) NOT NULL,
    fee_slippage_profile_version VARCHAR(40) NOT NULL,

    additional_config JSONB NOT NULL DEFAULT '{}'::jsonb,

    effective_from TIMESTAMPTZ NOT NULL,
    effective_to TIMESTAMPTZ,
    created_by VARCHAR(120) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_risk_policy_risk_per_trade
        CHECK (risk_per_trade_percent > 0 AND risk_per_trade_percent <= 5),
    CONSTRAINT ck_risk_policy_daily_drawdown
        CHECK (max_daily_drawdown_percent > 0 AND max_daily_drawdown_percent <= 20),
    CONSTRAINT ck_risk_policy_open_positions
        CHECK (max_open_positions BETWEEN 1 AND 100),
    CONSTRAINT ck_risk_policy_reward_risk
        CHECK (min_reward_risk_ratio >= 1 AND min_reward_risk_ratio <= 10),
    CONSTRAINT ck_risk_policy_order_notional
        CHECK (max_order_notional > 0),
    CONSTRAINT ck_risk_policy_gross_exposure
        CHECK (max_gross_exposure_percent > 0 AND max_gross_exposure_percent <= 100),
    CONSTRAINT ck_risk_policy_symbol_exposure
        CHECK (max_symbol_exposure_percent > 0 AND max_symbol_exposure_percent <= 100),
    CONSTRAINT ck_risk_policy_trades_day
        CHECK (max_trades_per_day BETWEEN 1 AND 1000),
    CONSTRAINT ck_risk_policy_signal_age
        CHECK (signal_max_age_minutes BETWEEN 1 AND 1440),
    CONSTRAINT ck_risk_policy_market_age
        CHECK (market_state_max_age_seconds BETWEEN 1 AND 3600),
    CONSTRAINT ck_risk_policy_effective_range
        CHECK (effective_to IS NULL OR effective_to > effective_from)
);
```

Indexes:

```sql
CREATE UNIQUE INDEX ux_risk_policy_one_active
    ON risk_policy_config ((active))
    WHERE active = TRUE;

CREATE INDEX ix_risk_policy_effective
    ON risk_policy_config (effective_from DESC, policy_version DESC);
```

Activation must be transactional:

1. lock active policy row;
2. validate new policy;
3. deactivate previous row and set `effective_to`;
4. activate new row;
5. write audit event.

Never UPDATE historical numeric limits of an already-effective policy. Create a new version.

## P05-W04 — `RiskPolicySnapshot`

```java
public record RiskPolicySnapshot(
    UUID id,
    int version,
    BigDecimal riskPerTradePercent,
    BigDecimal maxDailyDrawdownPercent,
    int maxOpenPositions,
    BigDecimal minRewardRiskRatio,
    BigDecimal maxOrderNotional,
    BigDecimal maxGrossExposurePercent,
    BigDecimal maxSymbolExposurePercent,
    int maxTradesPerDay,
    int signalMaxAgeMinutes,
    int marketStateMaxAgeSeconds,
    boolean enforceMarketSession,
    boolean rejectExistingSymbolPosition,
    boolean rejectDuplicatePendingExposure,
    BigDecimal feeRate,
    BigDecimal slippageRate,
    String feeSlippageProfileVersion
) {}
```

All calculation code consumes this immutable snapshot, never mutable Spring configuration after an evaluation begins.

---

# 7. Risk-decision persistence

## P05-W05 — Extend `risk_decision`

Forward migration:

```sql
ALTER TABLE risk_decision
    ADD COLUMN policy_config_id UUID,
    ADD COLUMN result_class VARCHAR(24),
    ADD COLUMN policy_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN reward_risk_ratio NUMERIC(18,8),
    ADD COLUMN gross_exposure_after NUMERIC(20,2),
    ADD COLUMN symbol_exposure_after NUMERIC(20,2),
    ADD COLUMN trades_today_after INTEGER,
    ADD COLUMN correlation_id VARCHAR(64);

ALTER TABLE risk_decision
    ADD CONSTRAINT fk_risk_decision_policy_config
        FOREIGN KEY (policy_config_id) REFERENCES risk_policy_config(id);

ALTER TABLE risk_decision
    ADD CONSTRAINT ck_risk_decision_result_class
        CHECK (result_class IS NULL OR result_class IN ('APPROVED','APPROVAL_REQUIRED','REJECTED'));
```

Do not rewrite legacy rows. Legacy `policy_version='risk-policy-v1'` remains interpretable.

Indexes:

```sql
CREATE INDEX ix_risk_decision_policy
    ON risk_decision (policy_config_id, evaluated_at DESC);

CREATE INDEX ix_risk_decision_result
    ON risk_decision (result_class, evaluated_at DESC);
```

## P05-W06 — New `risk_decision_rule`

```sql
CREATE TABLE risk_decision_rule (
    risk_decision_id UUID NOT NULL REFERENCES risk_decision(id) ON DELETE CASCADE,
    rule_sequence INTEGER NOT NULL,
    rule_code VARCHAR(80) NOT NULL,
    outcome VARCHAR(24) NOT NULL,
    reason_code VARCHAR(100) NOT NULL,
    observed_value NUMERIC(24,8),
    limit_value NUMERIC(24,8),
    unit VARCHAR(24),
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    PRIMARY KEY (risk_decision_id, rule_code),
    CONSTRAINT ck_risk_rule_outcome CHECK (outcome IN ('PASS','HARD_REJECT','APPROVAL_REQUIRED'))
);

CREATE INDEX ix_risk_rule_code_outcome
    ON risk_decision_rule (rule_code, outcome, risk_decision_id);
```

Persist PASS results as well as failures so the exact evaluation is reconstructable.

---

# 8. Rule SPI and deterministic ordering

## P05-W07 — `RiskRule`

```java
public interface RiskRule {
    RiskRuleCode code();
    int sequence();
    RiskRuleResult evaluate(RiskContext context);
}
```

Rule order is stable and test-enforced.

Proposed order:

```text
010 EMERGENCY_STOP
020 INSTRUMENT_ELIGIBILITY
030 SIGNAL_TIMESTAMP
040 SIGNAL_FRESHNESS
050 MARKET_STATE_FRESHNESS
060 MARKET_SESSION
070 PRICE_LEVELS
080 MIN_REWARD_RISK
090 DAILY_LOSS_LIMIT
100 PER_TRADE_RISK_BUDGET
110 MAX_ORDER_NOTIONAL
120 CASH_AVAILABILITY / HOLDINGS_AVAILABILITY
130 EXISTING_SYMBOL_POSITION
140 MAX_OPEN_POSITIONS
150 MAX_GROSS_EXPOSURE
160 MAX_SYMBOL_EXPOSURE
170 MAX_TRADES_PER_DAY
180 DUPLICATE_PENDING_EXPOSURE
```

The engine evaluates all rules even after one rejection where safe, so the operator receives a complete rejection explanation. It must not perform external calls during rule evaluation.

---

# 9. Exact rule definitions

## P05-W08 — Emergency stop

Class:

```text
risk/rules/EmergencyStopRule.java
```

Outcome:

```text
emergencyStopActive = true -> HARD_REJECT / EMERGENCY_STOP_ACTIVE
false -> PASS
```

No override path.

## P05-W09 — Instrument eligibility

Class:

```text
InstrumentEligibilityRule.java
```

V1 eligibility requires:

- active instrument catalogue row;
- cash-equity instrument type;
- exchange NSE/BSE as configured;
- valid symbol token/identity;
- instrument not explicitly disabled.

Failure -> `HARD_REJECT`.

## P05-W10 — Signal timestamp/freshness

Classes:

```text
SignalTimestampRule.java
SignalFreshnessRule.java
```

Rules:

```text
signalGeneratedAt == null
    -> allowed only for explicitly manual non-signal workflow if future product contract supports it;
       otherwise HARD_REJECT.

signalGeneratedAt > now
    -> HARD_REJECT / SIGNAL_TIMESTAMP_INVALID.

Duration(now - signalGeneratedAt) > configured max
    -> HARD_REJECT / STALE_SIGNAL.
```

Preserve current behavior for existing paper `/simulate` endpoint.

## P05-W11 — Market-state freshness

Class:

```text
MarketStateFreshnessRule.java
```

Input `marketPriceAsOf`.

If absent or older than configured `marketStateMaxAgeSeconds` for an execution-intent workflow:

```text
HARD_REJECT / MARKET_STATE_STALE
```

Phase 5 can use the current order reference/candle timestamp until a live quote component exists, but the source and timestamp must be explicit.

## P05-W12 — Market session

Class:

```text
MarketSessionRule.java
```

Uses Phase 1 `MarketSessionCalendar` when available.

For V1 PAPER:

- if `enforceMarketSession=false`, PASS but persist rule detail `disabledByPolicy=true`;
- if enabled, only `OPEN` may pass for normal intraday execution;
- SWING/DELIVERY order scheduling after market close is not silently accepted as executed; future queued semantics belong to Phase 6/7;
- `UNKNOWN` fails closed.

## P05-W13 — Price levels

Class:

```text
PriceLevelRule.java
```

BUY V1:

```text
stopLoss != null
targetPrice != null
stopLoss < referencePrice < targetPrice
```

SELL closing-order validation is based on holdings and order type; Phase 5 does not introduce naked shorts.

Failure -> `HARD_REJECT / INVALID_PRICE_LEVELS`.

## P05-W14 — Minimum reward:risk

Class:

```text
MinimumRewardRiskRule.java
```

For BUY:

```text
riskPerShare   = effectiveFillPrice - stopLoss
rewardPerShare = targetPrice - effectiveFillPrice
rewardRisk     = rewardPerShare / riskPerShare
```

Rules:

- `riskPerShare <= 0` -> HARD_REJECT;
- ratio computed with fixed scale/rounding only for presentation; comparison uses unrounded BigDecimal where practical;
- `rewardRisk < policy.minRewardRiskRatio` -> HARD_REJECT `MIN_REWARD_RISK_NOT_MET`.

Persist observed ratio and limit.

## P05-W15 — Daily loss

Class:

```text
DailyLossRule.java
```

Preserve current inclusive boundary:

```text
realizedPnlToday <= -dailyLossLimit -> HARD_REJECT
```

`dailyLossLimit = startingCapital * maxDailyDrawdownPercent / 100` for current semantics.

Future Phase 6 may change base to start-of-day equity; that requires explicit master-plan update, not silent behavior change.

## P05-W16 — Per-trade risk budget

Class:

```text
PerTradeRiskBudgetRule.java
```

Effective BUY risk:

```text
effectiveFillPrice = PaperExecutionModel.fillPrice(...)
priceRisk = (effectiveFillPrice - stopLoss) * quantity
estimatedFee = PaperExecutionModel.fee(gross, feeRate)
estimatedRisk = priceRisk + estimatedFee
riskBudget = startingCapital * riskPerTradePercent / 100
```

`estimatedRisk > riskBudget` -> HARD_REJECT.

Preserve existing fee/slippage semantics until Phase 6 introduces versioned execution profiles.

## P05-W17 — Maximum order notional

Class:

```text
MaxOrderNotionalRule.java
```

```text
notional = effectiveFillPrice * quantity
```

Use gross notional before fees for limit comparison.

`notional > maxOrderNotional` -> HARD_REJECT.

## P05-W18 — Cash/holdings

Classes:

```text
CashAvailabilityRule.java
HoldingsAvailabilityRule.java
```

BUY:

```text
gross + estimatedFee <= availableCash
```

SELL:

```text
heldQuantity >= requested quantity
```

No margin/shorting assumption in V1.

## P05-W19 — Existing symbol exposure

Class:

```text
ExistingSymbolExposureRule.java
```

Preserve existing V1 behavior when `rejectExistingSymbolPosition=true`:

```text
BUY && heldQuantity > 0 -> HARD_REJECT SYMBOL_ALREADY_HELD
```

When later disabled by explicit policy, SymbolConcentrationRule still applies.

## P05-W20 — Maximum open positions

Class:

```text
MaxOpenPositionsRule.java
```

For a BUY opening a new symbol:

```text
openPositionCount >= maxOpenPositions -> HARD_REJECT
```

Do not count closing SELL orders as new positions.

## P05-W21 — Gross portfolio exposure

Class:

```text
GrossPortfolioExposureRule.java
```

```text
maxGrossExposureAmount = currentEquity * maxGrossExposurePercent / 100
grossExposureAfter = currentGrossExposure + orderNotionalForNewExposure
```

BUY open/add exposure increases gross exposure. Closing SELL decreases/does not increase exposure.

`grossExposureAfter > max` -> HARD_REJECT.

## P05-W22 — Symbol concentration

Class:

```text
SymbolConcentrationRule.java
```

```text
maxSymbolExposureAmount = currentEquity * maxSymbolExposurePercent / 100
symbolExposureAfter = currentSymbolExposure + orderNotionalForNewExposure
```

`symbolExposureAfter > max` -> HARD_REJECT.

## P05-W23 — Maximum trades/day

Class:

```text
MaxTradesPerDayRule.java
```

Definition for V1:

- count filled entry/exit orders whose final fill date is current Asia/Kolkata trading date;
- rejected orders do not count;
- idempotent replay does not count twice;
- Phase 6 partial fills still count one logical order, not one fill.

For a new executable order:

```text
tradesToday >= maxTradesPerDay -> HARD_REJECT
```

## P05-W24 — Duplicate pending exposure

Class:

```text
DuplicatePendingExposureRule.java
```

`duplicatePendingExposure=true` when an existing non-terminal order/order-intent for the same instrument and same exposure-increasing side exists.

V1 terminal states:

```text
REJECTED
FILLED
CANCELLED
EXPIRED
FAILED
```

Until Phase 6 adds all states, current `NEW` and `RISK_APPROVED` rows are considered pending.

Failure -> HARD_REJECT `DUPLICATE_PENDING_EXPOSURE`.

This is separate from HTTP idempotency-key replay protection.

---

# 10. Risk engine

## P05-W25 — `RiskPolicyEngine`

Create:

```java
@Service
public final class RiskPolicyEngine {
    private final List<RiskRule> rules;

    public RiskDecisionV2 evaluate(RiskContext context) {
        // stable sequence
        // evaluate every safe rule
        // aggregate outcome
        // calculate common monetary values once
        // return immutable result
    }
}
```

Aggregation:

```text
any HARD_REJECT -> REJECTED
else any APPROVAL_REQUIRED -> APPROVAL_REQUIRED
else -> APPROVED
```

No rule may mutate context/database.

`RiskPolicyEngine` must be deterministic for identical `RiskContext`.

## P05-W26 — Compatibility adapter

Create:

```text
LegacyRiskPolicyAdapter.java
```

During migration:

- current `RiskPolicy` tests remain untouched initially;
- adapter converts existing `RiskInput` into minimum `RiskContext` using bootstrap defaults;
- run old and new engines in test-only shadow comparison;
- only switch PaperTradingService after parity is proven for existing rules.

Do not delete old `RiskPolicy` in the same PR that introduces the new engine.

---

# 11. Context construction and repository queries

## P05-W27 — `RiskContextFactory`

Create:

```java
@Service
public class RiskContextFactory {
    RiskContext build(UUID orderId, OrderCommand command, Instant now);
}
```

This becomes the only production builder of `RiskContext`.

Required dependencies:

```text
RiskPolicyConfigRepository
PaperAccountRepository or existing JdbcClient query during transition
PaperPositionRepository/query
PaperOrder/RiskState query
TradingControlRepository/query
MarketDataRepository
MarketSessionCalendar
InstrumentMasterRepository
Clock
```

No controller manually supplies account/exposure/risk state.

## P05-W28 — Required SQL/query semantics

### Lock/account

During existing PAPER transaction:

```sql
SELECT starting_capital, cash_balance
FROM paper_account
WHERE id = 1
FOR UPDATE;
```

Preserve this serialization until Phase 6 defines a replacement concurrency model.

### Open positions

Current count remains source until Phase 6 normalized position model:

```sql
SELECT COUNT(*) FROM paper_position;
```

### Symbol position

```sql
SELECT quantity, cost_basis
FROM paper_position
WHERE symbol = :symbol;
```

When `instrument_id` exists in Phase 6, switch identity from symbol to instrument ID.

### Realized P&L today

Continue deriving from fills with Asia/Kolkata day start. Query must be index-supported by `filled_at`.

### Trades today

Use logical filled orders, not fill rows:

```sql
SELECT COUNT(*)
FROM paper_order
WHERE status = 'FILLED'
  AND updated_at >= :marketDayStart;
```

After Phase 6 introduces `filled_at/completed_at`, migrate query accordingly.

### Pending duplicate

Before Phase 6 expanded states:

```sql
SELECT EXISTS (
  SELECT 1
  FROM paper_order
  WHERE symbol = :symbol
    AND side = :side
    AND status IN ('NEW','RISK_APPROVED')
    AND id <> :currentOrderId
);
```

### Gross exposure

Until Phase 6 portfolio valuation exists, use deterministic position cost/reference values explicitly documented. Phase 6 must switch to latest fresh valuation. The API must expose the valuation basis in rule details.

---

# 12. Risk policy repositories/services

## P05-W29 — `RiskPolicyConfigRepository`

Methods:

```java
RiskPolicySnapshot requireActiveForUpdate();
RiskPolicySnapshot requireActive();
Optional<RiskPolicySnapshot> findById(UUID id);
PageResponse<RiskPolicySummary> list(int page, int size);
int nextVersion();
void insert(RiskPolicyCreateCommand command, int version, boolean active);
void deactivate(UUID id, Instant effectiveTo);
void activate(UUID id);
```

Use transaction/locking for `nextVersion + activation`; do not rely on race-prone max+1 without lock.

## P05-W30 — `RiskPolicyConfigService`

Methods:

```java
RiskPolicyView active();
RiskPolicyView createAndActivate(CreateRiskPolicyRequest request, Actor actor);
PageResponse<RiskPolicyView> history(...);
RiskPolicyView get(UUID id);
```

Validation:

- symbol exposure <= gross exposure is recommended default and must be validated unless explicit rationale says otherwise;
- risk per trade < daily loss limit;
- positive notional/exposure values;
- activation requires authenticated actor in Phase 10; current operator-key gate until then.

Audit:

```text
RISK_POLICY_CREATED
RISK_POLICY_ACTIVATED
RISK_POLICY_DEACTIVATED
```

No DELETE endpoint for historical policy rows.

---

# 13. Position sizing

## P05-W31 — `PositionSizingService`

Create deterministic advisory calculator.

```java
public PositionSizeSuggestion suggest(PositionSizeRequest request, RiskPolicySnapshot policy, PortfolioRiskState state);
```

### Calculation

For long BUY:

```text
riskPerShare = effectiveEntry - stopLoss
riskBudgetAmount = capitalOrEquityBase * riskPerTradePercent / 100
qtyByRisk = floor(riskBudgetAmount / riskPerShare)
qtyByNotional = floor(maxOrderNotional / effectiveEntry)
qtyByCash = floor(availableCash / estimatedAllInPerShare)
qtyByGrossExposure = floor(remainingGrossExposure / effectiveEntry)
qtyBySymbolExposure = floor(remainingSymbolExposure / effectiveEntry)

suggestedQty = min(
    qtyByRisk,
    qtyByNotional,
    qtyByCash,
    qtyByGrossExposure,
    qtyBySymbolExposure
)
```

If `<1`, return quantity 0 with limiting rule.

### `PositionSizeSuggestion`

```java
public record PositionSizeSuggestion(
    int quantity,
    BigDecimal riskPerShare,
    BigDecimal riskBudgetAmount,
    BigDecimal estimatedRisk,
    BigDecimal estimatedNotional,
    BigDecimal estimatedFee,
    RiskRuleCode limitingRule,
    Map<RiskRuleCode, Integer> quantityCaps,
    int policyVersion
) {}
```

This endpoint is advisory. Actual order submission rebuilds fresh risk context and never trusts client-supplied suggested quantity as approved.

---

# 14. APIs

## P05-W32 — Risk policy APIs

### `GET /api/risk/policy`

Returns active persisted policy.

Response includes:

```text
id
version
policyName
all typed limits
fee/slippage profile
effectiveFrom
createdBy
createdAt
```

### `GET /api/risk/policies?page=0&size=20`

Historical policy versions.

### `GET /api/risk/policies/{id}`

Immutable detail.

### `POST /api/risk/policies`

Creates a new policy version; request must not specify `policyVersion`.

Current auth: operator key. Phase 10: ADMIN only.

Do not support PUT-in-place mutation of an historical policy.

## P05-W33 — Risk evaluation detail APIs

Keep:

```text
GET /api/risk/decisions
```

Enhance list row with:

```text
resultClass
policyVersion
rewardRiskRatio
grossExposureAfter
symbolExposureAfter
tradesTodayAfter
failedRuleCount
```

Add:

```text
GET /api/risk/decisions/{decisionId}
```

Detail response includes ordered `ruleResults`.

## P05-W34 — Position-size API

```text
POST /api/risk/position-size
```

Request:

```json
{
  "instrumentId": 123,
  "exchange": "NSE",
  "symbol": "INFY-EQ",
  "entryPrice": 1500.00,
  "stopLoss": 1475.00,
  "targetPrice": 1550.00,
  "strategy": "VOLUME_BREAKOUT_V1",
  "tradingStyle": "INTRADAY"
}
```

Server derives account/equity/exposure/policy; client cannot provide those values.

Response: `PositionSizeSuggestion`.

## P05-W35 — Emergency-stop API hardening

Keep:

```text
PUT /api/risk/emergency-stop
```

Enhance persistence/audit later with actor identity.

Rules:

- reason required when halting in production (`min 3`, max 200);
- resume reason required after Phase 10 actor auth;
- changing to current state remains idempotent;
- emergency stop changes invalidate risk/dashboard/order readiness cache on frontend.

---

# 15. Backward compatibility strategy

## P05-W36 — No big-bang switch

Implementation order:

### Step A

Add new tables/classes only. Existing PAPER execution still uses `RiskPolicy` v1.

### Step B

Add `RiskPolicyEngine` and parity tests. Run old/new engines against same fixtures.

### Step C

Persist new policy config but keep bootstrap from `RiskProperties` if no active DB policy exists.

### Step D

Switch PaperTradingService to:

```text
RiskContextFactory -> RiskPolicyEngine -> RiskDecisionPersistenceService
```

while keeping transaction/account lock behavior unchanged.

### Step E

Remove direct construction:

```java
new RiskPolicy(props)
new RiskInput(...)
```

from `PaperTradingService` only after integration parity tests pass.

### Step F

Deprecate `RiskInput`/legacy `RiskDecision`; do not delete until all tests/controllers/views migrate.

---

# 16. Persistence service

## P05-W37 — `RiskDecisionPersistenceService`

Create:

```java
void persist(UUID orderId, RiskContext context, RiskDecisionV2 decision);
RiskDecisionDetail require(UUID id);
PageResponse<RiskDecisionSummary> list(RiskDecisionFilter filter);
```

Transaction rules:

- when called from order execution, participates in existing order transaction;
- insert parent `risk_decision` first;
- batch insert all `risk_decision_rule` rows;
- policy snapshot JSON is serialized from validated server object only;
- no later UPDATE of rule outcomes.

Audit event:

```text
RISK_EVALUATED
```

Details:

```text
orderId
decisionId
resultClass
policyVersion
failedRuleCodes
approvalRequiredRuleCodes
```

Do not duplicate all monetary policy fields into audit JSON; DB snapshot is canonical.

---

# 17. Integration with PaperTradingService

## P05-W38 — Target orchestration

Inside existing `@Transactional placeOrder`:

```text
1. canonical request hash/idempotency check
2. lock paper account
3. insert paper_order NEW
4. RiskContextFactory.build(...)
5. RiskPolicyEngine.evaluate(...)
6. RiskDecisionPersistenceService.persist(...)
7. if REJECTED:
      NEW -> REJECTED
      audit ORDER_REJECTED
      return
8. if APPROVAL_REQUIRED:
      Phase 5 compatibility: treat as non-executable until Phase 7
      store state/reason safely; do not fill
9. if APPROVED:
      NEW -> RISK_APPROVED
      existing PAPER fill path
10. RISK_APPROVED -> FILLED
```

Before Phase 7 exists, no `APPROVAL_REQUIRED` rule should be enabled in the production active policy. The enum exists now to avoid redesign later.

## P05-W39 — Idempotency interaction

Replaying the same `Idempotency-Key` + same request hash:

- returns existing order/result;
- does not run a second risk evaluation;
- does not create additional `risk_decision_rule` rows;
- does not increment trade count;
- does not alter policy snapshot.

Same key + different request hash remains `409`.

---

# 18. UI/UX — shared business layer

## P05-W40 — React Query keys/hooks

Extend `frontend/src/hooks/useApi.js`.

Keys:

```js
riskPolicy: ['risk', 'policy'],
riskPolicies: (params) => ['risk', 'policies', params],
riskDecision: (id) => ['risk', 'decision', id],
riskDecisions: (params) => ['risk', 'decisions', params],
riskPositionSize: (input) => ['risk', 'position-size', input],
```

Hooks:

```text
useRiskPolicy()
useRiskPolicies(params)
useRiskPolicyDetail(id)
useCreateRiskPolicy()
useRiskDecisions(params)
useRiskDecisionDetail(id)
usePositionSizeSuggestion()
useEmergencyStop()
```

Mutation invalidation:

```text
create policy -> ['risk']
emergency stop -> ['risk'], ['dashboard'], ['paper']
```

No design-specific hook duplication.

---

# 19. UI/UX — route/page structure

## P05-W41 — Existing `/risk` remains canonical

Do not create separate Pro/Clear URLs.

Target page controller:

```text
RiskPage.jsx
   |
   +-- loads shared risk policy
   +-- loads emergency-stop state
   +-- loads risk utilization
   +-- loads decision history
   +-- manages selected decision/policy dialogs
   |
ExperienceBoundary
   |-------------------------------|
ProTerminalRiskView         ClearInvestRiskView
```

During Phase 5, shared components can be introduced incrementally while existing `RiskPage` remains functional. Full distinct compositions can complete in Phase 9.

---

# 20. UI/UX — components

## P05-W42 — Shared semantic components

Create/reuse:

```text
risk/RiskLimitCard.jsx
risk/RiskUtilizationBar.jsx
risk/RiskRuleBadge.jsx
risk/RiskDecisionDrawer.jsx
risk/RiskRuleResultTable.jsx
risk/RiskPolicyVersionBadge.jsx
risk/RiskPolicyHistoryDrawer.jsx
risk/PositionSizeCalculator.jsx
risk/EmergencyStopCard.jsx
risk/RiskPolicyEditor.jsx
risk/RiskPolicyDiff.jsx
```

Every risk rule must have:

- machine code;
- short human label;
- explanation;
- observed value;
- limit value;
- PASS/REJECT/APPROVAL_REQUIRED visual state.

Do not display only raw enum strings to normal users.

## P05-W43 — Pro Terminal composition

Pro Terminal target:

```text
Top status strip
  - PAPER/LIVE mode
  - Emergency stop
  - Policy version
  - Daily loss utilization
  - Gross exposure utilization

Dense limits grid
  - per-trade risk
  - daily drawdown
  - max notional
  - gross exposure
  - symbol exposure
  - max positions
  - max trades/day
  - signal age

Risk decision table
  - timestamp
  - symbol
  - order
  - result
  - risk / budget
  - R:R
  - gross exposure after
  - failed rules

Right-side decision drawer
  - ordered rule evaluation timeline/table
  - exact policy snapshot
  - correlation ID
  - observed vs limit values
```

Designed for high information density without hiding rejection details.

## P05-W44 — Clear Invest composition

Clear Invest target:

```text
Risk health summary
  - "Within limits" / "Orders paused" / "Limit reached"

Guided cards
  - How much can I risk per trade?
  - How much can I invest in one order?
  - How many positions are allowed?
  - Daily loss protection

Exposure progress cards
  - gross exposure
  - symbol concentration
  - trades today

Recent decisions
  - plain-language accepted/rejected explanation

Decision detail sheet
  - "Why was this blocked?"
  - rule-by-rule explanation
```

Same API fields, no weaker data visibility.

## P05-W45 — Policy editor UX

Policy changes are dangerous configuration mutations.

Requirements:

- never edit active policy in place;
- opening editor clones active values into a draft form;
- show current vs proposed diff;
- validate client-side for usability but server remains authority;
- confirmation modal states that a new version will be activated;
- production actor/auth restrictions arrive in Phase 10;
- emergency stop state is not automatically cleared by policy activation.

Fields:

```text
Risk per trade %
Max daily drawdown %
Max open positions
Minimum reward:risk
Max order notional
Max gross exposure %
Max symbol exposure %
Max trades/day
Signal max age
Market state max age
Enforce market session
Reject existing symbol position
Reject duplicate pending exposure
```

Fee/slippage values may be visible but should be edited only through the approved execution-cost profile workflow if Phase 6 separates them.

---

# 21. API DTOs

## P05-W46 — Request/response records

Create in `api` or `risk/api` according to current project convention.

### `CreateRiskPolicyRequest`

Typed Bean Validation; no arbitrary rule list.

### `RiskPolicyView`

Server-normalized representation.

### `RiskDecisionSummaryView`

List-safe fields only.

### `RiskDecisionDetailView`

Includes ordered rule results and policy identity/snapshot metadata.

### `PositionSizeRequest`

Client supplies only trade setup fields.

### `PositionSizeView`

Contains caps/limiting rule but is explicitly advisory.

No DTO exposes internal database SQL details or secrets.

---

# 22. Configuration migration

## P05-W47 — Bootstrap persisted policy

When `risk_policy_config` is introduced, create application bootstrap logic:

```text
if no risk_policy_config exists:
    create policy version 1 from validated RiskProperties
    active = true
    created_by = SYSTEM_BOOTSTRAP
```

But historical existing `risk_decision.policy_version = risk-policy-v1` remains unchanged.

Avoid Flyway inserting environment-specific monetary policy values because deployment configuration may differ. Bootstrap service reads validated runtime configuration and inserts only when DB is empty.

Class:

```text
RiskPolicyBootstrap.java
```

Must be idempotent and safe under concurrent app startup. Use DB unique active/version constraints and retry/re-read on duplicate key.

---

# 23. Error handling

## P05-W48 — Error codes

Add stable API error codes:

```text
RISK_POLICY_NOT_CONFIGURED
RISK_POLICY_VERSION_CONFLICT
RISK_POLICY_INVALID
RISK_DECISION_NOT_FOUND
POSITION_SIZE_NOT_POSSIBLE
MARKET_STATE_UNAVAILABLE
MARKET_STATE_STALE
MARKET_SESSION_CLOSED
EMERGENCY_STOP_ACTIVE
ORDER_RISK_REJECTED
```

Order rejection remains a business rejection, not a server error. Existing `/paper-trades/simulate` may retain `422` compatibility while newer canonical order API in Phase 6 can return structured state consistently.

---

# 24. Observability

## P05-W49 — Metrics

Add Micrometer metrics when observability foundation exists:

```text
risk_evaluations_total{result}
risk_rule_results_total{rule,outcome}
risk_evaluation_duration_seconds
risk_policy_version_active
risk_emergency_stop_active
risk_position_size_requests_total{result}
```

Do not tag metrics with order IDs/symbols if cardinality becomes unbounded.

Logs:

- correlation ID;
- decision ID;
- order ID;
- policy version;
- result class;
- failed rule codes.

Never log API keys, broker tokens, full policy JSON unnecessarily, or user secrets.

---

# 25. Test architecture

## P05-W50 — Existing-regression parity suite

Before switching production path, create `RiskPolicyV1ParityTest`.

For existing behavior fixtures, old `RiskPolicy` and new engine must agree on:

- emergency stop;
- future signal;
- stale signal;
- daily loss exact boundary;
- invalid stop/target;
- risk budget exact boundary;
- held symbol;
- max open positions;
- insufficient cash;
- insufficient holdings.

Differences require explicit documented behavior change.

## P05-W51 — Rule unit tests

One class per rule where complexity justifies it.

Each rule must cover:

```text
below boundary
exact boundary
above boundary
null/missing input where possible
large decimal scale
repeatability
```

Examples:

```text
MinimumRewardRiskRuleTest
MaxOrderNotionalRuleTest
GrossPortfolioExposureRuleTest
SymbolConcentrationRuleTest
MaxTradesPerDayRuleTest
DuplicatePendingExposureRuleTest
MarketSessionRuleTest
MarketStateFreshnessRuleTest
```

## P05-W52 — Engine tests

`RiskPolicyEngineTest`:

- deterministic stable ordering;
- multiple failed rules all returned;
- any HARD_REJECT -> REJECTED;
- APPROVAL_REQUIRED only when no hard rejects;
- identical context -> identical result;
- rule ordering independent of Spring injection order.

## P05-W53 — Persistence integration tests

PostgreSQL/Testcontainers:

```text
RiskPolicyConfigRepositoryIT
RiskPolicyBootstrapIT
RiskDecisionPersistenceIT
RiskPolicyActivationConcurrencyIT
```

Verify:

- one active policy only;
- policy history immutable;
- decision stores exact policy snapshot;
- all PASS and failure rule rows persisted;
- FK behavior;
- no duplicate decision on idempotent replay.

## P05-W54 — PAPER integration tests

Extend existing PaperTrading integration tests:

- max notional rejection creates no fill/cash mutation;
- min R:R rejection creates no fill/cash mutation;
- max trades/day rejection;
- duplicate pending exposure rejection;
- stale market-state rejection;
- gross exposure rejection;
- symbol exposure rejection;
- emergency stop remains final hard block;
- exact boundary approval/rejection semantics;
- policy snapshot does not change if active policy changes after order evaluation;
- same idempotency key replay returns original decision/policy snapshot.

## P05-W55 — API tests

`RiskApiIT`:

- active policy retrieval;
- bounded policy history;
- invalid policy request validation;
- create new version;
- decision detail with rule rows;
- position sizing;
- emergency-stop semantics.

## P05-W56 — Frontend tests

At minimum:

- active policy renders all mandatory limits;
- emergency stop confirmation and invalidation;
- rejected decision drawer renders rule reasons;
- policy diff renders changed fields;
- position sizing displays advisory label;
- Pro Terminal and Clear Invest consume identical data model;
- switching experience does not drop selected decision/draft policy form where technically supported by page controller state.

---

# 26. Performance and locking

## P05-W57 — Performance targets

Risk evaluation must not become a scanner/backtest-style expensive workflow.

Target:

- all DB state required by `RiskContextFactory` loaded in a bounded number of queries;
- no per-rule DB query;
- no network/provider call inside `RiskPolicyEngine`;
- no unbounded history scan;
- use indexed current-state queries.

Risk rules are pure in-memory computations after context creation.

## P05-W58 — Concurrency

Preserve current `paper_account FOR UPDATE` lock for PAPER order serialization during Phase 5.

Duplicate pending exposure query runs after account lock in the same transaction to avoid two concurrent BUYs both passing.

Policy activation uses separate row/table-level transaction and must not change the snapshot already loaded into an in-flight order.

---

# 27. Security

## P05-W59 — Authorization boundary

Before Phase 10:

- current operator API-key security remains required.

After Phase 10:

```text
READ_ONLY
  -> view active policy/decision history only

OPERATOR
  -> view policy, position sizing, emergency stop if authorized

ADMIN
  -> create/activate risk policy
  -> resume emergency stop where policy requires admin
```

No frontend authorization assumption replaces backend checks.

## P05-W60 — Tamper resistance

Client cannot supply:

- available cash;
- current equity;
- open positions;
- gross exposure;
- current symbol exposure;
- trades today;
- policy version to be enforced;
- emergency-stop state;
- hard risk outcome.

All are server-derived.

---

# 28. Integration with Phase 7 approvals

## P05-W61 — Hard vs soft contract

Phase 5 establishes the permanent boundary:

```text
RiskPolicyEngine
    HARD_REJECT -> terminal rejection
    APPROVED -> eligible for execution policy
    APPROVAL_REQUIRED -> eligible only for approval workflow
```

Phase 7 cannot transform HARD_REJECT into approval.

Phase 7 must re-run `RiskContextFactory + RiskPolicyEngine` immediately before final execution after approval to protect against TOCTOU changes in:

- price;
- cash;
- holdings;
- exposure;
- daily loss;
- emergency stop;
- session state;
- policy status where applicable.

---

# 29. Integration with research/day-trader concepts

## P05-W62 — Research remains advisory

Scanner/backtest/scenario fields may be attached to order provenance but are not risk overrides.

Examples:

```text
scanner score = 0.95
scenario target-first probability = 0.80
expected R = 1.7
```

None of these can bypass:

- min R:R;
- max notional;
- cash;
- exposure;
- daily loss;
- emergency stop;
- session/freshness;
- position limits.

The useful external day-trader concepts are implemented as deterministic local rules, not copied as LLM decision authority.

---

# 30. Implementation PR breakdown

Phase 5 must be delivered in small reviewable increments.

## P05-PR1 — Risk V2 domain + parity scaffolding

Files:

```text
RiskOutcome.java
RiskRuleCode.java
RiskRuleResult.java
RiskDecisionClass.java
RiskContext.java
RiskDecisionV2.java
RiskRule.java
RiskPolicyEngine.java
LegacyRiskPolicyAdapter.java
```

Tests only; no production path switch.

## P05-PR2 — Persisted policy configuration

- migration `risk_policy_config`;
- snapshot/repository/service/bootstrap;
- policy API read path;
- activation concurrency tests.

## P05-PR3 — Existing-rule extraction

Extract existing behavior into rule classes:

```text
EmergencyStop
SignalTimestamp/Freshness
DailyLoss
PriceLevels
PerTradeRisk
OpenPositions
ExistingSymbol
Cash
Holdings
```

Run parity suite.

## P05-PR4 — New day-trader-derived/production rules

Implement:

```text
MinimumRewardRisk
MaxOrderNotional
GrossExposure
SymbolExposure
MaxTradesPerDay
MarketSession
DuplicatePendingExposure
MarketStateFreshness
InstrumentEligibility
```

Boundary tests required.

## P05-PR5 — Decision persistence V2

- extend `risk_decision`;
- add `risk_decision_rule`;
- persistence service;
- detailed API.

## P05-PR6 — RiskContextFactory + PAPER path switch

- build server-derived context;
- preserve account lock;
- switch PaperTradingService from direct `RiskInput/RiskPolicy` to V2 engine;
- full integration regression.

## P05-PR7 — Position sizing

- service;
- endpoint;
- unit/API tests;
- advisory UI component.

## P05-PR8 — Risk UI shared model

- hooks/query keys;
- policy/history/decision detail;
- utilization components;
- emergency-stop polish;
- retain current page behavior.

## P05-PR9 — Dual-design risk composition

Can be completed in Phase 9 if timing requires:

- Pro Terminal risk view;
- Clear Invest risk view;
- responsive/accessibility/visual tests.

## P05-PR10 — Observability + final hardening

- metrics/logging;
- concurrency tests;
- documentation;
- full CI;
- Phase 5 exit evidence.

---

# 31. Exact Phase 5 exit criteria

Phase 5 is complete only when all are true:

1. Existing risk behavior has parity/regression coverage.
2. All mandatory risk rules are independent deterministic rule classes.
3. Every order evaluation uses one immutable server-derived `RiskContext`.
4. Active risk policy is persisted and versioned.
5. Historical policy versions cannot be mutated/deleted by normal API.
6. Every decision stores exact policy identity/snapshot.
7. Every rule PASS/rejection result is persisted.
8. Minimum R:R is enforced.
9. Maximum order notional is enforced.
10. Maximum gross exposure is enforced.
11. Maximum symbol exposure is enforced.
12. Maximum trades/day is enforced.
13. Market-session gate is implemented/configurable.
14. Market-state freshness fails closed where required.
15. Duplicate pending exposure is blocked.
16. Position sizing is deterministic and advisory only.
17. Emergency stop remains non-overridable.
18. Rejected orders create no fill/cash/position mutation.
19. Idempotent replay creates no second risk decision.
20. Concurrent PAPER order tests prove no double-spend/duplicate exposure race.
21. Risk decision detail API exposes ordered rule evidence.
22. Risk UI exposes limits, utilization and rule explanations.
23. Both UI experiences use the same risk APIs/state.
24. No AI/news/research score can override a hard rejection.
25. Full backend/frontend/Docker/Terraform-required CI is green.

---

# 32. Explicit non-goals for Phase 5

Phase 5 does **not** implement:

- live broker order submission;
- margin/leverage;
- short selling;
- options/futures risk;
- VaR as an execution authority;
- AI-driven risk overrides;
- autonomous policy changes;
- Phase 7 approval queue;
- Phase 6 partial-fill accounting;
- production identity/RBAC beyond current operator-key boundary;
- offline order queue/replay.

Those remain in their assigned phases.

---

# 33. Final implementation rule

The Phase 5 source of truth is now:

```text
docs/MASTER_IMPLEMENTATION_PLAN.md
        |
        +-- Phase 5 scope/exit gate
                |
                +-- docs/PHASE_5_RISK_ENGINE_LOW_LEVEL_DESIGN.md
                    (authoritative implementation details)
```

If this file conflicts with the older Phase 5 section inside `MASTER_LOW_LEVEL_IMPLEMENTATION_DESIGN.md`, **this file wins for Phase 5** until the master low-level document is next consolidated.

Implementation must proceed by the `P05-PR*` sequence above unless a PR explicitly updates this design with repository evidence.

---

# Architecture review addendum — mandatory implementation refinements (2026-10-28)

These items are acceptance criteria for the existing Phase 5 PR sequence, not new phases. They close gaps found in a second design review.

## A. Define account and exposure scope explicitly

All risk queries and policy lookups must be scoped by immutable paper_account_id (and authenticated actor/tenant once Phase 10 lands). Do not aggregate across accounts. Define exposure as sum of absolute marked position notionals plus reserved pending BUY notionals, and cash available as ledger cash minus pending reservations. Document whether SELL reduces exposure only after fill; pending SELL must not free capacity early. Add multi-account isolation fixtures.

## B. Make the snapshot consistent under concurrency

Within the existing paper_account FOR UPDATE transaction, read positions, cash ledger, pending order reservations, daily trade count, halt state and active policy from one consistent database snapshot. Define lock ordering (account -> trading control -> pending intent/position), and avoid deadlocks with concurrent orders/emergency stop. Re-evaluate after approval and before fill. Add two-thread race tests for simultaneous BUYs, stop activation, and policy switch.

## C. Resolve risk semantics before implementation

Specify BUY versus SELL/exit behavior per rule in a decision matrix: exits must not be blocked by entry-only max-position, concentration, or min reward:risk rules; emergency stop must distinguish halt-new-entry from halt-all-execution; protective liquidation must use a separately audited safe path. Define trading-day boundary in Asia/Kolkata, inclusive/exclusive threshold comparisons, daily-loss calculation (realized vs unrealized and fee inclusion), and whether fees/slippage affect effective reward:risk and cash reservations. Never silently assume zero exposure on stale prices.

## D. Define price/quantity precision and reference freshness

Resolve exchange tick size and permitted quantity/lot size from instrument master; validate all prices on tick and all quantities on lot multiples. Define max quote/candle age per order type, holiday/market-session behavior, and reference price source priority. Mark stale/missing quote, incomplete candle and unrecognized instrument as explicit hard-reject codes. Avoid using client-submitted entry price as trusted market-state evidence.

## E. Policy schema lifecycle and migration safety

Implement policy DDL with check constraints for nonnegative limits, one active policy per account/scope (partial unique index), immutable activated versions, effective interval consistency, actor/reason/correlation, and explicit policy status DRAFT/ACTIVE/RETIRED. Backfill a V1-equivalent snapshot before switching reads. Stage migration as expand -> backfill -> dual-read/compare -> cutover -> contract; do not silently reinterpret old decisions under new policy.

## F. Evidence, replay and explainability

Persist a canonical context hash, market-data cutoff/source, instrument catalogue version, policy version, risk engine version, ordered per-rule outcomes, fee/slippage assumptions, observed/limit values and evaluation timestamp. Keep sensitive account details out of client responses and logs. Replaying a historical decision must use its stored snapshot, not current limits; parity test existing V1 outcomes before enabling V2.

## G. Position sizing as advisory, not authorization

Compute quantity by floor(min(risk budget / stop distance including costs, cash capacity, notional capacity, concentration capacity, gross exposure capacity)) then round down to valid lot multiple. Reject zero, invalid tick/lot, nonpositive stop distance and missing market state. UI must display limiting constraint and estimation timestamp; server recomputes all quantities/rules at submission.

## H. API security and concurrency contracts

Policy updates require If-Match/row version, reason, actor audit and idempotency; reject stale updates with 409/412. Policy preview/dry-run cannot reserve cash or create executable intents. Preserve emergency-stop endpoint behavior and make resume a privileged, audited operation. Introduce role restrictions as Phase 10 integration contracts rather than trusting frontend visibility.

## I. Dual UI acceptance details

Both Pro Terminal and Clear Invest must expose identical policy version, risk utilization, last evaluated timestamp, data freshness, per-rule reason codes, entry/exit distinction and emergency-stop state. Pro uses compact inspection; Clear uses guided explanations. Test keyboard/screen reader, mobile 320px, reduced motion, stale/error/empty, and experience switching during an in-flight risk preview without duplicating mutations.

## J. Quantitative operational gates

Add metrics for risk_evaluations_total{outcome,rule}, risk_evaluation_duration_seconds, risk_context_stale_total, risk_policy_activation_total, risk_context_lock_wait_seconds, and risk_decision_persistence_failures_total (bounded labels only). Performance target must be measured against realistic PostgreSQL fixtures and documented before a hard latency SLO is committed. Fail closed on unavailable policy/context/audit persistence; never record an approved order without durable risk evidence.

## K. Phase 5 implementation acceptance matrix

For each rule and order side: positive, negative, equality boundary, null/missing, stale, and concurrent-state cases. Mandatory cross-service tests: policy activation race, emergency stop race, two competing orders exhausting shared cash, duplicate pending intent, idempotent retry, approval revalidation, migration of legacy decisions, V1/V2 parity, account isolation, and no broker network call. No Phase 5 exit until all applicable cases pass in CI and any intentional V1 behavioral differences are documented.

## LIVE risk policy clarification — DDPI and AUTO (2026-10-28)

LIVE is the target product; PAPER/shadow validates the implementation. AUTO does not require routine human approval but never bypasses hard risk or broker authorization checks. Intraday closes and demat delivery sells require distinct sell-source classification. For eligible delivery sales, DDPI is planned but must be verified active and account-scoped before unattended execution; missing/unknown eligibility blocks the order with an explicit authorization reason. eDIS is an interactive fallback, not a headless substitute. Risk returns deterministic evidence and never directly places broker orders. Add test cases for DDPI active/inactive/unknown, stale verification, AUTO/MANUAL/HYBRID, cross-account isolation and kill switch. Connectivity checks remain deferred.

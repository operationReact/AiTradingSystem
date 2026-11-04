# CI isolation — risk policy lifecycle cannot leak into PAPER trade tests

The full Java 21/PostgreSQL suite executes `RiskPolicyConfigIT` before
`PaperTradeApiIT` on some GitHub runners. The former intentionally deletes
the risk-policy table in `@BeforeEach` and creates/activates versions with
different risk percentages, fees and timing to exercise governance. It
previously never restored the application bootstrap afterward.

Evidence from the failing exact-head PR #414 baseline job:
- `PaperTradeApiIT` expected a PAPER buy's fee to be `2.5` but observed
  `5.0`, revealing the inherited policy/fee context.
- Several budget-rejected order tests expected HTTP 422 but received HTTP 201.
- All failures were in `PaperTradeApiIT`, immediately after
  `RiskPolicyConfigIT` in the full-suite run. The synthetic inference
  smoke was disabled and skipped in that baseline job.

This PR adds a scoped `@AfterEach` to `RiskPolicyConfigIT` that deletes
its owned policy audit records, removes mutated test risk-policy versions and
re-runs the existing canonical `RiskPolicyBootstrap`, asserting that the
active restored risk percentage, fee and slippage exactly match the configured
application defaults. It changes **no production risk policy, trading
algorithm, exchange data, order routing, fee calculation or LIVE safeguard**.

Acceptance: the full Java 21/PostgreSQL baseline, Phase 4, Phase 8,
dependency/security and delivery-policy gates must pass on the exact head.
A passing test-suite is software evidence only, not broker or economic
acceptance. Regressions must not be resolved by weakening risk validation.

# PAPER automation risk authority

The new `PaperRiskDecisionService` expresses minimum product-level checks for the slice. It must not replace stricter authoritative risk controls already implemented in prior phases.

Production integration should expose one composition point where:

- authenticated account context is resolved;
- authoritative emergency-stop state is read;
- existing risk limits/exposure/approval rules execute;
- new customer mode/automation eligibility rules execute;
- only an accepted decision may proceed to persisted PAPER execution.

If existing risk policy rejects, automation must reject even if the new minimum checks would pass. The integration may tighten risk; it must not weaken it.

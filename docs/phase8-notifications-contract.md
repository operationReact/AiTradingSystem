# P08-PR03 — Durable operator notifications

`system_notification` persists severity, bounded operator-facing text, optional entity provenance, creation time and acknowledgement actor/time. `NotificationService.raise` implements one active incident per explicit global dedupe key, protected by a PostgreSQL partial unique index. Concurrent raises return the original active incident without silently changing its title, severity, text or first-occurrence provenance. After acknowledgement a later occurrence creates a new incident/ID.

Acknowledgement is an idempotent compare-and-set. Only the winning update emits its audit event, in the same transaction. Raising participates in the source transaction, so an event that rolls back does not leave a notification behind. Acknowledging an incident is not permission to trade and does not change risk controls or accounting.

Emergency-stop activation and reconciliation differences raise critical notifications in their existing transactions. Messages are fixed descriptions linking to persisted evidence; they do not copy provider exceptions, credentials or arbitrary request contents. Recovery does not silently acknowledge an incident: the operator retains that decision.

Provider/backfill failures, stale market state and research failures are projected from persisted component/job state in the operational-health allocation (P08-PR04). Notification list/detail/acknowledge HTTP routes are allocated to P08-PR06; this PR supplies the model, service, bounded query contract and immediate critical accounting/risk sources. Deployment incidents remain a later source as specified by the canonical plan.

PostgreSQL evidence covers concurrent deduplication, preserved original incidents, exactly-once acknowledgement audit, a new occurrence after acknowledgement, transaction rollback, input/query bounds and the emergency-stop source. This is an in-application notification model; it sends no email, chat message or push notification.

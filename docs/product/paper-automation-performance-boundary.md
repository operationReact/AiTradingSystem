# PAPER automation processing boundary

Automation should be asynchronous and idempotent once integrated with persisted production services. A customer UI request should not synchronously own a long-running market-monitoring loop.

Required properties:

- opportunity evaluation can be retried safely;
- accepted intent/order/fill transitions use stable idempotency identity;
- market-price monitoring updates authoritative position state without duplicate exits;
- account emergency stop is observed before new intents and during monitoring;
- restart/recovery rereads persisted state rather than relying on process memory;
- customer UI reads current state and can request pause/resume preferences without becoming the scheduler.

The current in-process foundation demonstrates lifecycle rules only; it is not the production scheduler or persistence mechanism.

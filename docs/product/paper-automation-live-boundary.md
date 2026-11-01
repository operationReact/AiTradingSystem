# Future LIVE boundary

LIVE exists in the product model only to make the customer mode architecture explicit. It is not enabled by this slice.

Current release invariants:

- `liveAvailable = false`;
- `brokerSubmissionAvailable = false`;
- selecting/requesting LIVE fails closed;
- Manual/Automated preference cannot override LIVE availability;
- PAPER simulation never imports or invokes Angel One order submission;
- Angel One work remains authentication/authorization and read-only account/market/broker data.

A future LIVE release requires a separate explicit approval and implementation gate covering broker order submission, regulatory/compliance requirements, stronger risk controls, reconciliation, incident controls, auditability and dedicated acceptance evidence. Nothing in this branch constitutes that approval.

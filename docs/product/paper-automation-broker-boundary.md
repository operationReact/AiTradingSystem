# Angel One boundary during PAPER automation release

Angel One integration is separate from PAPER simulation authority.

Allowed customer capabilities in the current release boundary:

- connect/authenticate/authorize an Angel One account;
- read connectivity state;
- read funds;
- read holdings;
- read positions;
- read order book and other approved read-only account data.

Not allowed in this release:

- submit/modify/cancel a LIVE broker order;
- treat Angel One connection as permission to enable LIVE;
- send PAPER simulated orders to Angel One;
- expose broker credentials/tokens to Flutter or PAPER simulation services.

PAPER automation must remain fully testable without an Angel One account connected.

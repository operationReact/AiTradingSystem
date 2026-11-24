# STR-176 signed source observation intake

Owner has confirmed authorization to proceed with licensed research acquisition.
This is recorded as an owner assertion, not a fabricated provider agreement,
terms digest or independently verified source-rights receipt.

The private `Fno176AttestedBatchIntake` entry point connects exact original
OHLCV, final OI and contract-master packets to the existing V102 append-only
ingestor. A trusted independent reviewer must sign the versioned receipt with
a separately pinned Ed25519 public key. The signed fields are the full
normalized VerifiedDay, three SHA-256 digests and review time. The signature
is over UTF-8 fields, each prefixed with a four-byte big-endian length, in the
order specified by signingBytes. Never distribute the reviewer private key
with a data bundle. A signature is only as trustworthy as the independent
reviewer and their underlying source inspection.

The batch is limited to 32 unique provider/contract/session identities.
Rows are counted as stored only if the existing database insert succeeds.
Partial rejection is explicit, not a full batch success. All existing
source-use rights, calendar, retention, exact-contract and append-only
checks remain. No order route is involved.

This is a forward-observed intake path. It records the current database
observation time, not the candle's historical session label. A previously
downloaded candle can be observed now but cannot become evidence of an
earlier historical decision. True archival point-in-time evidence requires
an additional source-supported availability/custody path, not a rewrite
of these timestamps.

No default trusted key, production receipt, automatic source grant,
scheduler, HTTP route, new cloud resource or deployment is included.
The independent reviewer and private runtime composition must be installed
before real inputs can be accepted. CI uses synthetic cryptographic fixtures,
never a real licensing or strategy certificate.

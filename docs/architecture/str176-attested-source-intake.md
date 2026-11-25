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


## Private runtime entry point

The opt-in `Fno176PrivateIntakeRunner` runs once at backend startup. Configure
`minttick.fno-research.private-intake-enabled=true`,
`minttick.fno-research.private-intake-directory` (absolute canonical private
directory, read-only mounted into the backend), and
`minttick.fno-research.private-intake-reviewer-public-key` (base64 DER X.509
Ed25519 public key pinned by the independent operator). It is disabled by
default; no new cloud resources, public routes or default trust keys.

The directory contains at most 32 subdirectories named entry-0001 through
entry-9999. Each contains receipt.json (the signed Receipt record, max 16 KiB),
ohlcv.bin, oi.bin (max 1 MiB each), listing.bin (max 20 MiB). Directory
and file modes must grant no group or other access; symlinks are rejected.
Keep the directory immutable for the duration of the import. Each entry
is verified and stored individually to bound memory. Repeated source identity
is rejected by the existing immutable database uniqueness constraint; no
overwrite or automatic deletion. Only aggregate counts/state are logged.
A restart with an already imported batch will report rejected duplicates,
not new successful imports.

The root may also contain one `foundation.json` (max 64 KiB). This is a
separate Ed25519 signed reviewer receipt that can append the exact six
provider/use rights records needed for OHLCV, final OI and the NSE calendar,
plus one calendar record for each session in the batch. It binds provider ID,
provider code and legal name, reviewer ID, review time, terms digests, validity
windows, source references, calendar status/revision, session close, original
publication/first-availability timestamps and evidence digests. The configured
reviewer public key must be pinned outside the bundle. Rights are restricted
to `RAW_RETENTION` and `MODEL_INFERENCE` for those three domains; display,
redistribution and training permissions cannot be added through this path.
Conflicting IDs, source identities, invalid validity windows, unsigned or
tampered packets, session mismatches and non-private files fail closed.

The signature uses the version tag `minttick.fno176.foundation.v1`, UTF-8
fields length-prefixed by four-byte big-endian lengths, with rights sorted by
`sourceDomain/permittedUse` and calendar records sorted by session date. The
two list counts are encoded as four-byte big-endian integers before their
entries. `Fno176AttestedFoundationIntake.signingBytes` is the canonical
reference implementation. Reviewers must verify the actual license/calendar
source material before signing; a user authorization, test key or generated
fixture is not sufficient. The foundation import never certifies a strategy
and never grants an order permission.

Before enabling, provision genuine verified rights/calendar records and the
independent reviewer's public key and signed source packets privately.
Neither a user assertion nor a test key is a production receipt. After a
real import, execute the existing read-only V102 source eligibility audit.
The result can establish source rows, not independent strategy certification.

# MintTick F&O-43 deployed read-only readiness (operational acceptance)

This workflow verifies what the **actual deployed PAPER EC2 backend** currently
reports for the existing operator endpoints. It does **not** run a strategy
in a market session, place a simulated or live order, attest data licensing,
read customer identity, or update trade/rights configuration.

Target: AWS account `098859530205`, region `ap-south-1`,
instance `i-09c01b470fb39b85a`.

## Exact read-only source

The reviewed one-file request
`infra/ec2-paper/requests/fno43-deployed-readiness.json` triggers
`minttick-fno43-deployed-readiness.yml`. The verified GitHub OIDC role
uses SSM Run Command only on this existing instance. The host reads
`APP_API_KEY` from the locally protected runtime environment and performs
two GET requests against **127.0.0.1**:

- `/api/admin/research/fno-43/readiness`
- `/api/admin/research/fno-43/source-evidence`

Only aggregate counts may be returned to GitHub:
source-level evaluator observations, rights-attested sources, historical E2E,
PAPER and LIVE E2E, and eligible original market envelopes. Each response
must have exactly 43 uniquely ordered strategy IDs, recognized contract
versions and valid integer counters. A provider exception, 401/403, 404,
oversized/malformed JSON, or unexpected broker order flag reports a fixed,
non-secret reason. The runtime.env API key and full response bodies never
leave EC2 or appear in logs.

**Interpretation:** If the runtime endpoints return valid results with
historical/paper/live all 0, that is confirmed *deployed observability*, not
strategy readiness. If 401/403, the operator API auth route is not yet
accessible with this key; do not weaken its security to force the test.
If 404, the deployed release may not contain the endpoint. Never mark
E2E green for a sandbox broker ACK or an offline Java evaluator unit test.

No deployed writes, AWS start/stop, provider API calls, market data export,
SSM decrypt of brokerage secrets, broker orders or app deployment.

Evidence is the specific two-step GitHub workflow run and its sanitized
`MINTTICK_FNO43_DEPLOYED_READINESS:` receipt.

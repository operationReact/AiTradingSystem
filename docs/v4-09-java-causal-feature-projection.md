# V4-09 — Causal Java feature projection from frozen market/news evidence

## Exact missing bridge delivered

The first operational V4-09 model inference needs actual numeric features derived from an already captured Java feed, rather than operator-submitted future-return labels or synthetic benchmark inputs. This increment freezes a deterministic **six-feature research vector** from the existing V59 PostgreSQL capture, a distinct *earlier available* market candle, and a pre-capture Java `news_ai_evidence` row.

Java ADMIN APIs:

| HTTP | Endpoint | Meaning |
| --- | --- | --- |
| POST | `/api/admin/model-governance/paper-shadow/sessions/{sessionId}/source-cases/{caseKey}/features` | Freeze causal six-feature vector once |
| GET | Same | Read back immutable numeric evidence |

Flyway `V60__v4_causal_model_feature_projection.sql` stores exact source-capture lineage, previous candle ID, chosen AI evidence ID, six numeric features, schema, original feature SHA-256 identity, server timing and actor. A repeated call is read-only idempotent if the source/data-derived fingerprint is unchanged; changed existing market/news prices, feature values or selected AI evidence fail closed. No copyrighted article text or live credentials are persisted in the projection table.

### Six exact features

The feature keys match `DEFAULT_BASELINE_FEATURES` used by the existing trained CatBoost / LightGBM challengers:

- `market.return_1 = current_candle.close / previous_candle.close - 1`
- `market.range_pct = (current_candle.high - current_candle.low) / current_candle.close`
- `market.volume_change_1 = current_candle.volume / previous_candle.volume - 1`
- `event.AI_NEWS_EVIDENCE.direction_score`
- `event.AI_NEWS_EVIDENCE.confidence`
- `event.AI_NEWS_EVIDENCE.materiality`

No value is filled with zero for missing history. The previous candle must match the exact exchange **instrument token** (not just the displayed trading-symbol text), exchange and interval; it must be at least one entire market-bar interval earlier than the current candle and have been fetched no later than the frozen V59 source capture. A missing full prior interval fails closed; reusing an unrelated instrument's history is forbidden. Fresh projection writes are refused after a V56 model decision already exists, preventing a retrospective feature backfill. Prices and volume must be positive/valid. The selected AI news evidence must relate to the exact V59 `news_id`, include the instrument, have source availability matching the original Java news record, and have been generated *before* the original capture. **Late Gemini/news AI cannot be inserted retrospectively into an earlier decision.**

Both current candle and news-item bytes are freshly hashed and compared with the V59 capture's immutable market/news/input/capture fingerprints before creating any numeric projection. All source and AI evidence times must fit the original frozen capture. The session must be in Java PAPER mode and ACTIVE under V58's shared row-lock safety gate.

### Endpoint output and next integration

The output contains `sourceInputFingerprint`, `featureAsOf`, `latestFeatureAvailableAt`, `features`, `featurePayloadFingerprint`, `featureSchemaVersion`, `symbol`, and immutable source IDs. These map to the Python PR #400 `bounded-artifact-research-inference.v1` request fields:

- `model_version_id`: from the enrolled Java V56 model version, **not** user-supplied weights
- `case_key` and `symbol`: from the frozen projection
- `source_input_fingerprint`: projection `sourceInputFingerprint`
- `feature_as_of`: projection `featureAsOf`
- `latest_feature_available_at`: projection `latestFeatureAvailableAt`
- `features`: six-key projection `features`

This does **not** yet invoke Python or claim inference execution. The next V4-09 PR must add the Java internal authenticated worker with bounded timeout, exact frozen model UUID/feature checks, immutable attempt evidence and no fabricated successful decisions on partial model failures.

### Tests and acceptance

Integration tests on PostgreSQL/Flyway + MockMvc assert real prior market-candle and `news_ai_evidence` rows produce a real six-feature JSON projection; exact replay; source hash change rejection; missing prior candle; missing or late post-capture AI evidence; PAUSE rejection; ADMIN-only access; permanent false authorization. No third-party API call, broker order, token or paid licensing is required.

This remains `javaDatabaseBound=true`, `sourceRightsVerified=false`, `independentlyVerifiedModelInference=false`, `canAuthorizeCapital=false`. A database capture is not source signature verification, news licensing, independent human sentiment or a profitable strategy. PAPER/SHADOW soak and LIVE remain incomplete. 

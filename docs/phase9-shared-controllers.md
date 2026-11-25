# P09-W03 shared page controllers — research/backtesting slice

`ResearchPage` owns scan inputs, active run, queries, mutations and diagnostics state. Pro/Clear compositions live under `components/research/pro` and `clear`; both use the semantic `shared/ResearchWorkspace`. The scan launcher is controlled, so changing composition does not reset interval, universe limit or strategy selection. Mutations remain guarded by the authenticated controller; failed scans preserve inputs for retry. Research query options cannot override the authentication gate after clearing a key.

`BacktestingPage` owns search/debounce, instrument selection, date/capital form, selected run, assumptions, walk-forward and holdout state, queries, mutations and downloads. Pro/Clear views use `shared/BacktestWorkspace`, which imports presentation components and formatters, not API clients/hooks. Request results are projected with `requestState`; actions are callbacks owned by the page. The form receives a connected boolean rather than the operator key. Walk-forward and holdout callbacks reject invalid/pending invocations before mutation. The existing historical methodology and research safety statements remain unchanged.

This is the first P09-W03 slice, not completion of the whole work item. Risk, approvals, orders, signals, overview, portfolio and remaining major domains still require controller/view reconciliation. Operations, notifications, audit and candidate-history routes established the initial pattern in P09-W01. The complete major-page checklist must be closed before P09-W03 is complete.

Browser evidence covers cross-composition scan form persistence, real candidateId navigation links, guarded scan submission and no anonymous research query after key clear. Existing backtest run/history/holdout/walk-forward/download/accessibility contracts run in the same gate as architecture, tokens and approval tests. HTTP fixtures are deterministic; real backend persistence remains separately verified. This refactor preserves existing semantic presentation, not final Figma screen/asset parity.

No schema, API authorization, execution/risk policy, deployment or LIVE activation changes are included. Rollback restores the former page compositions without changing persisted evidence.


## Risk and approvals slice

RiskPage now owns stop confirmation/reason/mutation state and decision pagination above Pro/Clear compositions. Shared evidence components contain no API clients or data hooks. Changing appearance no longer resets a cancelled stop review draft or decision page. Missing control evidence is shown as UNKNOWN, never OPEN/healthy; the stop mutation callback requires authenticated, known current control evidence and rejects pending duplicates. Backend authority remains unchanged.

ApprovalsPage owns queue/filter/page, reviewed immutable request/version, decision reason, outcomes and mutation. Presentation cards retain only their local display countdown. The confirmation dialog is outside the experience subtree so preference updates preserve the open review and focus boundary. Views receive a connected flag and request projections, not credentials or mutation functions. Final confirmation remains explicit and uses the reviewed row version. Queue failure offers a controller-owned retry.

Dedicated browser tests cover preserved risk review drafts, unknown-control behavior, an appearance context update during an open approval review, and unchanged expected-row-version/final-confirmation behavior. Remaining major domains (overview, signals, orders, portfolio and provider/settings surfaces) are still open before P09-W03 completion.

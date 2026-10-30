# P09-W03 controller inventory

Every existing major route owns its data/state above the experience boundary, directly or through route-owned controller hooks. Pro/Clear variants consume request projections and callbacks. Shared semantic presentation may use local display effects (countdown/chart rendering), never API clients or data hooks. Stable reusable order/policy-review controllers remain outside variant subtrees.

| Route/domain | Controller owner | Presentation |
| --- | --- | --- |
| Overview/news/provider/RSS | OverviewPage + useDashboardWidgets | dashboard pro/clear/shared and data-free widget views |
| Instruments | InstrumentsPage + useInstrumentMasterController | instruments pro/clear/shared + InstrumentMasterPanel |
| Market data/quality/calendar | MarketDataPage + historical/operations controllers | marketdata pro/clear/shared + data-free panels |
| Research scanner | ResearchPage | research pro/clear/shared |
| Candidate evidence | CandidateDetailPage | CandidateEvidenceView via ExperienceBoundary |
| Backtesting/holdout/walk-forward | BacktestingPage | backtest pro/clear/shared |
| Signals | SignalsPage | signals pro/clear/shared |
| PAPER orders/fills/detail | PaperTradesPage | orders pro/clear/shared; stable order draft/detail |
| Portfolio/accounting | PortfolioPage | portfolio pro/clear/shared |
| Risk policy/decisions/stop | RiskPage | risk pro/clear/shared; stable policy-review controller |
| Approvals | ApprovalsPage | approvals pro/clear/shared; stable final-confirmation dialog |
| Operations | OperationsPage | OperationsView via ExperienceBoundary |
| Notifications | NotificationsPage | NotificationsView via ExperienceBoundary |
| Audit | AuditPage | AuditView via ExperienceBoundary |
| Settings | SettingsPage + useApprovalSettingsController | settings pro/clear/shared + data-free ApprovalPolicySettings |

The final setup slice lifts all instrument filters/import state, historical search/selection/window/backfill state, operations evaluation/reconciliation/calendar state and approval-settings queries/mutations into route-owned hooks. Authentication and pending guards remain controller callbacks; instrument IDs require safe positive integers. Switching appearance never imports a catalogue, starts/resumes a backfill, evaluates/reconciles market data or changes policy mode. Input labels for operations fields are explicitly associated.

Browser contracts prove instrument filters, separate historical/operations selections and explicit-only settings mutations survive switching. Existing 35 browser contracts cover the other controller slices; complete current contracts run in P9 CI. W03 is complete only after the final setup slice is validated and merged; this document is an implementation inventory, not a Phase 9 exit certificate.

Next allocation is P09-W04 screen inventory: finish each required feature in both designs against canonical Figma, including full candidate/scenario/provenance, analytics and action/state parity. P09-W05–W09 still cover full state, preference, responsive, accessibility and connected E2E exit evidence. No deployment, provider call or LIVE activation is implied.

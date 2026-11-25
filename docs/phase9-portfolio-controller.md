# P09-W03 portfolio controller slice

PortfolioPage owns the canonical portfolio/position/fill/cash/reconciliation queries and mutation. Pro/Clear views consume request projections and guarded callbacks through ExperienceBoundary. Reconciliation never runs as a consequence of switching appearance. Missing operator credentials render an explicit notice, and a cleared key removes reconciliation authority. Recoverable query errors expose retry callbacks.

This completes the portfolio controller slice of the existing PR #150. The same change preserves its validated overview preference/range guards and signal identity protections. A selected instrument absent from a refreshed result set is left unselected; it is never silently replaced by the first result. The form presents a selection placeholder and disables evaluation until a current instrument is chosen. Signals/open-order callbacks require current authenticated evidence and reject duplicate evaluation requests.

Local production build and three portfolio browser contracts verify existing accounting evidence, responsive/a11y checks, design switching and exactly one explicit reconciliation. A negative signal-identity browser contract simulates a catalogue refresh while retaining the selected ID. CI runs the complete current frontend contract directory against the combined branch, including the separate risk/approval controller and backend clock correction. Browser APIs use fixtures; backend PostgreSQL evidence is separate.

P09-W03 remains open for orders/signals presentation, overview and remaining provider/settings domains. Full Figma screen composition, all data states and the complete responsive matrix remain subsequent allocations. No backend, schema, deployment or LIVE activation is added by this slice.

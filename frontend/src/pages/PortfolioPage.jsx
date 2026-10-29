import { Notice, Panel } from '../components/ui.jsx';
import {
  CashLedgerTable,
  ExposureChart,
  FillBreakdown,
  PnLSummary,
  PositionTable,
  ReconciliationBanner,
} from '../components/PaperExecutionComponents.jsx';
import {
  useCanonicalPortfolio,
  usePaperCashLedger,
  usePaperFills,
  usePaperPositions,
  usePaperReconciliationRuns,
  useRunPaperReconciliation,
} from '../hooks/usePaperApi.js';

function QueryFailure({ title, query }) {
  if (!query.isError) return null;
  return <Notice tone="loss" title={title}>{query.error?.message || 'Request failed'}</Notice>;
}

export default function PortfolioPage() {
  const portfolio = useCanonicalPortfolio();
  const positions = usePaperPositions();
  const fills = usePaperFills();
  const cash = usePaperCashLedger();
  const reconciliation = usePaperReconciliationRuns();
  const runReconciliation = useRunPaperReconciliation();
  const pending = [portfolio, positions, fills, cash, reconciliation].some((q) => q.isPending);

  return (
    <div className="flex flex-col gap-5">
      <div>
        <h1 className="text-lg font-semibold text-text">Paper portfolio</h1>
        <p className="mt-0.5 text-xs text-muted">Persisted PAPER cash, positions, fills and reconciliation evidence. No live broker execution.</p>
      </div>

      <QueryFailure title="Could not load portfolio" query={portfolio} />
      <QueryFailure title="Could not load positions" query={positions} />
      <QueryFailure title="Could not load fills" query={fills} />
      <QueryFailure title="Could not load cash ledger" query={cash} />
      <QueryFailure title="Could not load reconciliation history" query={reconciliation} />

      {pending && <div className="h-28 animate-pulse rounded-lg bg-surface-2" />}

      {portfolio.data && (
        <>
          <ReconciliationBanner portfolio={portfolio.data} runs={reconciliation.data} mutation={runReconciliation} />
          <PnLSummary portfolio={portfolio.data} />
          <ExposureChart portfolio={portfolio.data} />
        </>
      )}

      <Panel><h2 className="mb-3 text-sm font-semibold text-text">Positions</h2>{positions.data && <PositionTable rows={positions.data} />}</Panel>
      <Panel><h2 className="mb-3 text-sm font-semibold text-text">Recent fills</h2>{fills.data && <FillBreakdown rows={fills.data} />}</Panel>
      <Panel><h2 className="mb-3 text-sm font-semibold text-text">Cash ledger</h2>{cash.data && <CashLedgerTable rows={cash.data} />}</Panel>
    </div>
  );
}

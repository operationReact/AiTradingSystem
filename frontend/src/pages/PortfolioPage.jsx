import { RefreshCw } from 'lucide-react';
import { Badge, Button, DataTable, EmptyState, Notice, Panel } from '../components/ui.jsx';
import { formatDateTime, formatInr } from '../lib/format.js';
import {
  useCanonicalPortfolio,
  usePaperCashLedger,
  usePaperFills,
  usePaperPositions,
  usePaperReconciliationRuns,
  useRunPaperReconciliation,
} from '../hooks/usePaperApi.js';

function Metric({ label, value, tone }) {
  return (
    <div className="rounded-lg border border-border bg-surface-2 p-3">
      <p className="text-[11px] uppercase tracking-wider text-muted">{label}</p>
      <p className={`num mt-1 text-lg font-semibold ${tone === 'gain' ? 'text-gain' : tone === 'loss' ? 'text-loss' : 'text-text'}`}>{value}</p>
    </div>
  );
}

function PnLSummary({ portfolio }) {
  return (
    <div className="grid grid-cols-2 gap-2 lg:grid-cols-4">
      <Metric label="Equity" value={formatInr(portfolio.equity)} />
      <Metric label="Cash" value={formatInr(portfolio.cash)} />
      <Metric label="Realized P&L" value={formatInr(portfolio.realizedPnl)} tone={Number(portfolio.realizedPnl) >= 0 ? 'gain' : 'loss'} />
      <Metric label="Unrealized P&L" value={formatInr(portfolio.unrealizedPnl)} tone={Number(portfolio.unrealizedPnl) >= 0 ? 'gain' : 'loss'} />
    </div>
  );
}

function ExposureChart({ portfolio }) {
  const exposure = Math.max(0, Math.min(100, Number(portfolio.exposurePercent || 0)));
  return (
    <Panel>
      <div className="flex items-center justify-between gap-3">
        <div>
          <h2 className="text-sm font-semibold text-text">Gross exposure</h2>
          <p className="text-xs text-muted">Market value relative to current paper equity</p>
        </div>
        <span className="num text-lg font-semibold text-text">{exposure.toFixed(2)}%</span>
      </div>
      <div className="mt-3 h-2 overflow-hidden rounded-full bg-surface-2" role="img" aria-label={`Gross exposure ${exposure.toFixed(2)} percent`}>
        <div className="h-full rounded-full bg-accent transition-[width]" style={{ width: `${exposure}%` }} />
      </div>
      <div className="mt-3 grid grid-cols-2 gap-2 text-xs text-muted sm:grid-cols-4">
        <span>Invested <b className="text-text">{formatInr(portfolio.invested)}</b></span>
        <span>Market value <b className="text-text">{formatInr(portfolio.marketValue)}</b></span>
        <span>Positions <b className="text-text">{portfolio.openPositions}</b></span>
        <span>Marks <b className="text-text">{portfolio.markPriceSource}</b></span>
      </div>
    </Panel>
  );
}

function PositionTable({ rows }) {
  const columns = [
    { key: 'symbol', header: 'Symbol', cell: (r) => <span className="font-medium">{r.symbol}</span> },
    { key: 'productType', header: 'Product', cell: (r) => <Badge tone="neutral">{r.productType}</Badge> },
    { key: 'quantity', header: 'Qty', align: 'right' },
    { key: 'averageCost', header: 'Average', align: 'right', cell: (r) => formatInr(r.averageCost) },
    { key: 'markPrice', header: 'Mark', align: 'right', cell: (r) => formatInr(r.markPrice) },
    { key: 'marketValue', header: 'Value', align: 'right', cell: (r) => formatInr(r.marketValue) },
    { key: 'unrealizedPnl', header: 'Unrealized P&L', align: 'right', cell: (r) => <span className={Number(r.unrealizedPnl) >= 0 ? 'text-gain' : 'text-loss'}>{formatInr(r.unrealizedPnl)}</span> },
  ];
  return <DataTable columns={columns} rows={rows} rowKey={(r) => `${r.instrumentId ?? 'legacy'}:${r.symbol}:${r.productType}`} minWidth={760} empty={<EmptyState title="No open paper positions" />} />;
}

function FillBreakdown({ rows }) {
  const columns = [
    { key: 'filledAt', header: 'Filled', cell: (r) => formatDateTime(r.filledAt) },
    { key: 'symbol', header: 'Symbol' },
    { key: 'side', header: 'Side', cell: (r) => <Badge tone={r.side === 'BUY' ? 'gain' : 'loss'}>{r.side}</Badge> },
    { key: 'productType', header: 'Product' },
    { key: 'quantity', header: 'Qty', align: 'right' },
    { key: 'fillPrice', header: 'Price', align: 'right', cell: (r) => formatInr(r.fillPrice) },
    { key: 'feeAmount', header: 'Fees', align: 'right', cell: (r) => formatInr(r.feeAmount) },
    { key: 'realizedPnl', header: 'Realized P&L', align: 'right', cell: (r) => formatInr(r.realizedPnl) },
  ];
  return <DataTable columns={columns} rows={rows} rowKey={(r) => r.id} minWidth={760} empty={<EmptyState title="No paper fills recorded" />} />;
}

function CashLedgerTable({ rows }) {
  const columns = [
    { key: 'createdAt', header: 'Time', cell: (r) => formatDateTime(r.createdAt) },
    { key: 'entryType', header: 'Entry' },
    { key: 'symbol', header: 'Symbol', cell: (r) => r.symbol || '—' },
    { key: 'productType', header: 'Product', cell: (r) => r.productType || '—' },
    { key: 'amount', header: 'Amount', align: 'right', cell: (r) => <span className={Number(r.amount) >= 0 ? 'text-gain' : 'text-loss'}>{formatInr(r.amount)}</span> },
    { key: 'balanceAfter', header: 'Balance after', align: 'right', cell: (r) => formatInr(r.balanceAfter) },
  ];
  return <DataTable columns={columns} rows={rows} rowKey={(r) => r.id} minWidth={720} empty={<EmptyState title="No cash ledger entries" />} />;
}

function ReconciliationBanner({ portfolio, runs, mutation }) {
  const latest = runs?.[0];
  const clean = portfolio.cashReconciled && (!latest || latest.status === 'CLEAN');
  return (
    <Notice
      tone={clean ? 'gain' : 'warn'}
      title={clean ? 'Paper ledger reconciled' : 'Paper ledger needs attention'}
      action={<Button size="sm" variant="ghost" icon={RefreshCw} disabled={mutation.isPending} onClick={() => mutation.mutate()}>Run reconciliation</Button>}
    >
      Cash ledger: {portfolio.cashReconciled ? 'balanced' : 'mismatch'}.
      {latest ? ` Latest run ${latest.status} with ${latest.differenceCount} difference(s), ${formatDateTime(latest.completedAt || latest.startedAt)}.` : ' No reconciliation run recorded yet.'}
    </Notice>
  );
}

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

      <Panel>
        <h2 className="mb-3 text-sm font-semibold text-text">Positions</h2>
        {positions.data && <PositionTable rows={positions.data} />}
      </Panel>

      <Panel>
        <h2 className="mb-3 text-sm font-semibold text-text">Recent fills</h2>
        {fills.data && <FillBreakdown rows={fills.data} />}
      </Panel>

      <Panel>
        <h2 className="mb-3 text-sm font-semibold text-text">Cash ledger</h2>
        {cash.data && <CashLedgerTable rows={cash.data} />}
      </Panel>
    </div>
  );
}

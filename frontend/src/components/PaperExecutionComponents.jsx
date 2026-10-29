import { RefreshCw } from 'lucide-react';
import { Badge, Button, DataTable, EmptyState, Notice, Panel } from './ui.jsx';
import { formatDateTime, formatInr } from '../lib/format.js';

const STATUS_TONE = {
  NEW: 'neutral', RISK_APPROVED: 'accent', APPROVAL_PENDING: 'warn', READY: 'accent',
  PARTIALLY_FILLED: 'warn', FILLED: 'gain', REJECTED: 'loss', CANCELLED: 'neutral',
  EXPIRED: 'warn', FAILED: 'loss',
};
const FLOW = ['NEW', 'RISK_APPROVED', 'APPROVAL_PENDING', 'READY', 'PARTIALLY_FILLED', 'FILLED'];

export function OrderStatusTimeline({ order }) {
  const status = order?.status;
  const terminalFailure = ['REJECTED', 'CANCELLED', 'EXPIRED', 'FAILED'].includes(status);
  const currentIndex = FLOW.indexOf(status);
  return (
    <div className="flex flex-col gap-2" aria-label={`Order status ${status || 'unknown'}`}>
      <div className="flex flex-wrap gap-1.5">
        {FLOW.filter((step) => step !== 'APPROVAL_PENDING' || status === 'APPROVAL_PENDING').map((step, index) => {
          const reached = !terminalFailure && (currentIndex >= FLOW.indexOf(step) || status === 'FILLED');
          return <Badge key={step} tone={reached ? STATUS_TONE[step] : 'neutral'}>{step}</Badge>;
        })}
        {terminalFailure && <Badge tone={STATUS_TONE[status]}>{status}</Badge>}
      </div>
      {order?.rejectionReason && <p className="text-xs text-loss">{order.rejectionReason}</p>}
    </div>
  );
}

export function FillBreakdown({ rows = [] }) {
  const columns = [
    { key: 'filledAt', header: 'Filled', cell: (r) => formatDateTime(r.filledAt) },
    { key: 'symbol', header: 'Symbol' },
    { key: 'side', header: 'Side', cell: (r) => <Badge tone={r.side === 'BUY' ? 'gain' : 'loss'}>{r.side}</Badge> },
    { key: 'productType', header: 'Product' },
    { key: 'fillSequence', header: 'Fill #', align: 'right', cell: (r) => r.fillSequence ?? '—' },
    { key: 'quantity', header: 'Qty', align: 'right' },
    { key: 'fillPrice', header: 'Price', align: 'right', cell: (r) => formatInr(r.fillPrice) },
    { key: 'feeAmount', header: 'Fees', align: 'right', cell: (r) => formatInr(r.feeAmount) },
    { key: 'realizedPnl', header: 'Realized P&L', align: 'right', cell: (r) => <span className={Number(r.realizedPnl) >= 0 ? 'text-gain' : 'text-loss'}>{formatInr(r.realizedPnl)}</span> },
  ];
  return <DataTable columns={columns} rows={rows} rowKey={(r) => r.id} minWidth={820} empty={<EmptyState title="No paper fills recorded" />} />;
}

export function PositionTable({ rows = [] }) {
  const columns = [
    { key: 'symbol', header: 'Symbol', cell: (r) => <span className="font-medium">{r.symbol}</span> },
    { key: 'exchange', header: 'Exchange', cell: (r) => r.exchange || '—' },
    { key: 'productType', header: 'Product', cell: (r) => <Badge tone="neutral">{r.productType}</Badge> },
    { key: 'quantity', header: 'Qty', align: 'right' },
    { key: 'averageCost', header: 'Average', align: 'right', cell: (r) => formatInr(r.averageCost) },
    { key: 'markPrice', header: 'Mark', align: 'right', cell: (r) => formatInr(r.markPrice) },
    { key: 'marketValue', header: 'Value', align: 'right', cell: (r) => formatInr(r.marketValue) },
    { key: 'unrealizedPnl', header: 'Unrealized P&L', align: 'right', cell: (r) => <span className={Number(r.unrealizedPnl) >= 0 ? 'text-gain' : 'text-loss'}>{formatInr(r.unrealizedPnl)}</span> },
  ];
  return <DataTable columns={columns} rows={rows} rowKey={(r) => `${r.instrumentId ?? 'legacy'}:${r.symbol}:${r.productType}`} minWidth={820} empty={<EmptyState title="No open paper positions" />} />;
}

function Metric({ label, value, tone }) {
  return (
    <div className="rounded-lg border border-border bg-surface-2 p-3">
      <p className="text-[11px] uppercase tracking-wider text-muted">{label}</p>
      <p className={`num mt-1 text-lg font-semibold ${tone === 'gain' ? 'text-gain' : tone === 'loss' ? 'text-loss' : 'text-text'}`}>{value}</p>
    </div>
  );
}

export function PnLSummary({ portfolio }) {
  if (!portfolio) return null;
  return (
    <div className="grid grid-cols-2 gap-2 lg:grid-cols-4">
      <Metric label="Equity" value={formatInr(portfolio.equity)} />
      <Metric label="Cash" value={formatInr(portfolio.cash)} />
      <Metric label="Realized P&L" value={formatInr(portfolio.realizedPnl)} tone={Number(portfolio.realizedPnl) >= 0 ? 'gain' : 'loss'} />
      <Metric label="Unrealized P&L" value={formatInr(portfolio.unrealizedPnl)} tone={Number(portfolio.unrealizedPnl) >= 0 ? 'gain' : 'loss'} />
    </div>
  );
}

export function ExposureChart({ portfolio }) {
  if (!portfolio) return null;
  const exposure = Math.max(0, Math.min(100, Number(portfolio.exposurePercent || 0)));
  return (
    <Panel>
      <div className="flex items-center justify-between gap-3">
        <div><h2 className="text-sm font-semibold text-text">Gross exposure</h2><p className="text-xs text-muted">Market value relative to current paper equity</p></div>
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

export function CashLedgerTable({ rows = [] }) {
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

export function ReconciliationBanner({ portfolio, runs = [], mutation }) {
  if (!portfolio) return null;
  const latest = runs?.[0];
  const clean = portfolio.cashReconciled && (!latest || latest.status === 'CLEAN');
  return (
    <Notice
      tone={clean ? 'gain' : 'warn'}
      title={clean ? 'Paper ledger reconciled' : 'Paper ledger needs attention'}
      action={mutation ? <Button size="sm" variant="ghost" icon={RefreshCw} disabled={mutation.isPending} onClick={() => mutation.mutate()}>Run reconciliation</Button> : null}
    >
      Cash ledger: {portfolio.cashReconciled ? 'balanced' : 'mismatch'}.
      {latest ? ` Latest run ${latest.status} with ${latest.differenceCount} difference(s), ${formatDateTime(latest.completedAt || latest.startedAt)}.` : ' No reconciliation run recorded yet.'}
    </Notice>
  );
}

import { Link, useNavigate } from 'react-router-dom';
import { Download } from 'lucide-react';
import { DataTable, Badge, Button, EmptyState, Notice, Panel } from '../ui.jsx';
import { DataState } from '../DataState.jsx';
import { downloadCsv } from '../../lib/csv.js';
import { formatDateTime, formatInr, formatSignedInr, pnlTone } from '../../lib/format.js';

const STATUS_TONE = { FILLED: 'gain', RISK_APPROVED: 'accent', NEW: 'neutral', REJECTED: 'loss' };

const signalTone = (status) => (status === 'APPROVED' ? 'gain' : status === 'REJECTED' ? 'loss' : 'neutral');

export function SignalsPanel({ query }) {
  const navigate = useNavigate();
  const columns = [
    { key: 'symbol', header: 'Symbol', cell: (r) => <span className="font-medium">{r.symbol}</span> },
    { key: 'action', header: 'Action', cell: (r) => <Badge tone={String(r.action).includes('SELL') ? 'loss' : 'gain'}>{r.action}</Badge> },
    { key: 'confidence', header: 'Confidence', align: 'right', cell: (r) => (Number.isFinite(Number(r.confidence)) ? Number(r.confidence).toFixed(2) : '—') },
    { key: 'referencePrice', header: 'Ref. price', align: 'right', cell: (r) => formatInr(r.referencePrice) },
    { key: 'status', header: 'Status', cell: (r) => <Badge tone={signalTone(r.status)}>{r.status}</Badge> },
  ];
  return (
    <Panel
      title="Highest-ranked signals"
      subtitle="Persisted deterministic candidates; not executable orders"
      actions={<Badge tone="warn">Research only</Badge>}
    >
      <DataState query={query}>
        {(signals) => {
          const top = [...signals].sort((a, b) => Number(b.confidence) - Number(a.confidence)).slice(0, 5);
          return (
            <div className="flex flex-col gap-2">
              <DataTable
                columns={columns}
                rows={top}
                rowKey={(r) => r.id}
                pageSize={5}
                onRowClick={() => navigate('/signals')}
                empty={<EmptyState title="No signals" description="Signals appear once the strategy analyst produces candidates." />}
              />
              <div className="flex justify-end">
                <Link to="/signals" className="text-xs text-accent hover:underline">All signals</Link>
              </div>
            </div>
          );
        }}
      </DataState>
    </Panel>
  );
}

export function PositionsPanel({ query }) {
  const navigate = useNavigate();
  const columns = [
    { key: 'symbol', header: 'Symbol', cell: (r) => <span className="font-medium">{r.symbol}</span> },
    { key: 'quantity', header: 'Qty', align: 'right' },
    { key: 'averageCost', header: 'Avg cost', align: 'right', cell: (r) => formatInr(r.averageCost) },
    { key: 'markPrice', header: 'Last fill', align: 'right', cell: (r) => formatInr(r.markPrice) },
    { key: 'unrealizedPnl', header: 'Unrealized', align: 'right', cell: (r) => <span className={pnlTone(r.unrealizedPnl)}>{formatSignedInr(r.unrealizedPnl)}</span> },
    { key: 'stopLoss', header: 'Stop', align: 'right', cell: (r) => formatInr(r.stopLoss) },
    { key: 'target', header: 'Target', align: 'right', cell: (r) => formatInr(r.target) },
  ];
  return (
    <Panel
      title="Open paper positions"
      subtitle="Marked at last paper fill (no live quotes)"
      actions={
        <Button
          size="sm"
          variant="ghost"
          icon={Download}
          disabled={!query.data?.positions?.length}
          onClick={() => downloadCsv('paper-positions.csv', columns.map((c) => ({ label: c.header, value: (r) => r[c.key] })), query.data.positions)}
        >
          CSV
        </Button>
      }
    >
      <DataState query={query}>
        {(portfolio) => (
          <DataTable
            columns={columns}
            rows={portfolio.positions}
            rowKey={(r) => r.symbol}
            pageSize={6}
            onRowClick={(r) => navigate(`/trades?symbol=${encodeURIComponent(r.symbol)}`)}
            empty={<EmptyState title="No open positions" description="Approved paper orders will appear here after they fill." />}
          />
        )}
      </DataState>
    </Panel>
  );
}

export function TradesPanel({ query }) {
  const navigate = useNavigate();
  const columns = [
    { key: 'symbol', header: 'Symbol', cell: (r) => <span className="font-medium">{r.symbol}</span> },
    { key: 'side', header: 'Side', cell: (r) => <Badge tone={r.side === 'SELL' ? 'loss' : 'gain'}>{r.side}</Badge> },
    { key: 'quantity', header: 'Qty', align: 'right' },
    { key: 'entryPrice', header: 'Entry', align: 'right', cell: (r) => formatInr(r.entryPrice) },
    { key: 'status', header: 'Status', cell: (r) => <Badge tone={STATUS_TONE[r.status] ?? 'neutral'}>{r.status}</Badge> },
    { key: 'createdAt', header: 'Created', cell: (r) => formatDateTime(r.createdAt) },
  ];
  return (
    <Panel title="Recent paper trades" subtitle="Latest order requests and their outcome">
      <DataState query={query}>
        {(page) => (
          <DataTable
            columns={columns}
            rows={page.items}
            rowKey={(r) => r.id}
            pageSize={8}
            onRowClick={(r) => navigate(`/trades?symbol=${encodeURIComponent(r.symbol)}`)}
            empty={<EmptyState title="No paper trades yet" description="Simulate an order from the Signals page to start." />}
          />
        )}
      </DataState>
    </Panel>
  );
}

/** Risk warnings derived from live summary, portfolio and risk-config data. */
export function RiskAlerts({ summary, portfolio, config }) {
  const alerts = [];
  if (summary.newOrdersHalted) alerts.push({ tone: 'loss', text: 'Emergency stop is active. New paper orders are halted.' });
  if (summary.cashReconciled === false) alerts.push({ tone: 'loss', text: 'Cash reconciliation failed. Investigate the ledger before trading.' });
  if (config) {
    const dailyLimit = (Number(config.startingCapital) * Number(config.maxDailyDrawdownPercent)) / 100;
    if (Number(summary.dailyPnl) <= -dailyLimit) alerts.push({ tone: 'loss', text: `Daily loss limit reached (${formatInr(dailyLimit)}).` });
    if (summary.openPositions >= config.maxOpenPositions) {
      alerts.push({ tone: 'warn', text: `At the open-position limit (${summary.openPositions} of ${config.maxOpenPositions}).` });
    }
  }
  const ageMinutes = (Date.now() - new Date(summary.asOf).getTime()) / 60000;
  if (Number.isFinite(ageMinutes) && ageMinutes > 5) alerts.push({ tone: 'warn', text: 'Dashboard snapshot is more than 5 minutes old.' });
  if (portfolio?.positions?.length && summary.markPriceSource) {
    alerts.push({ tone: 'neutral', text: 'Position values use the last paper fill, not live market prices.' });
  }

  if (alerts.length === 0) return <p className="text-xs text-muted">No active risk warnings.</p>;
  return (
    <ul className="flex flex-col gap-2">
      {alerts.map((a) => (
        <li key={a.text}>
          <Notice tone={a.tone}>{a.text}</Notice>
        </li>
      ))}
    </ul>
  );
}

export function AgentsPanel({ agents, unavailable }) {
  return (
    <Panel title="Agent status" subtitle="Module health reported by the backend">
      <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
        {agents.map((agent) => (
          <div key={agent.id} className="flex flex-col gap-1 rounded-md border border-border bg-surface-2/50 p-3">
            <div className="flex items-center justify-between gap-2">
              <span className="truncate text-sm font-medium text-text">{agent.name}</span>
              <Badge tone={agent.status === 'READY' || agent.status === 'OK' ? 'gain' : 'neutral'}>{agent.status}</Badge>
            </div>
            <p className="text-xs text-muted">{agent.description}</p>
          </div>
        ))}
      </div>
      <p className="mt-3 text-xs text-muted">{unavailable}</p>
    </Panel>
  );
}

export function ProvidersPanel({ providers }) {
  return (
    <Panel title="Provider connectivity" subtitle="Status is reported only when a check has run">
      <ul className="divide-y divide-border">
        {providers.map((p) => (
          <li key={p.name} className="flex items-center justify-between gap-3 py-2 text-xs">
            <div className="min-w-0">
              <p className="truncate font-medium text-text">{p.name}</p>
              <p className="text-muted">Last successful check: {p.lastSuccessfulCheck ? formatDateTime(p.lastSuccessfulCheck) : 'never'}</p>
            </div>
            <Badge tone={p.status === 'CONNECTED' ? 'gain' : 'warn'}>{p.status}</Badge>
          </li>
        ))}
      </ul>
    </Panel>
  );
}


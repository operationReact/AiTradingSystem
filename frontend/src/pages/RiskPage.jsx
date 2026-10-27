import { useState } from 'react';
import { OctagonX, PlayCircle } from 'lucide-react';
import { useDashboardSummary, useEmergencyStop, useRiskConfig, useRiskDecisions } from '../hooks/useApi.js';
import { DataState } from '../components/DataState.jsx';
import { Badge, Button, DataTable, EmptyState, KpiCard, Modal, Notice, Panel } from '../components/ui.jsx';
import { useApp } from '../context/AppContext.jsx';
import { formatDateTime, formatInr, formatPct } from '../lib/format.js';

const PAGE_SIZE = 10;

function LimitsPanel({ query }) {
  return (
    <DataState query={query} skeleton={<div className="grid grid-cols-2 gap-3 lg:grid-cols-4"><div className="h-20 animate-pulse rounded bg-surface-2" /><div className="h-20 animate-pulse rounded bg-surface-2" /><div className="h-20 animate-pulse rounded bg-surface-2" /><div className="h-20 animate-pulse rounded bg-surface-2" /></div>}>
      {(cfg) => (
        <div className="flex flex-col gap-3">
          <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
            <KpiCard label="Starting capital" value={formatInr(cfg.startingCapital)} hint="Paper account" />
            <KpiCard label="Risk per trade" value={formatPct(cfg.riskPerTradePercent)} hint={`Budget ${formatInr((cfg.startingCapital * cfg.riskPerTradePercent) / 100)}`} />
            <KpiCard label="Max daily drawdown" value={formatPct(cfg.maxDailyDrawdownPercent)} hint={`Limit ${formatInr((cfg.startingCapital * cfg.maxDailyDrawdownPercent) / 100)}`} />
            <KpiCard label="Max open positions" value={cfg.maxOpenPositions} hint={`Signals stale after ${cfg.signalMaxAgeMinutes} min`} />
          </div>
          <div className="flex flex-wrap items-center gap-2 text-xs text-muted">
            <Badge tone="accent">{cfg.tradingMode}</Badge>
            <Badge tone={cfg.liveTradingEnabled ? 'loss' : 'gain'}>{cfg.liveTradingEnabled ? 'Live enabled' : 'Live trading disabled'}</Badge>
            <span>Policy {cfg.policyVersion} · fee {formatPct(cfg.feeRate * 100)} · slippage {formatPct(cfg.slippageRate * 100)}</span>
          </div>
        </div>
      )}
    </DataState>
  );
}

function EmergencyStopPanel({ summary }) {
  const { apiKey } = useApp();
  const stop = useEmergencyStop();
  const [confirming, setConfirming] = useState(false);
  const [reason, setReason] = useState('');

  const halted = !!summary.data?.newOrdersHalted;
  const nextHalted = !halted;

  const submit = () => {
    stop.mutate(
      { halted: nextHalted, reason: reason.trim() || undefined },
      {
        onSuccess: () => {
          setConfirming(false);
          setReason('');
        },
      },
    );
  };

  return (
    <Panel title="Emergency stop" subtitle="Blocks new paper orders while active. Existing positions are not closed.">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <div className="flex items-center gap-2">
          <Badge tone={halted ? 'loss' : 'gain'}>{halted ? 'New orders halted' : 'Accepting new orders'}</Badge>
          {!summary.data && summary.isPending && <span className="text-xs text-muted">Loading status…</span>}
        </div>
        <Button
          variant={halted ? 'secondary' : 'danger'}
          icon={halted ? PlayCircle : OctagonX}
          disabled={!apiKey || summary.isPending}
          onClick={() => setConfirming(true)}
        >
          {halted ? 'Resume new orders' : 'Halt new orders'}
        </Button>
      </div>
      {stop.isError && (
        <div className="mt-3">
          <Notice tone="loss" title="Could not change the stop state">{stop.error?.message}</Notice>
        </div>
      )}

      <Modal
        open={confirming}
        onOpenChange={(open) => !open && setConfirming(false)}
        title={nextHalted ? 'Halt new paper orders?' : 'Resume new paper orders?'}
        description="This affects paper execution only. No broker orders are involved."
        footer={
          <>
            <Button onClick={() => setConfirming(false)}>Cancel</Button>
            <Button variant={nextHalted ? 'danger' : 'primary'} loading={stop.isPending} onClick={submit}>
              Confirm
            </Button>
          </>
        }
      >
        <label className="flex flex-col gap-1 text-xs text-muted">
          Reason (optional, 200 characters max)
          <input
            value={reason}
            maxLength={200}
            onChange={(e) => setReason(e.target.value)}
            className="h-9 rounded-md border border-border bg-surface-2 px-2.5 text-sm text-text focus:border-accent focus:outline-none"
          />
        </label>
      </Modal>
    </Panel>
  );
}

function DecisionsPanel() {
  const [page, setPage] = useState(0);
  const decisions = useRiskDecisions({ page, size: PAGE_SIZE });

  const columns = [
    { key: 'evaluatedAt', header: 'Evaluated', cell: (r) => formatDateTime(r.evaluatedAt) },
    { key: 'orderId', header: 'Order', cell: (r) => <span className="text-xs text-muted">{String(r.orderId).slice(0, 8)}</span> },
    { key: 'decision', header: 'Decision', cell: (r) => <Badge tone={r.decision === 'APPROVED' ? 'gain' : 'loss'}>{r.decision}</Badge> },
    { key: 'estimatedRisk', header: 'Est. risk', align: 'right', cell: (r) => formatInr(r.estimatedRisk) },
    { key: 'riskBudget', header: 'Budget', align: 'right', cell: (r) => formatInr(r.riskBudget) },
    { key: 'availableCash', header: 'Cash', align: 'right', cell: (r) => formatInr(r.availableCash) },
    { key: 'openPositions', header: 'Open pos.', align: 'right' },
    { key: 'reasonCodes', header: 'Reasons', cell: (r) => (r.reasonCodes?.length ? r.reasonCodes.join(', ') : '—') },
  ];

  return (
    <Panel title="Risk decisions" subtitle="Every paper order is evaluated by the deterministic policy before execution.">
      <DataState query={decisions} skeleton={<div className="h-48 w-full animate-pulse rounded bg-surface-2" />}>
        {(data) => (
          <div className="flex flex-col gap-3">
            {data.items.length === 0 ? (
              <EmptyState title="No risk decisions yet" description="Decisions appear after the first paper order is submitted." />
            ) : (
              <DataTable columns={columns} rows={data.items} rowKey={(r) => r.id} pageSize={data.items.length} minWidth={860} />
            )}
            {data.totalPages > 1 && (
              <div className="flex items-center justify-between text-xs text-muted">
                <span>Page {data.page + 1} of {data.totalPages} · {data.totalItems} decisions</span>
                <div className="flex gap-1">
                  <Button size="sm" variant="ghost" disabled={data.page === 0} onClick={() => setPage(page - 1)}>Previous</Button>
                  <Button size="sm" variant="ghost" disabled={data.page + 1 >= data.totalPages} onClick={() => setPage(page + 1)}>Next</Button>
                </div>
              </div>
            )}
          </div>
        )}
      </DataState>
    </Panel>
  );
}

export default function RiskPage() {
  const config = useRiskConfig();
  const summary = useDashboardSummary();
  const { apiKey } = useApp();

  return (
    <div className="flex flex-col gap-5">
      <div>
        <h1 className="text-lg font-semibold text-text">Risk management</h1>
        <p className="mt-0.5 text-xs text-muted">Deterministic limits enforced on the backend. AI recommendations cannot override them.</p>
      </div>

      {!apiKey && <Notice tone="neutral" title="Operator key required">Enter the operator API key in the top bar to load risk data.</Notice>}

      <Panel title="Limits" subtitle="Values come from validated backend configuration.">
        <LimitsPanel query={config} />
      </Panel>

      {apiKey && <EmergencyStopPanel summary={summary} />}

      <DecisionsPanel />
    </div>
  );
}

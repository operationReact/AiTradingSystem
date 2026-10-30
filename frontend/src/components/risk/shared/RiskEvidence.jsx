import { OctagonX, PlayCircle } from 'lucide-react';
import { DataState } from '../../DataState.jsx';
import { Badge, Button, DataTable, EmptyState, KpiCard, Modal, Notice, Panel } from '../../ui.jsx';
import { formatDateTime, formatInr, formatPct } from '../../../lib/format.js';
import { POLICY_FIELDS, policyDisplay, utilization } from '../../../lib/riskPresentation.js';

export function PolicyStatus({ policy, summary, compact = false }) {
  const snapshot = policy?.snapshot;
  const dailyLoss = Math.max(0, -Number(summary?.dailyPnl || 0));
  const dailyLossLimit = Number(summary?.capital || 0) * Number(snapshot?.maxDailyDrawdownPercent || 0) / 100;
  const dailyUtilization = utilization(dailyLoss, dailyLossLimit);
  const grossUtilization = utilization(summary?.exposurePercent, snapshot?.maxGrossExposurePercent);

  return (
    <div className={`grid gap-3 ${compact ? 'grid-cols-2 xl:grid-cols-5' : 'grid-cols-1 sm:grid-cols-2 xl:grid-cols-5'}`}>
      <KpiCard label="Execution mode" value={summary?.mode || '—'} hint={summary?.liveTradingEnabled ? 'LIVE enabled' : 'PAPER safety boundary'} />
      <KpiCard label="Emergency stop" value={typeof summary?.newOrdersHalted !== 'boolean' ? 'UNKNOWN' : summary.newOrdersHalted ? 'HALTED' : 'OPEN'} hint={typeof summary?.newOrdersHalted !== 'boolean' ? 'Control evidence unavailable' : summary.newOrdersHalted ? 'New orders blocked' : 'New orders accepted'} />
      <KpiCard label="Policy version" value={policy ? `v${policy.version}` : '—'} hint={policy?.policyName || 'Active immutable policy'} />
      <KpiCard label="Daily loss use" value={formatPct(dailyUtilization)} hint={`${formatInr(dailyLoss)} of ${formatInr(dailyLossLimit)}`} />
      <KpiCard label="Gross exposure use" value={formatPct(grossUtilization)} hint={`${formatPct(Number(summary?.exposurePercent || 0))} of ${formatPct(Number(snapshot?.maxGrossExposurePercent || 0))}`} />
    </div>
  );
}

export function LimitsGrid({ policy, clear = false }) {
  const snapshot = policy?.snapshot;
  if (!snapshot) return null;
  return (
    <div className={`grid gap-3 ${clear ? 'grid-cols-1 sm:grid-cols-2' : 'grid-cols-2 lg:grid-cols-5'}`}>
      {POLICY_FIELDS.map(([key, label, unit]) => (
        <KpiCard key={key} label={label} value={policyDisplay(snapshot[key], unit)} hint={clear ? 'Backend-enforced hard limit' : undefined} />
      ))}
    </div>
  );
}

export function EmergencyStopPanel({ summary, controller, clear = false }) {
  const { connected, stop, confirming, setConfirming, reason, setReason, halted, nextHalted, haltReasonValid, submit } = controller;
  return (
    <Panel
      title={clear ? 'Pause new trades' : 'Emergency stop'}
      subtitle={clear ? 'Use this when you want the system to reject every new PAPER trade.' : 'Blocks new PAPER orders while active. Existing positions are not closed.'}
    >
      <div className="flex flex-wrap items-center justify-between gap-4">
        <div className="flex items-center gap-2">
          <Badge tone={typeof summary.data?.newOrdersHalted !== 'boolean' ? 'neutral' : halted ? 'loss' : 'gain'}>{typeof summary.data?.newOrdersHalted !== 'boolean' ? 'Stop state unknown' : halted ? 'New orders halted' : 'Accepting new orders'}</Badge>
          {!summary.data && summary.isPending && <span className="text-xs text-muted">Loading status…</span>}
        </div>
        <Button
          variant={halted ? 'secondary' : 'danger'}
          icon={halted ? PlayCircle : OctagonX}
          disabled={!connected || summary.isPending || summary.isError || stop.isPending || typeof summary.data?.newOrdersHalted !== 'boolean'}
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
        title={nextHalted ? 'Halt new PAPER orders?' : 'Resume new PAPER orders?'}
        description="This affects PAPER execution only. No broker orders are involved."
        footer={
          <>
            <Button onClick={() => setConfirming(false)}>Cancel</Button>
            <Button variant={nextHalted ? 'danger' : 'primary'} disabled={!connected || summary.isPending || summary.isError || stop.isPending || typeof summary.data?.newOrdersHalted !== 'boolean' || !haltReasonValid} loading={stop.isPending} onClick={submit}>
              Confirm
            </Button>
          </>
        }
      >
        <label className="flex flex-col gap-1 text-xs text-muted">
          {nextHalted ? 'Reason (required, 3–200 characters)' : 'Reason (optional, 200 characters max)'}
          <input
            value={reason}
            maxLength={200}
            onChange={(e) => setReason(e.target.value)}
            className="h-9 rounded-md border border-border bg-surface-2 px-2.5 text-sm text-text focus:border-accent focus:outline-none"
          />
        </label>
        {nextHalted && reason.length > 0 && !haltReasonValid && (
          <p className="mt-2 text-xs text-loss">Enter at least 3 non-space characters.</p>
        )}
      </Modal>
    </Panel>
  );
}

export function DecisionsPanel({ controller, clear = false }) {
  const { decisions, setPage } = controller;

  const columns = [
    { key: 'evaluatedAt', header: 'Evaluated', cell: (r) => formatDateTime(r.evaluatedAt) },
    { key: 'orderId', header: 'Order', cell: (r) => <span className="text-xs text-muted">{String(r.orderId).slice(0, 8)}</span> },
    { key: 'decision', header: 'Decision', cell: (r) => <Badge tone={r.decision === 'APPROVED' ? 'gain' : 'loss'}>{r.resultClass || r.decision}</Badge> },
    { key: 'estimatedRisk', header: 'Est. risk', align: 'right', cell: (r) => formatInr(r.estimatedRisk) },
    { key: 'riskBudget', header: 'Budget', align: 'right', cell: (r) => formatInr(r.riskBudget) },
    { key: 'availableCash', header: 'Cash', align: 'right', cell: (r) => formatInr(r.availableCash) },
    { key: 'openPositions', header: 'Open pos.', align: 'right' },
    { key: 'reasonCodes', header: 'Reasons', cell: (r) => (r.reasonCodes?.length ? r.reasonCodes.join(', ') : '—') },
  ];

  return (
    <Panel
      title={clear ? 'Why trades were accepted or blocked' : 'Risk decisions'}
      subtitle={clear ? 'Each row records the deterministic checks applied before a PAPER trade.' : 'Every PAPER order is evaluated by the deterministic policy before execution.'}
    >
      <DataState query={decisions} skeleton={<div className="h-48 w-full animate-pulse rounded bg-surface-2" />}>
        {(data) => (
          <div className="flex flex-col gap-3">
            {data.items.length === 0 ? (
              <EmptyState title="No risk decisions yet" description="Decisions appear after the first PAPER order is submitted." />
            ) : (
              <DataTable columns={columns} rows={data.items} rowKey={(r) => r.id} pageSize={data.items.length} minWidth={860} />
            )}
            {data.totalPages > 1 && (
              <div className="flex items-center justify-between text-xs text-muted">
                <span>Page {data.page + 1} of {data.totalPages} · {data.totalItems} decisions</span>
                <div className="flex gap-1">
                  <Button size="sm" variant="ghost" disabled={data.page === 0} onClick={() => setPage((p) => p - 1)}>Previous</Button>
                  <Button size="sm" variant="ghost" disabled={data.page + 1 >= data.totalPages} onClick={() => setPage((p) => p + 1)}>Next</Button>
                </div>
              </div>
            )}
          </div>
        )}
      </DataState>
    </Panel>
  );
}


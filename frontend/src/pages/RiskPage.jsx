import { useMemo, useState } from 'react';
import { OctagonX, PencilLine, PlayCircle, ShieldCheck } from 'lucide-react';
import {
  useCreateRiskPolicy,
  useDashboardSummary,
  useEmergencyStop,
  useRiskDecisions,
  useRiskPolicy,
} from '../hooks/useApi.js';
import { DataState } from '../components/DataState.jsx';
import { Badge, Button, DataTable, EmptyState, KpiCard, Modal, Notice, Panel } from '../components/ui.jsx';
import { useApp } from '../context/AppContext.jsx';
import { formatDateTime, formatInr, formatPct } from '../lib/format.js';

const PAGE_SIZE = 10;

const POLICY_FIELDS = [
  ['riskPerTradePercent', 'Risk per trade', '%'],
  ['maxDailyDrawdownPercent', 'Max daily drawdown', '%'],
  ['maxOpenPositions', 'Max open positions', 'count'],
  ['minRewardRiskRatio', 'Minimum reward:risk', 'ratio'],
  ['maxOrderNotional', 'Max order notional', 'money'],
  ['maxGrossExposurePercent', 'Max gross exposure', '%'],
  ['maxSymbolExposurePercent', 'Max symbol exposure', '%'],
  ['maxTradesPerDay', 'Max trades/day', 'count'],
  ['signalMaxAgeMinutes', 'Signal max age', 'minutes'],
  ['marketStateMaxAgeSeconds', 'Market-state max age', 'seconds'],
];

function toNumber(value) {
  const number = Number(value);
  return Number.isFinite(number) ? number : null;
}

function policyDisplay(value, unit) {
  if (value == null) return '—';
  if (unit === '%') return formatPct(Number(value));
  if (unit === 'money') return formatInr(Number(value));
  if (unit === 'ratio') return `${Number(value).toFixed(2)} R`;
  if (unit === 'minutes') return `${value} min`;
  if (unit === 'seconds') return `${value} sec`;
  return String(value);
}

function utilization(value, limit) {
  const observed = Math.max(0, Number(value || 0));
  const maximum = Number(limit || 0);
  if (!Number.isFinite(maximum) || maximum <= 0) return 0;
  return Math.min(100, (observed / maximum) * 100);
}

function PolicyStatus({ policy, summary, compact = false }) {
  const snapshot = policy?.snapshot;
  const dailyLoss = Math.max(0, -Number(summary?.dailyPnl || 0));
  const dailyLossLimit = Number(summary?.capital || 0) * Number(snapshot?.maxDailyDrawdownPercent || 0) / 100;
  const dailyUtilization = utilization(dailyLoss, dailyLossLimit);
  const grossUtilization = utilization(summary?.exposurePercent, snapshot?.maxGrossExposurePercent);

  return (
    <div className={`grid gap-3 ${compact ? 'grid-cols-2 xl:grid-cols-5' : 'grid-cols-1 sm:grid-cols-2 xl:grid-cols-5'}`}>
      <KpiCard label="Execution mode" value={summary?.mode || '—'} hint={summary?.liveTradingEnabled ? 'LIVE enabled' : 'PAPER safety boundary'} />
      <KpiCard label="Emergency stop" value={summary?.newOrdersHalted ? 'HALTED' : 'OPEN'} hint={summary?.newOrdersHalted ? 'New orders blocked' : 'New orders accepted'} />
      <KpiCard label="Policy version" value={policy ? `v${policy.version}` : '—'} hint={policy?.policyName || 'Active immutable policy'} />
      <KpiCard label="Daily loss use" value={formatPct(dailyUtilization)} hint={`${formatInr(dailyLoss)} of ${formatInr(dailyLossLimit)}`} />
      <KpiCard label="Gross exposure use" value={formatPct(grossUtilization)} hint={`${formatPct(Number(summary?.exposurePercent || 0))} of ${formatPct(Number(snapshot?.maxGrossExposurePercent || 0))}`} />
    </div>
  );
}

function LimitsGrid({ policy, clear = false }) {
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

function EmergencyStopPanel({ summary, clear = false }) {
  const { apiKey } = useApp();
  const stop = useEmergencyStop();
  const [confirming, setConfirming] = useState(false);
  const [reason, setReason] = useState('');

  const halted = !!summary.data?.newOrdersHalted;
  const nextHalted = !halted;
  const haltReasonValid = !nextHalted || reason.trim().length >= 3;

  const submit = () => {
    // Never submit against an unknown/stale UI state or duplicate an in-flight stop request.
    if (!apiKey || !summary.data || summary.isPending || summary.isError || stop.isPending || !haltReasonValid) return;
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
    <Panel
      title={clear ? 'Pause new trades' : 'Emergency stop'}
      subtitle={clear ? 'Use this when you want the system to reject every new PAPER trade.' : 'Blocks new PAPER orders while active. Existing positions are not closed.'}
    >
      <div className="flex flex-wrap items-center justify-between gap-4">
        <div className="flex items-center gap-2">
          <Badge tone={halted ? 'loss' : 'gain'}>{halted ? 'New orders halted' : 'Accepting new orders'}</Badge>
          {!summary.data && summary.isPending && <span className="text-xs text-muted">Loading status…</span>}
        </div>
        <Button
          variant={halted ? 'secondary' : 'danger'}
          icon={halted ? PlayCircle : OctagonX}
          disabled={!apiKey || !summary.data || summary.isPending || summary.isError || stop.isPending}
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
            <Button variant={nextHalted ? 'danger' : 'primary'} disabled={!apiKey || !summary.data || summary.isPending || summary.isError || stop.isPending || !haltReasonValid} loading={stop.isPending} onClick={submit}>
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

function DecisionsPanel({ clear = false }) {
  const [page, setPage] = useState(0);
  const decisions = useRiskDecisions({ page, size: PAGE_SIZE });

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

function PolicyEditor({ policy }) {
  const createPolicy = useCreateRiskPolicy();
  const [open, setOpen] = useState(false);
  const [reviewing, setReviewing] = useState(false);
  const [form, setForm] = useState(null);

  const begin = () => {
    if (!policy?.snapshot) return;
    setForm({ policyName: `${policy.policyName} v${policy.version + 1}`, ...policy.snapshot });
    setReviewing(false);
    setOpen(true);
  };

  const changes = useMemo(() => {
    if (!form || !policy?.snapshot) return [];
    return POLICY_FIELDS.filter(([key]) => String(form[key]) !== String(policy.snapshot[key]));
  }, [form, policy]);

  const invalid = !form || !form.policyName?.trim() || POLICY_FIELDS.some(([key]) => toNumber(form[key]) == null || toNumber(form[key]) < 0);

  const activate = () => {
    if (invalid || !form) return;
    const body = {
      policyName: form.policyName.trim(),
      riskPerTradePercent: toNumber(form.riskPerTradePercent),
      maxDailyDrawdownPercent: toNumber(form.maxDailyDrawdownPercent),
      maxOpenPositions: toNumber(form.maxOpenPositions),
      minRewardRiskRatio: toNumber(form.minRewardRiskRatio),
      maxOrderNotional: toNumber(form.maxOrderNotional),
      maxGrossExposurePercent: toNumber(form.maxGrossExposurePercent),
      maxSymbolExposurePercent: toNumber(form.maxSymbolExposurePercent),
      maxTradesPerDay: toNumber(form.maxTradesPerDay),
      signalMaxAgeMinutes: toNumber(form.signalMaxAgeMinutes),
      marketStateMaxAgeSeconds: toNumber(form.marketStateMaxAgeSeconds),
      enforceMarketSession: !!form.enforceMarketSession,
      rejectExistingSymbolPosition: !!form.rejectExistingSymbolPosition,
      rejectDuplicatePendingExposure: !!form.rejectDuplicatePendingExposure,
      feeRate: Number(form.feeRate),
      slippageRate: Number(form.slippageRate),
      feeSlippageProfileVersion: form.feeSlippageProfileVersion,
    };
    createPolicy.mutate(body, {
      onSuccess: () => {
        setOpen(false);
        setReviewing(false);
      },
    });
  };

  return (
    <>
      <Button icon={PencilLine} variant="secondary" disabled={!policy} onClick={begin}>Create new policy version</Button>
      <Modal
        open={open}
        onOpenChange={(next) => !next && setOpen(false)}
        title={reviewing ? 'Activate a new immutable policy version?' : 'Create risk policy version'}
        description={reviewing
          ? 'The active policy is never edited in place. Confirming creates and activates a new server-versioned policy.'
          : `Draft values are cloned from active policy v${policy?.version ?? '—'}. The server remains the validation authority.`}
        footer={
          reviewing ? (
            <>
              <Button onClick={() => setReviewing(false)}>Back</Button>
              <Button variant="primary" loading={createPolicy.isPending} disabled={invalid || changes.length === 0} onClick={activate}>Activate new version</Button>
            </>
          ) : (
            <>
              <Button onClick={() => setOpen(false)}>Cancel</Button>
              <Button variant="primary" disabled={invalid || changes.length === 0} onClick={() => setReviewing(true)}>Review changes</Button>
            </>
          )
        }
      >
        {form && !reviewing && (
          <div className="flex max-h-[60vh] flex-col gap-3 overflow-y-auto pr-1">
            <label className="flex flex-col gap-1 text-xs text-muted">
              Policy name
              <input value={form.policyName} onChange={(e) => setForm((f) => ({ ...f, policyName: e.target.value }))} className="h-9 rounded-md border border-border bg-surface-2 px-2.5 text-sm text-text focus:border-accent focus:outline-none" />
            </label>
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
              {POLICY_FIELDS.map(([key, label, unit]) => (
                <label key={key} className="flex flex-col gap-1 text-xs text-muted">
                  {label} {unit !== 'count' ? `(${unit})` : ''}
                  <input type="number" min="0" step="any" value={form[key]} onChange={(e) => setForm((f) => ({ ...f, [key]: e.target.value }))} className="h-9 rounded-md border border-border bg-surface-2 px-2.5 text-sm text-text focus:border-accent focus:outline-none" />
                </label>
              ))}
            </div>
            <div className="grid gap-2 text-xs text-muted sm:grid-cols-3">
              {[
                ['enforceMarketSession', 'Enforce market session'],
                ['rejectExistingSymbolPosition', 'Reject existing symbol'],
                ['rejectDuplicatePendingExposure', 'Reject duplicate pending exposure'],
              ].map(([key, label]) => (
                <label key={key} className="flex items-center gap-2 rounded border border-border bg-surface-2 p-2.5">
                  <input type="checkbox" checked={!!form[key]} onChange={(e) => setForm((f) => ({ ...f, [key]: e.target.checked }))} />
                  {label}
                </label>
              ))}
            </div>
          </div>
        )}

        {form && reviewing && (
          <div className="flex max-h-[55vh] flex-col gap-3 overflow-y-auto">
            <Notice tone="neutral" title="Immutable version activation">Existing policy v{policy.version} and its historical decisions remain unchanged.</Notice>
            {changes.length === 0 ? (
              <EmptyState title="No numeric limit changes" description="Change at least one enforced limit before activation." />
            ) : (
              <div className="flex flex-col divide-y divide-border rounded border border-border">
                {changes.map(([key, label, unit]) => (
                  <div key={key} className="grid grid-cols-[1fr_auto_auto] items-center gap-3 p-3 text-xs">
                    <span className="text-text">{label}</span>
                    <span className="text-muted line-through">{policyDisplay(policy.snapshot[key], unit)}</span>
                    <span className="font-medium text-accent">{policyDisplay(form[key], unit)}</span>
                  </div>
                ))}
              </div>
            )}
          </div>
        )}

        {createPolicy.isError && <div className="mt-3"><Notice tone="loss" title="Policy activation rejected">{createPolicy.error?.message}</Notice></div>}
      </Modal>
    </>
  );
}

function ProTerminalRiskView({ policy, summary }) {
  return (
    <div className="flex flex-col gap-5">
      <PolicyStatus policy={policy} summary={summary.data} compact />
      <Panel title="Hard risk limits" subtitle="Dense view of the active immutable policy used immediately before execution.">
        <LimitsGrid policy={policy} />
      </Panel>
      <EmergencyStopPanel summary={summary} />
      <DecisionsPanel />
    </div>
  );
}

function ClearInvestRiskView({ policy, summary }) {
  const snapshot = policy?.snapshot;
  const healthy = !summary.data?.newOrdersHalted && summary.data?.cashReconciled !== false;
  return (
    <div className="flex flex-col gap-5">
      <Panel title="Your risk health" subtitle="The same backend risk rules, explained in simpler language.">
        <div className="flex flex-wrap items-center justify-between gap-4">
          <div>
            <div className="flex items-center gap-2">
              <ShieldCheck className="h-5 w-5 text-accent" />
              <span className="text-base font-semibold text-text">{healthy ? 'Protection checks are active' : 'Attention required'}</span>
            </div>
            <p className="mt-1 text-xs text-muted">Policy v{policy?.version ?? '—'} limits each trade, daily losses, exposure and trading frequency before a PAPER order can execute.</p>
          </div>
          <Badge tone={healthy ? 'gain' : 'loss'}>{healthy ? 'Risk controls healthy' : 'Risk controls restricted'}</Badge>
        </div>
      </Panel>
      <PolicyStatus policy={policy} summary={summary.data} />
      <Panel title="What the system will not exceed" subtitle="These are enforced limits, not AI recommendations.">
        <LimitsGrid policy={policy} clear />
        {snapshot && <p className="mt-3 text-xs text-muted">Signals older than {snapshot.signalMaxAgeMinutes} minutes and market state older than {snapshot.marketStateMaxAgeSeconds} seconds fail the freshness checks.</p>}
      </Panel>
      <EmergencyStopPanel summary={summary} clear />
      <DecisionsPanel clear />
    </div>
  );
}

export default function RiskPage() {
  const policy = useRiskPolicy();
  const summary = useDashboardSummary();
  const { apiKey, experience } = useApp();

  return (
    <div className="flex flex-col gap-5">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-lg font-semibold text-text">Risk management</h1>
          <p className="mt-0.5 text-xs text-muted">Deterministic limits are backend-enforced. AI and research evidence cannot override a hard risk rejection.</p>
        </div>
        {apiKey && policy.data && <PolicyEditor policy={policy.data} />}
      </div>

      {!apiKey && <Notice tone="neutral" title="Operator key required">Enter the operator API key in the top bar to load risk data.</Notice>}

      {apiKey && (
        <DataState query={policy} skeleton={<div className="h-28 w-full animate-pulse rounded bg-surface-2" />}>
          {(activePolicy) => experience === 'clear-invest'
            ? <ClearInvestRiskView policy={activePolicy} summary={summary} />
            : <ProTerminalRiskView policy={activePolicy} summary={summary} />}
        </DataState>
      )}
    </div>
  );
}

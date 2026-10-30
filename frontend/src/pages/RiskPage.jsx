import { useMemo, useState } from 'react';
import { PencilLine } from 'lucide-react';
import { useCreateRiskPolicy, useDashboardSummary, useEmergencyStop, useRiskDecisions, useRiskPolicy } from '../hooks/useApi.js';
import { DataState } from '../components/DataState.jsx';
import { Badge, Button, Modal, Notice, Panel } from '../components/ui.jsx';
import { useApp } from '../context/AppContext.jsx';
import { POLICY_FIELDS, toNumber, policyDisplay } from '../lib/riskPresentation.js';
import { requestState } from '../lib/viewModel.js';
import ExperienceBoundary from '../components/experience/ExperienceBoundary.jsx';
import ProTerminalRiskView from '../components/risk/pro/ProTerminalRiskView.jsx';
import ClearInvestRiskView from '../components/risk/clear/ClearInvestRiskView.jsx';

function useEmergencyStopController(summary) {
  const { apiKey } = useApp();
  const stop = useEmergencyStop();
  const [confirming, setConfirming] = useState(false);
  const [reason, setReason] = useState('');

  const halted = !!summary.data?.newOrdersHalted;
  const nextHalted = !halted;
  const haltReasonValid = !nextHalted || reason.trim().length >= 3;

  const submit = () => {
    if (!apiKey || !haltReasonValid || stop.isPending || summary.isPending || typeof summary.data?.newOrdersHalted !== 'boolean' || summary.isError) return;
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

  return { connected: !!apiKey, stop: requestState(stop), confirming, setConfirming, reason, setReason, halted,
    nextHalted, haltReasonValid, submit };
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

export default function RiskPage() {
  const policy = useRiskPolicy();
  const summary = useDashboardSummary();
  const { apiKey } = useApp();

  const stopController = useEmergencyStopController(summary);
  const [decisionPage, setDecisionPage] = useState(0);
  const decisions = useRiskDecisions({ page: decisionPage, size: 10 });
  const decisionsController = { decisions: { ...requestState(decisions), refetch: () => decisions.refetch() }, setPage: setDecisionPage };
  const summaryView = requestState(summary);

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
          {(activePolicy) => <ExperienceBoundary
            pro={<ProTerminalRiskView policy={activePolicy} summary={summaryView} stopController={stopController} decisionsController={decisionsController} />}
            clear={<ClearInvestRiskView policy={activePolicy} summary={summaryView} stopController={stopController} decisionsController={decisionsController} />}
          />}
        </DataState>
      )}
    </div>
  );
}

import { useEffect, useMemo, useState } from 'react';
import { CheckCircle2, Clock3, ShieldCheck, XCircle } from 'lucide-react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiFetch } from '../lib/api.js';
import { useApp } from '../context/AppContext.jsx';
import { Badge, Button, EmptyState, KpiCard, Modal, Notice, Panel, SegmentedControl } from '../components/ui.jsx';

const PAGE_SIZE = 20;
const FILTERS = ['PENDING', 'APPROVED', 'REJECTED', 'CANCELLED', 'EXPIRED', 'AUTO_APPROVED'];

function toneForState(state) {
  if (state === 'APPROVED' || state === 'AUTO_APPROVED') return 'gain';
  if (state === 'REJECTED' || state === 'EXPIRED' || state === 'CANCELLED') return 'loss';
  return 'warn';
}

function useCountdown(expiresAt) {
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    if (!expiresAt) return undefined;
    const id = setInterval(() => setNow(Date.now()), 1_000);
    return () => clearInterval(id);
  }, [expiresAt]);
  if (!expiresAt) return '—';
  const seconds = Math.max(0, Math.ceil((new Date(expiresAt).getTime() - now) / 1000));
  if (seconds <= 0) return 'Expired';
  const minutes = Math.floor(seconds / 60);
  return `${minutes}:${String(seconds % 60).padStart(2, '0')}`;
}

function snapshotObject(raw) {
  if (!raw) return {};
  if (typeof raw === 'object') return raw;
  try { return JSON.parse(raw); } catch { return { raw }; }
}

function ApprovalCard({ approval, onDecision, clear }) {
  const countdown = useCountdown(approval.expiresAt);
  const snapshot = useMemo(() => snapshotObject(approval.requestSnapshot), [approval.requestSnapshot]);
  const pending = approval.state === 'PENDING';
  const policyMode = snapshot.approvalMode || snapshot.policyMode || 'MANUAL';

  return (
    <article className="rounded-lg border border-border bg-surface p-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0 space-y-1">
          <div className="flex flex-wrap items-center gap-2">
            <Badge tone={toneForState(approval.state)}>{approval.state}</Badge>
            <Badge tone="accent">{policyMode}</Badge>
            <span className="text-xs text-muted">Policy v{approval.policyVersion}</span>
          </div>
          <p className="truncate text-sm font-medium text-text">Order {String(approval.orderId).slice(0, 12)}</p>
          <p className="text-xs text-muted">Approval {String(approval.id).slice(0, 12)} · requested {new Date(approval.requestedAt).toLocaleString()}</p>
        </div>
        <div className="text-right">
          <p className="flex items-center justify-end gap-1 text-xs text-muted"><Clock3 className="h-3.5 w-3.5" aria-hidden /> Expires in</p>
          <p className={`num text-lg font-semibold ${countdown === 'Expired' ? 'text-loss' : 'text-text'}`}>{countdown}</p>
        </div>
      </div>

      <div className={`mt-4 grid gap-2 ${clear ? 'grid-cols-1 sm:grid-cols-2' : 'grid-cols-2 lg:grid-cols-4'}`}>
        <KpiCard label="Symbol" value={snapshot.symbol || snapshot.tradingSymbol || '—'} />
        <KpiCard label="Side / product" value={[snapshot.side, snapshot.productType].filter(Boolean).join(' · ') || '—'} />
        <KpiCard label="Quantity" value={snapshot.quantity ?? snapshot.submittedQuantity ?? '—'} />
        <KpiCard label="Notional" value={snapshot.notional ?? snapshot.orderNotional ?? '—'} />
      </div>

      <div className="mt-3 grid gap-3 lg:grid-cols-2">
        <div className="rounded-md border border-border bg-surface-2/40 p-3">
          <p className="text-[11px] font-semibold uppercase tracking-wide text-muted">Order snapshot</p>
          <dl className="mt-2 grid grid-cols-2 gap-x-3 gap-y-1 text-xs">
            <dt className="text-muted">Order type</dt><dd className="text-right text-text">{snapshot.orderType || '—'}</dd>
            <dt className="text-muted">Limit price</dt><dd className="text-right text-text">{snapshot.limitPrice ?? '—'}</dd>
            <dt className="text-muted">Strategy</dt><dd className="truncate text-right text-text">{snapshot.strategyId || '—'}</dd>
            <dt className="text-muted">Exchange</dt><dd className="text-right text-text">{snapshot.exchange || '—'}</dd>
          </dl>
        </div>
        <div className="rounded-md border border-border bg-surface-2/40 p-3">
          <p className="text-[11px] font-semibold uppercase tracking-wide text-muted">Risk evidence</p>
          <dl className="mt-2 grid grid-cols-2 gap-x-3 gap-y-1 text-xs">
            <dt className="text-muted">Risk decision</dt><dd className="truncate text-right text-text">{approval.riskDecisionId ? String(approval.riskDecisionId).slice(0, 12) : '—'}</dd>
            <dt className="text-muted">Policy</dt><dd className="truncate text-right text-text">{String(approval.policyId).slice(0, 12)}</dd>
            <dt className="text-muted">Row version</dt><dd className="text-right text-text">{approval.rowVersion}</dd>
            <dt className="text-muted">Reason</dt><dd className="truncate text-right text-text">{approval.decisionReason || 'Pending operator decision'}</dd>
          </dl>
        </div>
      </div>

      {pending && (
        <div className="mt-4 flex flex-wrap justify-end gap-2 border-t border-border pt-3">
          <Button variant="danger" icon={XCircle} onClick={() => onDecision(approval, 'reject')}>Reject</Button>
          <Button variant="primary" icon={CheckCircle2} onClick={() => onDecision(approval, 'approve')}>Approve</Button>
        </div>
      )}
    </article>
  );
}

export default function ApprovalsPage() {
  const { apiKey, experience } = useApp();
  const qc = useQueryClient();
  const [state, setState] = useState('PENDING');
  const [page, setPage] = useState(0);
  const [decision, setDecision] = useState(null);
  const [reason, setReason] = useState('');
  const clear = experience === 'clear-invest';

  const approvals = useQuery({
    queryKey: ['approvals', 'queue', state, page, apiKey ? 'authed' : 'anon'],
    queryFn: ({ signal }) => apiFetch(`/approvals?state=${state}&page=${page}&size=${PAGE_SIZE}`, { apiKey, signal }),
    enabled: !!apiKey,
    refetchInterval: state === 'PENDING' ? 15_000 : false,
    placeholderData: (previous) => previous,
  });
  const pendingCount = useQuery({
    queryKey: ['approvals', 'pending-count', apiKey ? 'authed' : 'anon'],
    queryFn: ({ signal }) => apiFetch('/approvals?state=PENDING&page=0&size=1', { apiKey, signal }),
    enabled: !!apiKey,
    refetchInterval: 15_000,
  });
  const mutate = useMutation({
    mutationFn: ({ approval, action, reason: decisionReason }) => apiFetch(`/approvals/${approval.id}/${action}`, {
      apiKey,
      method: 'POST',
      body: {
        expectedRowVersion: approval.rowVersion,
        actor: 'operator-ui',
        reason: decisionReason?.trim() || undefined,
      },
    }),
    onSuccess: () => {
      setDecision(null);
      setReason('');
      return Promise.all([
        qc.invalidateQueries({ queryKey: ['approvals'] }),
        qc.invalidateQueries({ queryKey: ['paper'] }),
      ]);
    },
  });

  const openDecision = (approval, action) => { setDecision({ approval, action }); setReason(''); };
  const totalPending = pendingCount.data?.totalItems ?? pendingCount.data?.total ?? 0;

  return (
    <div className="space-y-4">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <div className="flex items-center gap-2"><ShieldCheck className="h-5 w-5 text-accent" aria-hidden /><h1 className="text-xl font-semibold text-text">Execution approvals</h1></div>
          <p className="mt-1 text-sm text-muted">{clear ? 'Review orders that need a person before execution.' : 'Operator queue for policy-gated PAPER execution decisions.'}</p>
        </div>
        <KpiCard label="Pending approvals" value={totalPending} hint="Refreshes every 15 seconds" />
      </div>

      {!apiKey ? (
        <Notice title="Operator key required">Connect an operator key before reviewing approvals.</Notice>
      ) : (
        <>
          <Panel title="Queue" subtitle="A decision is never permanent execution authorization; the backend revalidates immediately before execution.">
            <div className="mb-4 overflow-x-auto">
              <SegmentedControl
                label="Approval state filter"
                value={state}
                onChange={(value) => { setState(value); setPage(0); }}
                options={FILTERS.map((value) => ({ value, label: value.replace('_', ' ') }))}
              />
            </div>
            {approvals.isPending && !approvals.data ? (
              <div className="h-52 animate-pulse rounded bg-surface-2" />
            ) : approvals.isError ? (
              <Notice tone="loss" title="Could not load approvals">{approvals.error?.message}</Notice>
            ) : !approvals.data?.items?.length ? (
              <EmptyState title={`No ${state.toLowerCase().replace('_', ' ')} approvals`} description="The queue is currently clear for this filter." />
            ) : (
              <div className="space-y-3">
                {approvals.data.items.map((approval) => <ApprovalCard key={approval.id} approval={approval} onDecision={openDecision} clear={clear} />)}
                {(approvals.data.totalPages ?? 1) > 1 && (
                  <div className="flex items-center justify-between text-xs text-muted">
                    <span>Page {(approvals.data.page ?? page) + 1} of {approvals.data.totalPages}</span>
                    <div className="flex gap-2">
                      <Button size="sm" variant="ghost" disabled={page === 0} onClick={() => setPage((p) => Math.max(0, p - 1))}>Previous</Button>
                      <Button size="sm" variant="ghost" disabled={page + 1 >= approvals.data.totalPages} onClick={() => setPage((p) => p + 1)}>Next</Button>
                    </div>
                  </div>
                )}
              </div>
            )}
          </Panel>

          {clear && (
            <Notice tone="neutral" title="How approval works">
              Manual approval allows an eligible order to proceed to the next safety check. It does not bypass risk limits, emergency stop, market-session rules or stale-market-data checks.
            </Notice>
          )}
        </>
      )}

      <Modal
        open={!!decision}
        onOpenChange={(open) => !open && setDecision(null)}
        title={decision?.action === 'approve' ? 'Approve this order?' : 'Reject this order?'}
        description="This action is concurrency-safe. The server returns the authoritative current state if another operator already decided it."
        footer={
          <>
            <Button onClick={() => setDecision(null)}>Cancel</Button>
            <Button
              variant={decision?.action === 'approve' ? 'primary' : 'danger'}
              loading={mutate.isPending}
              onClick={() => mutate.mutate({ ...decision, reason })}
            >
              Confirm {decision?.action}
            </Button>
          </>
        }
      >
        <div className="space-y-3 text-sm">
          <p className="text-muted">Order <span className="font-medium text-text">{decision?.approval?.orderId}</span></p>
          <label className="flex flex-col gap-1 text-xs text-muted">
            Decision reason (optional, max 300 characters)
            <textarea
              value={reason}
              maxLength={300}
              rows={3}
              onChange={(e) => setReason(e.target.value)}
              className="rounded-md border border-border bg-surface-2 px-2.5 py-2 text-sm text-text focus:border-accent focus:outline-none"
            />
          </label>
          {mutate.isError && <Notice tone="loss" title="Decision failed">{mutate.error?.message}</Notice>}
          <Notice tone="neutral" title="Final confirmation required">
            There is deliberately no one-key approve shortcut. Navigation hotkeys may be added later, but irreversible decisions always require explicit confirmation.
          </Notice>
        </div>
      </Modal>
    </div>
  );
}

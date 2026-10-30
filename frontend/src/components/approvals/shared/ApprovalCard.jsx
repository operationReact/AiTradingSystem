import { useEffect, useMemo, useState } from 'react';
import { CheckCircle2, Clock3, XCircle } from 'lucide-react';
import { Badge, Button, KpiCard } from '../../ui.jsx';

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

export default function ApprovalCard({ approval, onDecision, clear }) {
  const countdown = useCountdown(approval.expiresAt);
  const snapshot = useMemo(() => snapshotObject(approval.requestSnapshot), [approval.requestSnapshot]);
  const pending = approval.state === 'PENDING';
  const expired = countdown === 'Expired';
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
          <Button variant="danger" icon={XCircle} disabled={expired} onClick={() => onDecision(approval, 'reject')}>Reject</Button>
          <Button variant="primary" icon={CheckCircle2} disabled={expired} onClick={() => onDecision(approval, 'approve')}>Approve</Button>
        </div>
      )}
    </article>
  );
}


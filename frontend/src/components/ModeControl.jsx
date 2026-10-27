import { useState } from 'react';
import { ShieldCheck, TriangleAlert } from 'lucide-react';
import { useExecutionMode, useChangeExecutionMode } from '../hooks/useApi.js';
import { useApp } from '../context/AppContext.jsx';
import { cn } from '../lib/cn.js';
import { Button, Modal } from './ui.jsx';

const LIVE_CONFIRMATION = 'SWITCH TO LIVE';
const BLOCKER_LABELS = {
  LIVE_ORDER_PATH_NOT_IMPLEMENTED: 'Live broker order execution is not implemented',
  BROKER_LIVE_CREDENTIALS_NOT_VERIFIED: 'Broker live credentials are not verified',
  LIVE_SAFETY_CONDITIONS_NOT_VERIFIED: 'Live safety conditions are not verified',
};

function LiveConfirmDialog({ open, onOpenChange, blockers }) {
  const [typed, setTyped] = useState('');
  const [error, setError] = useState('');
  const change = useChangeExecutionMode();
  const close = (next) => {
    if (!next) {
      setTyped('');
      setError('');
      change.reset();
    }
    onOpenChange(next);
  };
  const submit = (e) => {
    e.preventDefault();
    if (typed.trim() !== LIVE_CONFIRMATION || change.isPending) return;
    setError('');
    change.mutate({ mode: 'LIVE', confirmation: LIVE_CONFIRMATION }, {
      onSuccess: (data) => {
        if (data?.activeMode === 'LIVE') close(false);
        else setError('LIVE was not activated by the backend.');
      },
      onError: (err) => setError(err.message || 'The backend refused the mode change.'),
    });
  };
  return (
    <Modal
      open={open}
      onOpenChange={close}
      title="Switch to LIVE trading"
      description="LIVE remains locked until real broker order execution and required safety checks are implemented and verified."
      footer={<>
        <Button variant="ghost" onClick={() => close(false)}>Cancel</Button>
        <Button variant="danger" type="submit" form="live-confirm-form"
          disabled={typed.trim() !== LIVE_CONFIRMATION} loading={change.isPending}>
          Confirm LIVE request
        </Button>
      </>}
    >
      <form id="live-confirm-form" onSubmit={submit} className="flex flex-col gap-3">
        {blockers?.length > 0 && <ul className="list-disc space-y-1 pl-4 text-xs text-muted">
          {blockers.map((b) => <li key={b}>{BLOCKER_LABELS[b] || b}</li>)}
        </ul>}
        <label htmlFor="live-confirm-text" className="text-xs text-muted">
          Type <span className="font-mono text-text">{LIVE_CONFIRMATION}</span> to continue
        </label>
        <input id="live-confirm-text" autoComplete="off" value={typed}
          onChange={(e) => setTyped(e.target.value)}
          className="h-9 rounded-md border border-border bg-surface-2 px-3 text-sm text-text focus:border-loss focus:outline-none" />
        {error && <p role="alert" className="text-xs text-loss">{error}</p>}
      </form>
    </Modal>
  );
}

export default function ModeControl() {
  const { apiKey } = useApp();
  const mode = useExecutionMode();
  const change = useChangeExecutionMode();
  const [confirmOpen, setConfirmOpen] = useState(false);
  const data = mode.data;
  const status = !apiKey || mode.isError || !data ? 'UNKNOWN' : data.activeMode;
  const unknown = status === 'UNKNOWN';
  const busy = change.isPending || mode.isFetching;
  const blockers = data?.liveBlockers ?? [];
  const liveAvailable = !unknown && data?.liveAvailable === true;
  const tooltip = !apiKey
    ? 'Connect the operator key to read the execution mode'
    : unknown ? 'Execution mode could not be retrieved from the backend' : undefined;
  const liveTitle = liveAvailable
    ? 'Request LIVE trading (requires confirmation)'
    : blockers.length ? `LIVE locked: ${blockers.map((b) => BLOCKER_LABELS[b] || b).join('; ')}`
    : 'LIVE locked';

  const requestPaper = () => {
    change.mutate({ mode: 'PAPER' });
  };

  return (
    <div className="flex items-center gap-2" data-testid="execution-mode">
      <div role="radiogroup" aria-label="Execution mode" title={tooltip}
        className="inline-flex items-center rounded-md border border-border bg-surface-2 p-0.5">
        <button type="button" role="radio" aria-checked={status === 'PAPER'}
          disabled={unknown || busy || status === 'PAPER'} title={unknown ? tooltip : 'Paper only — simulated trades, no broker orders'}
          onClick={requestPaper}
          className={cn('inline-flex h-7 items-center gap-1 rounded px-2.5 text-[11px] font-semibold tracking-wide transition-colors disabled:cursor-not-allowed',
            status === 'PAPER' ? 'bg-accent text-white' : 'text-muted hover:text-text disabled:opacity-50')}>
          <ShieldCheck className="h-3 w-3" aria-hidden />
          PAPER ONLY
        </button>
        <button type="button" role="radio" aria-checked={status === 'LIVE'}
          disabled={unknown || !liveAvailable || busy} title={liveTitle}
          onClick={() => setConfirmOpen(true)}
          className={cn('inline-flex h-7 items-center gap-1 rounded px-2.5 text-[11px] font-semibold tracking-wide transition-colors disabled:cursor-not-allowed',
            status === 'LIVE' ? 'bg-loss text-white' : 'text-muted disabled:opacity-60')}>
          {!liveAvailable && !unknown && <TriangleAlert className="h-3 w-3" aria-hidden />}
          LIVE
        </button>
      </div>
      {unknown
        ? <span className="text-[11px] font-semibold uppercase tracking-wide text-warn" title={tooltip}>UNKNOWN</span>
        : <span className={cn('hidden text-[11px] xl:inline', status === 'PAPER' ? 'text-muted' : 'text-loss')}>
            {status === 'PAPER' ? 'Paper only active' : 'Live mode'}
          </span>}
      {mode.isError && apiKey && <Button size="sm" variant="ghost" onClick={() => mode.refetch()}>Retry</Button>}
      {confirmOpen && <LiveConfirmDialog open={confirmOpen} onOpenChange={setConfirmOpen} blockers={blockers} />}
    </div>
  );
}

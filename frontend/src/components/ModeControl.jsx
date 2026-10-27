import { useState } from 'react';
import { ShieldCheck, TriangleAlert } from 'lucide-react';
import { useExecutionMode, useChangeExecutionMode } from '../hooks/useApi.js';
import { useApp } from '../context/AppContext.jsx';
import { cn } from '../lib/cn.js';
import { Button, Modal } from './ui.jsx';

const LIVE_CONFIRMATION = 'SWITCH TO LIVE';

const BLOCKER_LABELS = {
  LIVE_ORDER_PATH_NOT_IMPLEMENTED: 'No live order path exists in this build',
  BROKER_LIVE_CREDENTIALS_NOT_VERIFIED: 'Broker live credentials are not verified',
  LIVE_SAFETY_CONDITIONS_NOT_VERIFIED: 'Live safety conditions are not verified',
};

function LiveConfirmDialog({ open, onOpenChange, blockers }) {
  const [typed, setTyped] = useState('');
  const change = useChangeExecutionMode();
  const [error, setError] = useState('');
  const matches = typed.trim() === LIVE_CONFIRMATION;

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
    if (!matches || change.isPending) return;
    setError('');
    change.mutate(
      { mode: 'LIVE', confirmation: LIVE_CONFIRMATION },
      {
        onSuccess: (data) => {
          if (data?.activeMode === 'LIVE') close(false);
          else setError('The backend did not activate LIVE mode.');
        },
        onError: (err) => setError(err.message || 'The backend refused the mode change.'),
      },
    );
  };

  return (
    <Modal
      open={open}
      onOpenChange={close}
      title="Switch to LIVE trading"
      description="The backend verifies every safety condition before LIVE can be activated. Nothing changes until it confirms."
      footer={
        <>
          <Button variant="ghost" onClick={() => close(false)}>Cancel</Button>
          <Button variant="danger" type="submit" form="live-confirm-form" disabled={!matches} loading={change.isPending}>
            Request LIVE
          </Button>
        </>
      }
    >
      <form id="live-confirm-form" onSubmit={submit} className="flex flex-col gap-3">
        {blockers?.length > 0 && (
          <ul className="list-disc space-y-1 pl-4 text-xs text-muted">
            {blockers.map((b) => (
              <li key={b}>{BLOCKER_LABELS[b] || b}</li>
            ))}
          </ul>
        )}
        <label htmlFor="live-confirm-text" className="text-xs text-muted">
          Type <span className="font-mono text-text">{LIVE_CONFIRMATION}</span> to continue
        </label>
        <input
          id="live-confirm-text"
          autoComplete="off"
          value={typed}
          onChange={(e) => setTyped(e.target.value)}
          className="h-9 rounded-md border border-border bg-surface-2 px-3 text-sm text-text focus:border-loss focus:outline-none"
        />
        {error && <p role="alert" className="text-xs text-loss">{error}</p>}
      </form>
    </Modal>
  );
}

/** Header control for the execution mode. The backend is the source of truth; LIVE stays unavailable until it reports otherwise. */
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
    : unknown
      ? 'Execution mode could not be retrieved from the backend'
      : null;

  const liveTitle = liveAvailable
    ? 'Request LIVE trading (requires confirmation)'
    : blockers.length
      ? `LIVE unavailable: ${blockers.map((b) => BLOCKER_LABELS[b] || b).join('; ')}`
      : 'LIVE unavailable';

  return (
    <div className="flex items-center gap-2" data-testid="execution-mode">
      <div
        role="radiogroup"
        aria-label="Execution mode"
        title={tooltip || undefined}
        className="inline-flex items-center rounded-md border border-border bg-surface-2 p-0.5"
      >
        <button
          type="button"
          role="radio"
          aria-checked={status === 'PAPER'}
          disabled={unknown || busy || status === 'PAPER'}
          title={unknown ? tooltip : 'Paper trading (simulated, no broker orders)'}
          onClick={() => change.mutate({ mode: 'PAPER' })}
          className={cn(
            'inline-flex h-6 items-center gap-1 rounded px-2 text-[11px] font-semibold tracking-wide transition-colors disabled:cursor-not-allowed',
            status === 'PAPER' ? 'bg-accent text-white' : 'text-muted hover:text-text disabled:opacity-50',
          )}
        >
          <ShieldCheck className="h-3 w-3" aria-hidden />
          PAPER TRADING
        </button>
        <button
          type="button"
          role="radio"
          aria-checked={status === 'LIVE'}
          disabled={!liveAvailable || busy}
          title={liveTitle}
          onClick={() => setConfirmOpen(true)}
          className={cn(
            'inline-flex h-6 items-center gap-1 rounded px-2 text-[11px] font-semibold tracking-wide transition-colors',
            status === 'LIVE' ? 'bg-loss text-white' : 'text-muted/70 disabled:cursor-not-allowed disabled:opacity-60',
          )}
        >
          {!liveAvailable && !unknown && <TriangleAlert className="h-3 w-3" aria-hidden />}
          LIVE TRADING
        </button>
      </div>
      {unknown ? (
        <span className="text-[11px] font-semibold uppercase tracking-wide text-warn" title={tooltip}>UNKNOWN</span>
      ) : (
        !liveAvailable && <span className="hidden text-[11px] text-muted xl:inline">LIVE unavailable</span>
      )}
      {mode.isError && apiKey && (
        <Button size="sm" variant="ghost" onClick={() => mode.refetch()}>Retry</Button>
      )}
      {confirmOpen && <LiveConfirmDialog open={confirmOpen} onOpenChange={setConfirmOpen} blockers={blockers} />}
    </div>
  );
}

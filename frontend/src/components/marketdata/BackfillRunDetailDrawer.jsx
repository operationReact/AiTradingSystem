import { X } from 'lucide-react';
import { Badge, Button } from '../ui.jsx';
import { formatDateTime, formatInteger } from '../../lib/format.js';

export default function BackfillRunDetailDrawer({ run, onClose }) {
  if (!run) return null;
  const tone = run.status === 'SUCCEEDED' ? 'gain' : run.status === 'FAILED' ? 'loss' : run.status === 'PARTIAL' ? 'warn' : 'neutral';
  return (
    <div className="fixed inset-0 z-50 flex justify-end bg-black/40" role="dialog" aria-modal="true" aria-label="Backfill run details">
      <button className="flex-1 cursor-default" aria-label="Close details" onClick={onClose} />
      <aside className="h-full w-full max-w-md overflow-y-auto border-l border-border bg-surface p-5 shadow-xl">
        <div className="flex items-start justify-between gap-3">
          <div><p className="text-xs uppercase tracking-wide text-muted">Backfill run</p><h3 className="mt-1 text-base font-semibold text-text">{run.tradingSymbol || `Run ${run.id}`}</h3></div>
          <Button size="sm" variant="secondary" icon={X} onClick={onClose}>Close</Button>
        </div>
        <div className="mt-4"><Badge tone={tone}>{run.status || 'UNKNOWN'}</Badge></div>
        <dl className="mt-5 grid grid-cols-2 gap-4 text-xs">
          <div><dt className="text-muted">Interval</dt><dd className="mt-1 font-medium text-text">{run.interval || '—'}</dd></div>
          <div><dt className="text-muted">Chunks</dt><dd className="mt-1 font-medium text-text">{formatInteger(run.chunksCompleted)}</dd></div>
          <div><dt className="text-muted">Rows fetched</dt><dd className="mt-1 font-medium text-text">{formatInteger(run.fetchedRows)}</dd></div>
          <div><dt className="text-muted">Rows persisted</dt><dd className="mt-1 font-medium text-text">{formatInteger(run.persistedRows)}</dd></div>
          <div className="col-span-2"><dt className="text-muted">Window</dt><dd className="mt-1 font-medium text-text">{run.fromDate || '—'} → {run.toDate || '—'}</dd></div>
          <div className="col-span-2"><dt className="text-muted">Updated</dt><dd className="mt-1 font-medium text-text">{run.updatedAt ? formatDateTime(run.updatedAt) : '—'}</dd></div>
        </dl>
        {run.errorMessage && <div className="mt-5 rounded-md border border-loss/30 bg-loss/5 p-3 text-xs text-loss"><strong>{run.errorCode || 'ERROR'}:</strong> {run.errorMessage}</div>}
      </aside>
    </div>
  );
}

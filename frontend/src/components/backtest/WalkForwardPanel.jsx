import { CheckCircle2, Play } from 'lucide-react';
import { Button, EmptyState, Notice } from '../ui.jsx';
import { formatPct } from '../../lib/format.js';

export default function WalkForwardPanel({ detail, onRun, running, disabled }) {
  const run = detail?.run;
  const folds = detail?.folds || [];
  const positive = folds.filter((fold) => Number(fold.validationReturnPercent ?? fold.returnPercent ?? 0) > 0).length;
  const average = folds.length ? folds.reduce((sum, fold) => sum + Number(fold.validationReturnPercent ?? fold.returnPercent ?? 0), 0) / folds.length : null;
  return (
    <div className="space-y-4">
      {run ? (
        <>
          <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
            <div className="rounded-xl border border-border bg-surface-2/40 p-3"><p className="text-[10px] text-muted">Windows</p><p className="num mt-1 text-lg font-bold text-text">{folds.length}</p></div>
            <div className="rounded-xl border border-border bg-surface-2/40 p-3"><p className="text-[10px] text-muted">Positive</p><p className="num mt-1 text-lg font-bold text-gain">{positive}/{folds.length}</p></div>
            <div className="rounded-xl border border-border bg-surface-2/40 p-3"><p className="text-[10px] text-muted">Avg validation</p><p className="num mt-1 text-lg font-bold text-text">{average === null ? '—' : formatPct(average)}</p></div>
            <div className="rounded-xl border border-border bg-surface-2/40 p-3"><p className="text-[10px] text-muted">Leakage guard</p><p className="mt-1 flex items-center gap-1 text-sm font-bold text-gain"><CheckCircle2 className="h-4 w-4" /> Active</p></div>
          </div>
          <div className="space-y-2 text-xs text-muted">
            <p>• {positive}/{folds.length} validation windows positive</p>
            <p>• Point-in-time history is bounded before every decision</p>
            <p>• Next-bar execution uses the next observed candle, never fabricated gaps</p>
          </div>
        </>
      ) : <EmptyState title="Walk-forward evaluation not run" description="Run rolling training and validation windows after choosing a valid backtest setup." />}
      <div className="flex justify-end"><Button variant="primary" icon={Play} loading={running} disabled={disabled} onClick={onRun}>Run walk-forward</Button></div>
      {run?.status && run.status !== 'COMPLETED' && <Notice tone="warn" title="Walk-forward state">{run.status}</Notice>}
    </div>
  );
}

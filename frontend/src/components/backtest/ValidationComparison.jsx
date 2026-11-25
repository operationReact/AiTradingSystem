import { Badge, EmptyState } from '../ui.jsx';
import { formatBusinessDate, formatInteger, formatPct, pnlTone } from '../../lib/format.js';

function Metric({ label, value, hint, tone = 'text-text' }) {
  return <div className="rounded-xl border border-border bg-surface-2/45 p-3"><p className="text-[10px] font-semibold uppercase tracking-wide text-muted">{label}</p><p className={`num mt-1 text-lg font-bold ${tone}`}>{value}</p>{hint && <p className="mt-1 text-[11px] text-muted">{hint}</p>}</div>;
}

export default function ValidationComparison({ detail }) {
  const validation = detail?.validation;
  if (!validation) return <EmptyState title="No holdout comparison selected" description="Run or choose a saved holdout validation to compare training and untouched evaluation performance." />;
  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-2"><div><p className="text-sm font-semibold text-text">{validation.tradingSymbol} · {validation.interval}</p><p className="text-xs text-muted">Split {formatBusinessDate(validation.splitDate)} · {validation.outOfSamplePercent}% holdout</p></div><Badge tone="neutral">Saved report</Badge></div>
      <div className="grid grid-cols-2 gap-2 lg:grid-cols-4">
        <Metric label="Training return" value={formatPct(validation.trainingReturnPercent)} tone={pnlTone(validation.trainingReturnPercent)} hint={`${formatInteger(validation.trainingCandles)} candles`} />
        <Metric label="Holdout return" value={formatPct(validation.outOfSampleReturnPercent)} tone={pnlTone(validation.outOfSampleReturnPercent)} hint={`${formatInteger(validation.outOfSampleCandles)} candles`} />
        <Metric label="Holdout delta" value={formatPct(validation.returnDeltaPercentagePoints)} tone={pnlTone(validation.returnDeltaPercentagePoints)} hint="Percentage points" />
        <Metric label="Coverage" value={`${formatBusinessDate(validation.fromDate)} – ${formatBusinessDate(validation.toDate)}`} hint="Historical only" />
      </div>
    </div>
  );
}

import { formatInr, formatInteger, formatPct } from '../../lib/format.js';

export default function BacktestMetricsGrid({ run, totalRuns = 0, compact = false }) {
  const metrics = [
    ['Runs', formatInteger(totalRuns)],
    ['Win rate', formatPct(run?.winRatePercent)],
    ['Net return', formatPct(run?.totalReturnPercent)],
    ['Max DD', formatPct(run?.maxDrawdownPercent)],
  ];
  return (
    <div className="grid grid-cols-4 gap-2 sm:gap-3" aria-label="Backtest summary metrics">
      {metrics.map(([label, value]) => (
        <div key={label} className="backtest-metric min-w-0 rounded-2xl border border-border bg-surface px-2.5 py-3 sm:px-4">
          <p className="truncate text-[9px] font-semibold text-muted sm:text-[11px]">{label}</p>
          <p className={`num mt-1 truncate font-bold text-text ${compact ? 'text-base sm:text-xl' : 'text-lg sm:text-2xl'}`}>{value || '—'}</p>
          <div className="backtest-metric-rail mt-2 h-[3px] rounded-full bg-accent/60" aria-hidden />
        </div>
      ))}
      {run && !compact && (
        <span className="sr-only">Final equity {formatInr(run.finalEquity)}</span>
      )}
    </div>
  );
}

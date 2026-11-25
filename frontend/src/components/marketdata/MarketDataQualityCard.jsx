import { Badge, Notice } from '../ui.jsx';
import GapRangeTable from './GapRangeTable.jsx';

function tone(status) {
  if (status === 'HEALTHY') return 'gain';
  if (status === 'DEGRADED') return 'warn';
  if (status === 'INCOMPLETE' || status === 'FAILED') return 'loss';
  return 'neutral';
}

export default function MarketDataQualityCard({ latest, evaluation, isLoading, error }) {
  const data = evaluation || latest;
  const status = data?.status || 'NOT_EVALUATED';
  const eligible = evaluation ? status === 'HEALTHY' : !!data?.researchEligible;

  return (
    <section className="rounded-lg border border-border bg-surface p-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <p className="text-xs font-medium uppercase tracking-wide text-muted">Data quality</p>
          <h3 className="mt-1 text-base font-semibold text-text">Stored-candle readiness</h3>
        </div>
        <Badge tone={tone(status)}>{isLoading ? 'CHECKING' : status}</Badge>
      </div>
      {error && <div className="mt-3"><Notice tone="loss" title="Quality status unavailable">{error.message || 'Could not read market-data quality.'}</Notice></div>}
      {data && !isLoading && (
        <>
          <div className="mt-4 grid grid-cols-2 gap-3 sm:grid-cols-4">
            <div><p className="text-[11px] uppercase tracking-wide text-muted">Research gate</p><p className="mt-1 text-sm font-semibold text-text">{eligible ? 'Eligible' : 'Blocked'}</p></div>
            <div><p className="text-[11px] uppercase tracking-wide text-muted">Expected</p><p className="mt-1 text-sm font-semibold text-text">{data.expectedCandles ?? '—'}</p></div>
            <div><p className="text-[11px] uppercase tracking-wide text-muted">Missing</p><p className="mt-1 text-sm font-semibold text-text">{data.missingCandles ?? '—'}</p></div>
            <div><p className="text-[11px] uppercase tracking-wide text-muted">Duplicates</p><p className="mt-1 text-sm font-semibold text-text">{data.duplicateCandles ?? '—'}</p></div>
          </div>
          {evaluation?.gapRanges && <div className="mt-4"><GapRangeTable gaps={evaluation.gapRanges} compact /></div>}
        </>
      )}
      {!data && !isLoading && !error && <p className="mt-3 text-xs leading-5 text-muted">Choose an instrument and interval, then evaluate a bounded window to calculate expected, missing and duplicate candles.</p>}
    </section>
  );
}

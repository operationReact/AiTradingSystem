import { Activity, RefreshCw, ShieldCheck } from 'lucide-react';
import { Badge, Button, Notice, Panel } from '../ui.jsx';
import BackfillRunDetailDrawer from './BackfillRunDetailDrawer.jsx';
import GapRangeTable from './GapRangeTable.jsx';
import MarketCalendarPanel from './MarketCalendarPanel.jsx';
import MarketDataQualityCard from './MarketDataQualityCard.jsx';
import ProviderHealthBadge from './ProviderHealthBadge.jsx';

const INTERVALS = ['ONE_MINUTE', 'THREE_MINUTE', 'FIVE_MINUTE', 'TEN_MINUTE', 'FIFTEEN_MINUTE', 'THIRTY_MINUTE', 'ONE_HOUR', 'ONE_DAY'];
const inputClass = 'h-9 w-full rounded-md border border-border bg-surface-2 px-3 text-sm text-text focus:border-accent focus:outline-none';

export default function MarketDataOperationsPanel({ controller }) {
  const { connected, today, instrumentId, setInstrumentId, interval, setInterval, from, setFrom, to, setTo, evaluation,
    setEvaluation, reconciliation, setReconciliation, selectedRun, setSelectedRun, validInstrument, validRange,
    latestQuality, evaluate, reconcile, calendar, provider, backfills, runEvaluate, runReconcile } = controller;
  return (
    <div className="flex flex-col gap-4">
      {!connected && <Notice tone="neutral" title="Operator key required">Connect the operator key to evaluate market-data quality or reconcile gaps.</Notice>}
      <Panel title="Market-data operations" subtitle="Quality gate · reconciliation · provider health · exchange calendar">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <p className="max-w-3xl text-xs leading-5 text-muted">Operational checks are read-only except explicit reconciliation/backfill requests. They never submit broker orders.</p>
          <ProviderHealthBadge status={provider.data} isLoading={connected && provider.isPending} error={provider.error} />
        </div>
        <div className="mt-4 grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <div><label htmlFor="operations-instrument" className="mb-1 block text-xs font-medium text-muted">Instrument ID</label><input id="operations-instrument" className={inputClass} inputMode="numeric" min="1" type="number" value={instrumentId} onChange={(e) => { setInstrumentId(e.target.value); setEvaluation(null); setReconciliation(null); }} placeholder="e.g. 123" /></div>
          <div><label htmlFor="operations-interval" className="mb-1 block text-xs font-medium text-muted">Interval</label><select id="operations-interval" className={inputClass} value={interval} onChange={(e) => { setInterval(e.target.value); setEvaluation(null); setReconciliation(null); }}>{INTERVALS.map((item) => <option key={item}>{item}</option>)}</select></div>
          <div><label htmlFor="operations-from" className="mb-1 block text-xs font-medium text-muted">From</label><input id="operations-from" className={inputClass} type="date" value={from} max={to} onChange={(e) => setFrom(e.target.value)} /></div>
          <div><label htmlFor="operations-to" className="mb-1 block text-xs font-medium text-muted">To</label><input id="operations-to" className={inputClass} type="date" value={to} min={from} max={today} onChange={(e) => setTo(e.target.value)} /></div>
        </div>
        <div className="mt-4 flex flex-wrap gap-2">
          <Button icon={ShieldCheck} variant="primary" disabled={!connected || !validInstrument || !validRange || evaluate.isPending} loading={evaluate.isPending} onClick={runEvaluate}>Evaluate quality</Button>
          <Button icon={RefreshCw} variant="secondary" disabled={!connected || !validInstrument || !validRange || reconcile.isPending} loading={reconcile.isPending} onClick={runReconcile}>Reconcile gaps</Button>
        </div>
        {(evaluate.error || reconcile.error) && <div className="mt-3"><Notice tone="loss" title="Operation failed">{(evaluate.error || reconcile.error)?.message || 'The market-data operation failed.'}</Notice></div>}
      </Panel>

      <MarketDataQualityCard latest={latestQuality.data} evaluation={evaluation} isLoading={validInstrument && latestQuality.isPending} error={latestQuality.error} />

      {reconciliation && (
        <Panel title="Reconciliation result" subtitle="Gap detection and bounded auto-backfill decision">
          <div className="mb-4 grid grid-cols-2 gap-3 sm:grid-cols-4">
            <div><p className="text-[11px] uppercase tracking-wide text-muted">Quality</p><p className="mt-1"><Badge tone={reconciliation.qualityStatus === 'HEALTHY' ? 'gain' : 'warn'}>{reconciliation.qualityStatus}</Badge></p></div>
            <div><p className="text-[11px] uppercase tracking-wide text-muted">Missing</p><p className="mt-1 text-sm font-semibold text-text">{reconciliation.missingCandles}</p></div>
            <div><p className="text-[11px] uppercase tracking-wide text-muted">Auto backfill</p><p className="mt-1 text-sm font-semibold text-text">{reconciliation.autoBackfillEnabled ? 'Enabled' : 'Disabled'}</p></div>
            <div><p className="text-[11px] uppercase tracking-wide text-muted">Backfills started</p><p className="mt-1 text-sm font-semibold text-text">{reconciliation.backfillsStarted}</p></div>
          </div>
          <GapRangeTable gaps={reconciliation.gaps || []} />
        </Panel>
      )}

      <Panel title="Market calendar" subtitle={`${from} to ${to} · Asia/Kolkata exchange session expectations`}>
        <MarketCalendarPanel query={calendar} />
      </Panel>

      <Panel title="Backfill run details" subtitle="Open a recent run without leaving the market-data workspace">
        {backfills.data?.length ? (
          <div className="flex flex-col gap-2">
            {backfills.data.slice(0, 5).map((run) => (
              <button key={run.id} type="button" onClick={() => setSelectedRun(run)} className="flex items-center justify-between gap-3 rounded-md border border-border px-3 py-2 text-left hover:bg-surface-2">
                <span><span className="block text-sm font-medium text-text">{run.tradingSymbol || `Run ${run.id}`}</span><span className="block text-xs text-muted">{run.interval} · {run.fromDate} → {run.toDate}</span></span>
                <span className="flex items-center gap-2"><Badge tone={run.status === 'SUCCEEDED' ? 'gain' : run.status === 'FAILED' ? 'loss' : 'warn'}>{run.status}</Badge><Activity className="h-4 w-4 text-muted" /></span>
              </button>
            ))}
          </div>
        ) : <p className="text-xs text-muted">No recent backfill runs are available.</p>}
      </Panel>
      <BackfillRunDetailDrawer run={selectedRun} onClose={() => setSelectedRun(null)} />
    </div>
  );
}

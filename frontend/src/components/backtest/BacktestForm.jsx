import { Play, Search, X } from 'lucide-react';
import { Button, ErrorState } from '../ui.jsx';

const intervals = [
  ['ONE_MINUTE', '1 minute'], ['THREE_MINUTE', '3 minutes'], ['FIVE_MINUTE', '5 minutes'], ['TEN_MINUTE', '10 minutes'],
  ['FIFTEEN_MINUTE', '15 minutes'], ['THIRTY_MINUTE', '30 minutes'], ['ONE_HOUR', '1 hour'], ['ONE_DAY', '1 day'],
];
const inputClass = 'h-10 w-full rounded-lg border border-border bg-surface-2 px-3 text-sm text-text placeholder:text-muted focus:border-accent focus:outline-none';

export default function BacktestForm({
  apiKey, form, onChange, instrumentText, onInstrumentTextChange, selectedInstrument, onSelectInstrument, onClearInstrument,
  matches, canRun, running, onSubmit,
}) {
  return (
    <form onSubmit={onSubmit} className="space-y-4">
      <div>
        <label htmlFor="studio-instrument" className="mb-1 block text-xs font-semibold text-muted">NSE cash-equity instrument</label>
        {selectedInstrument ? (
          <div className="flex min-h-11 items-center justify-between gap-3 rounded-lg border border-border bg-surface-2/60 px-3 py-2">
            <div className="min-w-0"><p className="truncate text-sm font-semibold text-text">{selectedInstrument.tradingSymbol}</p><p className="truncate text-[11px] text-muted">{selectedInstrument.exchange} · Token {selectedInstrument.symbolToken}</p></div>
            <Button size="sm" variant="ghost" icon={X} onClick={onClearInstrument}>Change</Button>
          </div>
        ) : (
          <div className="relative">
            <Search className="pointer-events-none absolute left-3 top-3 h-4 w-4 text-muted" aria-hidden />
            <input id="studio-instrument" className={`${inputClass} pl-9`} value={instrumentText} onChange={(e) => onInstrumentTextChange(e.target.value)} placeholder="Search symbol, e.g. RELIANCE" autoComplete="off" disabled={!apiKey} />
            {apiKey && instrumentText.trim() && (
              <div className="absolute z-20 mt-1 max-h-56 w-full overflow-y-auto rounded-lg border border-border bg-surface shadow-xl">
                {matches.isFetching && <p className="px-3 py-2 text-xs text-muted">Searching catalogue…</p>}
                {matches.isError && <div className="p-2"><ErrorState error={matches.error} onRetry={() => matches.refetch()} /></div>}
                {matches.data?.content?.map((item) => <button key={item.id} type="button" onClick={() => onSelectInstrument(item)} className="block w-full border-b border-border/60 px-3 py-2 text-left last:border-0 hover:bg-surface-2"><span className="block text-sm font-semibold text-text">{item.tradingSymbol}</span><span className="text-[11px] text-muted">{item.name || item.instrumentType || 'Equity'}</span></button>)}
              </div>
            )}
          </div>
        )}
      </div>
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <label className="text-xs font-semibold text-muted">Interval<select className={`${inputClass} mt-1`} value={form.interval} onChange={(e) => onChange('interval', e.target.value)}>{intervals.map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label>
        <label className="text-xs font-semibold text-muted">From<input className={`${inputClass} mt-1`} type="date" value={form.fromDate} onChange={(e) => onChange('fromDate', e.target.value)} required /></label>
        <label className="text-xs font-semibold text-muted">To<input className={`${inputClass} mt-1`} type="date" value={form.toDate} onChange={(e) => onChange('toDate', e.target.value)} required /></label>
        <label className="text-xs font-semibold text-muted">Paper capital (₹)<input className={`${inputClass} mt-1`} type="number" min="10000" max="100000000" step="1000" value={form.initialCapital} onChange={(e) => onChange('initialCapital', e.target.value)} required /></label>
      </div>
      <div className="flex flex-wrap items-center justify-between gap-3 border-t border-border pt-3">
        <p className="max-w-2xl text-xs leading-5 text-muted">Point-in-time candles only. Charges and slippage are applied server-side. No broker orders are submitted.</p>
        <Button type="submit" variant="primary" icon={Play} loading={running} disabled={!canRun}>Run backtest</Button>
      </div>
    </form>
  );
}

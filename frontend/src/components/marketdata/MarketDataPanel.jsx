import { useEffect, useState } from 'react';
import { Database, RefreshCw, Search } from 'lucide-react';
import { useApp } from '../../context/AppContext.jsx';
import {
  useCandleBackfills,
  useCandles,
  useInstrumentSearch,
  useStartCandleBackfill,
  useResumeCandleBackfill,
} from '../../hooks/useApi.js';
import { formatDateTime, formatInteger } from '../../lib/format.js';
import { Badge, Button, EmptyState, ErrorState, Notice, Panel, Skeleton } from '../ui.jsx';

const INTERVALS = [
  { value: 'ONE_MINUTE', label: '1 minute' },
  { value: 'THREE_MINUTE', label: '3 minutes' },
  { value: 'FIVE_MINUTE', label: '5 minutes' },
  { value: 'TEN_MINUTE', label: '10 minutes' },
  { value: 'FIFTEEN_MINUTE', label: '15 minutes' },
  { value: 'THIRTY_MINUTE', label: '30 minutes' },
  { value: 'ONE_HOUR', label: '1 hour' },
  { value: 'ONE_DAY', label: '1 day' },
];
const inputClass = 'h-9 w-full rounded-md border border-border bg-surface-2 px-3 text-sm text-text placeholder:text-muted focus:border-accent focus:outline-none';
const selectClass = 'h-9 w-full rounded-md border border-border bg-surface-2 px-2 text-sm text-text focus:border-accent focus:outline-none';

function todayInIndia() {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Kolkata', year: 'numeric', month: '2-digit', day: '2-digit',
  }).formatToParts(new Date());
  const part = (type) => parts.find((item) => item.type === type)?.value;
  return part('year') + '-' + part('month') + '-' + part('day');
}
function shiftDate(value, days) {
  const date = new Date(value + 'T00:00:00Z');
  date.setUTCDate(date.getUTCDate() + days);
  return date.toISOString().slice(0, 10);
}
function price(value) {
  return value === null || value === undefined ? '—' : Number(value).toLocaleString('en-IN', {
    minimumFractionDigits: 2, maximumFractionDigits: 4,
  });
}
function StatusBadge({ status }) {
  const tone = status === 'SUCCEEDED' ? 'gain' : status === 'FAILED' ? 'loss' : status === 'PARTIAL' ? 'warn' : 'neutral';
  return <Badge tone={tone}>{status || 'UNKNOWN'}</Badge>;
}

export default function MarketDataPanel() {
  const { apiKey } = useApp();
  const [instrumentText, setInstrumentText] = useState('');
  const [searchTerm, setSearchTerm] = useState('');
  const [selected, setSelected] = useState(null);
  const [interval, setInterval] = useState('FIVE_MINUTE');
  const today = todayInIndia();
  const [toDate, setToDate] = useState(today);
  const [fromDate, setFromDate] = useState(shiftDate(today, -29));
  const [page, setPage] = useState(0);
  const [latestRun, setLatestRun] = useState(null);

  useEffect(() => {
    const timer = setTimeout(() => setSearchTerm(instrumentText.trim()), 300);
    return () => clearTimeout(timer);
  }, [instrumentText]);

  const matches = useInstrumentSearch({
    q: searchTerm, page: 0, size: 10, enabled: !!apiKey && !selected && searchTerm.length > 0,
  });
  const backfills = useCandleBackfills(10);
  const start = useStartCandleBackfill();
  const resume = useResumeCandleBackfill();
  const candles = useCandles({
    instrumentId: selected?.id, interval, fromDate, toDate, page, size: 100,
    enabled: !!selected && !!fromDate && !!toDate && fromDate <= toDate,
  });

  const submitBackfill = (event) => {
    event.preventDefault();
    if (!selected || !fromDate || !toDate || fromDate > toDate || start.isPending) return;
    start.mutate({ instrumentId: selected.id, interval, fromDate, toDate }, { onSuccess: setLatestRun });
  };
  const resumeRun = (runId) => resume.mutate(runId, { onSuccess: setLatestRun });
  const chooseInstrument = (instrument) => {
    setSelected(instrument);
    setInstrumentText(instrument.tradingSymbol);
    setSearchTerm('');
    setPage(0);
    setLatestRun(null);
  };
  const changingInstrument = () => {
    setSelected(null);
    setSearchTerm('');
    setInstrumentText('');
    setLatestRun(null);
  };
  const latestError = start.error || resume.error;
  const displayedRun = latestRun || start.data || resume.data;

  return (
    <div className="flex flex-col gap-4">
      <Panel title="Historical candles" subtitle="Angel One OHLCV · bounded import · stored in PostgreSQL">
        {!apiKey && <Notice tone="neutral" title="Operator key required">Connect the operator API key in the top bar to search instruments and request historical data.</Notice>}
        <form onSubmit={submitBackfill} className="flex flex-col gap-4">
          <div>
            <label htmlFor="market-instrument" className="mb-1 block text-xs font-medium text-muted">Instrument from active catalogue</label>
            {selected ? (
              <div className="flex items-center justify-between gap-3 rounded-md border border-border bg-surface-2/40 px-3 py-2">
                <div className="min-w-0">
                  <p className="truncate text-sm font-semibold text-text">{selected.tradingSymbol}</p>
                  <p className="truncate text-xs text-muted">{selected.exchange} · Token {selected.symbolToken} · {selected.name || selected.instrumentType || 'Instrument'}</p>
                </div>
                <Button type="button" size="sm" variant="secondary" onClick={changingInstrument}>Change</Button>
              </div>
            ) : (
              <>
                <div className="relative">
                  <Search className="absolute left-3 top-2.5 h-4 w-4 text-muted" aria-hidden />
                  <input id="market-instrument" className={inputClass + ' pl-9'} value={instrumentText}
                    onChange={(e) => setInstrumentText(e.target.value)} placeholder="Search symbol/name (e.g. RELIANCE)"
                    autoComplete="off" maxLength={100} disabled={!apiKey} />
                </div>
                {apiKey && searchTerm.length > 0 && (
                  <div className="mt-2 overflow-hidden rounded-md border border-border">
                    {matches.isFetching && <p className="px-3 py-2 text-xs text-muted">Searching active catalogue…</p>}
                    {matches.isError && <div className="p-2"><ErrorState error={matches.error} onRetry={() => matches.refetch()} /></div>}
                    {matches.data?.content?.length > 0 && matches.data.content.map((item) => (
                      <button type="button" key={item.id} onClick={() => chooseInstrument(item)}
                        className="flex w-full items-center justify-between gap-3 border-b border-border/70 px-3 py-2 text-left last:border-0 hover:bg-surface-2">
                        <span className="min-w-0">
                          <span className="block truncate text-sm font-medium text-text">{item.tradingSymbol}</span>
                          <span className="block truncate text-xs text-muted">{item.exchange} · {item.name || item.instrumentType || 'Instrument'}</span>
                        </span>
                        <span className="shrink-0 text-[11px] text-muted">{item.instrumentType || '—'}</span>
                      </button>
                    ))}
                    {!matches.isFetching && !matches.isError && matches.data?.content?.length === 0 && (
                      <p className="px-3 py-2 text-xs text-muted">No matches. Import the latest instrument master from the Instruments page first.</p>
                    )}
                  </div>
                )}
              </>
            )}
          </div>

          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
            <div>
              <label htmlFor="candle-interval" className="mb-1 block text-xs font-medium text-muted">Candle interval</label>
              <select id="candle-interval" className={selectClass} value={interval} onChange={(e) => { setInterval(e.target.value); setPage(0); }}>
                {INTERVALS.map((item) => <option key={item.value} value={item.value}>{item.label}</option>)}
              </select>
            </div>
            <div>
              <label htmlFor="candle-from" className="mb-1 block text-xs font-medium text-muted">From date</label>
              <input id="candle-from" className={inputClass} type="date" value={fromDate} max={toDate || today}
                onChange={(e) => { setFromDate(e.target.value); setPage(0); }} required />
            </div>
            <div>
              <label htmlFor="candle-to" className="mb-1 block text-xs font-medium text-muted">To date</label>
              <input id="candle-to" className={inputClass} type="date" value={toDate} min={fromDate}
                max={today} onChange={(e) => { setToDate(e.target.value); setPage(0); }} required />
            </div>
          </div>
          <div className="flex flex-wrap items-center justify-between gap-3">
            <p className="max-w-2xl text-xs leading-5 text-muted">
              Long requests stop after a small number of chunks and can be resumed from a saved checkpoint.
            </p>
            <Button type="submit" variant="primary" icon={Database} disabled={!apiKey || !selected || !fromDate || !toDate || fromDate > toDate || start.isPending}
              loading={start.isPending}>
              Fetch & store candles
            </Button>
          </div>
        </form>
        {latestError && <div className="mt-3"><Notice tone="loss" title="Backfill request failed">{latestError.message || 'Could not run the backfill.'}</Notice></div>}
        {displayedRun && (
          <div className="mt-4 rounded-md border border-border p-3">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <div>
                <p className="text-sm font-semibold text-text">Latest backfill</p>
                <p className="mt-0.5 text-xs text-muted">{displayedRun.tradingSymbol} · {displayedRun.interval} · {displayedRun.fromDate} to {displayedRun.toDate}</p>
              </div>
              <StatusBadge status={displayedRun.status} />
            </div>
            <div className="mt-3 grid grid-cols-2 gap-3 sm:grid-cols-4">
              <div><p className="text-[11px] uppercase tracking-wide text-muted">Chunks done</p><p className="mt-1 text-sm font-semibold text-text">{formatInteger(displayedRun.chunksCompleted)}</p></div>
              <div><p className="text-[11px] uppercase tracking-wide text-muted">Rows received</p><p className="mt-1 text-sm font-semibold text-text">{formatInteger(displayedRun.fetchedRows)}</p></div>
              <div><p className="text-[11px] uppercase tracking-wide text-muted">Rows persisted</p><p className="mt-1 text-sm font-semibold text-text">{formatInteger(displayedRun.persistedRows)}</p></div>
              <div><p className="text-[11px] uppercase tracking-wide text-muted">Next checkpoint</p><p className="mt-1 text-sm font-semibold text-text">{displayedRun.nextFromDate > displayedRun.toDate ? 'Complete' : displayedRun.nextFromDate}</p></div>
            </div>
            {displayedRun.errorMessage && <p className="mt-3 text-xs text-loss">{displayedRun.errorCode}: {displayedRun.errorMessage}</p>}
            {(displayedRun.status === 'PARTIAL' || displayedRun.status === 'FAILED') && (
              <div className="mt-3">
                <Button size="sm" variant="secondary" icon={RefreshCw} loading={resume.isPending}
                  disabled={!apiKey || resume.isPending} onClick={() => resumeRun(displayedRun.id)}>
                  Resume from checkpoint
                </Button>
              </div>
            )}
          </div>
        )}
      </Panel>

      <Panel title="Stored candles" subtitle={selected ? selected.tradingSymbol + ' · ' + interval + ' · time shown in India Standard Time' : 'Select an instrument to view persisted OHLCV rows'}>
        {selected && candles.isPending && <Skeleton className="h-40 w-full" />}
        {candles.isError && <ErrorState error={candles.error} onRetry={() => candles.refetch()} />}
        {selected && candles.data && candles.data.items.length === 0 && (
          <EmptyState title="No candles stored for this selection" description="Fetch and store the selected instrument and interval to populate this table." />
        )}
        {selected && candles.data && candles.data.items.length > 0 && (
          <>
            <div className="mb-3 flex items-center justify-between gap-3 text-xs text-muted">
              <span>{formatInteger(candles.data.totalItems)} stored rows</span>
              <span>{candles.isFetching ? 'Refreshing…' : 'Source: Angel One SmartAPI'}</span>
            </div>
            <div className="overflow-x-auto rounded-md border border-border">
              <table className="min-w-[820px] w-full text-left text-xs">
                <thead className="bg-surface-2/70 text-[11px] uppercase tracking-wide text-muted">
                  <tr>
                    <th className="px-3 py-2 font-medium">Candle time (IST)</th>
                    <th className="px-3 py-2 text-right font-medium">Open</th>
                    <th className="px-3 py-2 text-right font-medium">High</th>
                    <th className="px-3 py-2 text-right font-medium">Low</th>
                    <th className="px-3 py-2 text-right font-medium">Close</th>
                    <th className="px-3 py-2 text-right font-medium">Volume</th>
                  </tr>
                </thead>
                <tbody>
                  {candles.data.items.map((item) => (
                    <tr key={item.id} className="border-t border-border/70 hover:bg-surface-2/40">
                      <td className="px-3 py-2 text-muted">{formatDateTime(item.candleTime)}</td>
                      <td className="num px-3 py-2 text-right text-text">{price(item.open)}</td>
                      <td className="num px-3 py-2 text-right text-text">{price(item.high)}</td>
                      <td className="num px-3 py-2 text-right text-text">{price(item.low)}</td>
                      <td className="num px-3 py-2 text-right text-text">{price(item.close)}</td>
                      <td className="num px-3 py-2 text-right text-text">{formatInteger(item.volume)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div className="mt-3 flex items-center justify-between text-xs text-muted">
              <span>Page {candles.data.page + 1} of {Math.max(candles.data.totalPages, 1)}</span>
              <div className="flex gap-2">
                <Button size="sm" disabled={page === 0 || candles.isFetching} onClick={() => setPage((p) => Math.max(0, p - 1))}>Previous</Button>
                <Button size="sm" disabled={!candles.data.hasNext || candles.isFetching} onClick={() => setPage((p) => p + 1)}>Next</Button>
              </div>
            </div>
          </>
        )}
      </Panel>

      <Panel title="Recent backfills" subtitle="Attempt history and resumable checkpoints">
        {backfills.isPending && <Skeleton className="h-24 w-full" />}
        {backfills.isError && <ErrorState error={backfills.error} onRetry={() => backfills.refetch()} />}
        {backfills.data && backfills.data.length === 0 && <EmptyState title="No backfill attempts yet" description="Start a backfill above to record the first request." />}
        {backfills.data?.length > 0 && (
          <div className="overflow-x-auto">
            <table className="min-w-[760px] w-full text-left text-xs">
              <thead className="text-[11px] uppercase tracking-wide text-muted">
                <tr>
                  <th className="px-2 py-2 font-medium">Started</th>
                  <th className="px-2 py-2 font-medium">Instrument / interval</th>
                  <th className="px-2 py-2 font-medium">Date range</th>
                  <th className="px-2 py-2 font-medium">Status</th>
                  <th className="px-2 py-2 text-right font-medium">Chunks / rows</th>
                  <th className="px-2 py-2 font-medium">Action</th>
                </tr>
              </thead>
              <tbody>
                {backfills.data.map((run) => (
                  <tr key={run.id} className="border-t border-border/70">
                    <td className="px-2 py-2 text-muted">{formatDateTime(run.startedAt)}</td>
                    <td className="px-2 py-2"><p className="font-medium text-text">{run.tradingSymbol}</p><p className="text-muted">{run.interval}</p></td>
                    <td className="px-2 py-2 text-muted">{run.fromDate} – {run.toDate}</td>
                    <td className="px-2 py-2"><StatusBadge status={run.status} /></td>
                    <td className="num px-2 py-2 text-right text-text">{formatInteger(run.chunksCompleted)} / {formatInteger(run.persistedRows)}</td>
                    <td className="px-2 py-2">
                      {(run.status === 'PARTIAL' || run.status === 'FAILED')
                        ? <Button size="sm" disabled={!apiKey || resume.isPending} loading={resume.isPending && resume.variables === run.id} onClick={() => resumeRun(run.id)}>Resume</Button>
                        : <Button size="sm" variant="ghost" onClick={() => { setSelected({ id: run.instrumentId, exchange: run.exchange, tradingSymbol: run.tradingSymbol, symbolToken: run.symbolToken }); setInterval(run.interval); setFromDate(run.fromDate); setToDate(run.toDate); setPage(0); setLatestRun(null); }}>View</Button>}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Panel>
    </div>
  );
}

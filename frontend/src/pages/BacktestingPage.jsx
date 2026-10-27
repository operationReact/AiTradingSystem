import { useEffect, useMemo, useState } from 'react';
import { Activity, BarChart3, Clock3, Play, RefreshCw, ShieldCheck } from 'lucide-react';
import { useApp } from '../context/AppContext.jsx';
import {
  useBacktestValidationDetail,
  useBacktestValidations,
  useInstrumentSearch,
  useRunBacktestValidation,
} from '../hooks/useApi.js';
import { formatBusinessDate, formatDateTime, formatInr, formatInteger, formatPct, pnlTone } from '../lib/format.js';
import { Badge, Button, EmptyState, ErrorState, Notice, Panel, Skeleton } from '../components/ui.jsx';

const INTERVALS = [
  ['ONE_MINUTE', '1 minute'],
  ['THREE_MINUTE', '3 minutes'],
  ['FIVE_MINUTE', '5 minutes'],
  ['TEN_MINUTE', '10 minutes'],
  ['FIFTEEN_MINUTE', '15 minutes'],
  ['THIRTY_MINUTE', '30 minutes'],
  ['ONE_HOUR', '1 hour'],
  ['ONE_DAY', '1 day'],
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
function ReturnTone({ value }) {
  return <span className={pnlTone(value)}>{formatPct(value)}</span>;
}
function SummaryMetric({ label, value, hint, tone }) {
  return (
    <div className="min-w-0 rounded-md border border-border bg-surface-2/40 p-3">
      <p className="text-[11px] uppercase tracking-wide text-muted">{label}</p>
      <p className={`mt-1 truncate text-lg font-semibold num ${tone || 'text-text'}`}>{value}</p>
      {hint && <p className="mt-1 text-[11px] leading-4 text-muted">{hint}</p>}
    </div>
  );
}
function PeriodPanel({ title, period, badge }) {
  if (!period) return null;
  const run = period.run;
  const summary = run;
  return (
    <Panel title={title} subtitle={`${formatBusinessDate(summary.fromDate)} – ${formatBusinessDate(summary.toDate)}`}
      actions={<Badge tone="neutral">{badge}</Badge>}>
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-3">
        <SummaryMetric label="Return" value={<ReturnTone value={summary.totalReturnPercent} />} hint="Net of modeled fees/slippage" />
        <SummaryMetric label="Net P&L" value={formatInr(summary.netPnl)} tone={pnlTone(summary.netPnl)} />
        <SummaryMetric label="Max drawdown" value={formatPct(summary.maxDrawdownPercent)} tone="text-warn" />
        <SummaryMetric label="Trades" value={formatInteger(summary.tradeCount)} />
        <SummaryMetric label="Win rate" value={formatPct(summary.winRatePercent)} />
        <SummaryMetric label="Candles evaluated" value={formatInteger(summary.candlesEvaluated)} />
      </div>
      <div className="mt-3 flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-muted">
        <span>Initial capital: {formatInr(summary.initialCapital)}</span>
        <span>Strategy: {summary.strategyVersion}</span>
        <span>Fast/slow SMA: {summary.fastPeriod}/{summary.slowPeriod}</span>
      </div>
    </Panel>
  );
}
function TradeTable({ trades }) {
  if (!trades?.length) {
    return <EmptyState title="No trades in this period" description="The strategy did not find a qualifying setup in the selected candles." />;
  }
  return (
    <div className="overflow-x-auto">
      <table className="w-full min-w-[720px] text-left text-xs">
        <thead><tr className="border-b border-border text-[11px] uppercase tracking-wide text-muted">
          <th className="px-2 py-2 font-medium">Entry</th>
          <th className="px-2 py-2 font-medium">Exit</th>
          <th className="px-2 py-2 text-right font-medium">Qty</th>
          <th className="px-2 py-2 text-right font-medium">Entry price</th>
          <th className="px-2 py-2 text-right font-medium">Exit price</th>
          <th className="px-2 py-2 text-right font-medium">Net P&L</th>
          <th className="px-2 py-2 font-medium">Exit reason</th>
        </tr></thead>
        <tbody>
          {trades.slice(0, 30).map((trade) => (
            <tr key={trade.tradeNumber} className="border-b border-border/60 last:border-0">
              <td className="px-2 py-2 text-text">{formatDateTime(trade.entryAt)}</td>
              <td className="px-2 py-2 text-text">{formatDateTime(trade.exitAt)}</td>
              <td className="px-2 py-2 text-right num text-text">{formatInteger(trade.quantity)}</td>
              <td className="px-2 py-2 text-right num text-text">{formatInr(trade.entryPrice)}</td>
              <td className="px-2 py-2 text-right num text-text">{formatInr(trade.exitPrice)}</td>
              <td className={`px-2 py-2 text-right num font-medium ${pnlTone(trade.netPnl)}`}>{formatInr(trade.netPnl)}</td>
              <td className="px-2 py-2 text-muted">{trade.exitReason}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {trades.length > 30 && <p className="mt-2 text-xs text-muted">Showing first 30 of {trades.length} trades.</p>}
    </div>
  );
}

export default function BacktestingPage() {
  const { apiKey } = useApp();
  const today = todayInIndia();
  const defaultTo = shiftDate(today, -1);
  const [instrumentText, setInstrumentText] = useState('');
  const [searchTerm, setSearchTerm] = useState('');
  const [selectedInstrument, setSelectedInstrument] = useState(null);
  const [interval, setInterval] = useState('ONE_DAY');
  const [toDate, setToDate] = useState(defaultTo);
  const [fromDate, setFromDate] = useState(shiftDate(defaultTo, -179));
  const [outOfSamplePercent, setOutOfSamplePercent] = useState('30');
  const [initialCapital, setInitialCapital] = useState('80000');
  const [selectedValidationId, setSelectedValidationId] = useState(null);
  const history = useBacktestValidations(20);
  const matches = useInstrumentSearch({
    q: searchTerm, exchange: 'NSE', type: 'EQ', page: 0, size: 20,
    enabled: !!apiKey && !selectedInstrument && searchTerm.length > 0,
  });
  const runMutation = useRunBacktestValidation();
  const detail = useBacktestValidationDetail(selectedValidationId);

  useEffect(() => {
    const timer = setTimeout(() => setSearchTerm(instrumentText.trim()), 250);
    return () => clearTimeout(timer);
  }, [instrumentText]);

  const result = useMemo(() => {
    if (runMutation.data?.validation?.id === selectedValidationId) return runMutation.data;
    return detail.data;
  }, [runMutation.data, selectedValidationId, detail.data]);

  const selectInstrument = (item) => {
    setSelectedInstrument(item);
    setInstrumentText(item.tradingSymbol);
    setSearchTerm('');
  };

  const clearInstrument = () => {
    setSelectedInstrument(null);
    setInstrumentText('');
    setSearchTerm('');
  };

  const validDates = fromDate && toDate && fromDate < toDate
    && (new Date(toDate + 'T00:00:00Z') - new Date(fromDate + 'T00:00:00Z')) / 86400000 <= 365;
  const canRun = !!apiKey && !!selectedInstrument && validDates && Number(initialCapital) >= 10000
    && Number(initialCapital) <= 100000000 && !runMutation.isPending;

  const submit = (event) => {
    event.preventDefault();
    if (!canRun) return;
    runMutation.mutate({
      instrumentId: selectedInstrument.id,
      interval,
      fromDate,
      toDate,
      outOfSamplePercent: Number(outOfSamplePercent),
      initialCapital: Number(initialCapital),
    }, {
      onSuccess: (data) => {
        setSelectedValidationId(data.validation.id);
        history.refetch();
      },
    });
  };

  const selectHistory = (item) => {
    setSelectedValidationId(item.id);
    runMutation.reset();
  };

  const validation = result?.validation;
  const holdoutTrades = result?.outOfSample?.trades || [];

  return (
    <div className="flex flex-col gap-5">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-lg font-semibold text-text">Backtesting & holdout validation</h1>
          <p className="mt-1 max-w-3xl text-xs leading-5 text-muted">
            Compare the fixed SMA/volume strategy across an earlier training period and a later, untouched evaluation window using stored historical candles.
          </p>
        </div>
        <Badge tone="neutral"><ShieldCheck className="mr-1 inline h-3 w-3" /> PAPER only</Badge>
      </div>

      <Notice tone="neutral" title="How to read this result">
        The holdout window uses earlier candles only for indicator warm-up; it cannot open trades before the split date. Training and holdout simulations start with separate initial capital. This is a fixed-parameter historical test, not a promise of future performance or investment advice.
      </Notice>

      {!apiKey && <Notice tone="warn" title="Operator API key required">Connect the operator key in the top bar to run validation and view saved reports. The key stays in memory and is sent only in API request headers.</Notice>}

      <Panel title="Run a holdout validation" subtitle="Uses persisted historical data; it does not place broker orders">
        <form onSubmit={submit} className="flex flex-col gap-4">
          <div>
            <label htmlFor="backtest-instrument" className="mb-1 block text-xs font-medium text-muted">NSE cash-equity instrument</label>
            {selectedInstrument ? (
              <div className="flex items-center justify-between gap-3 rounded-md border border-border bg-surface-2/40 px-3 py-2">
                <div className="min-w-0">
                  <p className="truncate text-sm font-semibold text-text">{selectedInstrument.tradingSymbol}</p>
                  <p className="truncate text-xs text-muted">{selectedInstrument.exchange} · Token {selectedInstrument.symbolToken} · {selectedInstrument.name || 'Equity'}</p>
                </div>
                <Button size="sm" onClick={clearInstrument}>Change</Button>
              </div>
            ) : (
              <>
                <input id="backtest-instrument" className={inputClass} value={instrumentText}
                  onChange={(event) => setInstrumentText(event.target.value)} placeholder="Search NSE symbol (e.g. RELIANCE)"
                  autoComplete="off" maxLength={100} disabled={!apiKey} />
                {apiKey && searchTerm && (
                  <div className="mt-2 max-h-64 overflow-y-auto rounded-md border border-border">
                    {matches.isFetching && <p className="px-3 py-2 text-xs text-muted">Searching the active catalogue…</p>}
                    {matches.isError && <div className="p-2"><ErrorState error={matches.error} onRetry={() => matches.refetch()} /></div>}
                    {matches.data?.content?.map((item) => (
                      <button type="button" key={item.id} onClick={() => selectInstrument(item)}
                        className="flex w-full items-center justify-between gap-3 border-b border-border/70 px-3 py-2 text-left last:border-0 hover:bg-surface-2">
                        <span className="min-w-0">
                          <span className="block truncate text-sm font-medium text-text">{item.tradingSymbol}</span>
                          <span className="block truncate text-xs text-muted">{item.exchange} · {item.name || item.instrumentType || 'Instrument'}</span>
                        </span>
                        <span className="shrink-0 text-[11px] text-muted">{item.instrumentType || '—'}</span>
                      </button>
                    ))}
                    {!matches.isFetching && !matches.isError && matches.data?.content?.length === 0 && (
                      <p className="px-3 py-2 text-xs text-muted">No matching instruments. Import the catalogue on the Instruments page first.</p>
                    )}
                  </div>
                )}
              </>
            )}
          </div>

          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
            <div>
              <label htmlFor="backtest-interval" className="mb-1 block text-xs font-medium text-muted">Candle interval</label>
              <select id="backtest-interval" className={selectClass} value={interval} onChange={(event) => setInterval(event.target.value)}>
                {INTERVALS.map(([value, label]) => <option key={value} value={value}>{label}</option>)}
              </select>
            </div>
            <div>
              <label htmlFor="backtest-from" className="mb-1 block text-xs font-medium text-muted">From date</label>
              <input id="backtest-from" className={inputClass} type="date" value={fromDate}
                max={toDate ? shiftDate(toDate, -1) : defaultTo}
                onChange={(event) => setFromDate(event.target.value)} required />
            </div>
            <div>
              <label htmlFor="backtest-to" className="mb-1 block text-xs font-medium text-muted">To date</label>
              <input id="backtest-to" className={inputClass} type="date" value={toDate}
                min={fromDate ? shiftDate(fromDate, 1) : undefined} max={defaultTo}
                onChange={(event) => setToDate(event.target.value)} required />
            </div>
            <div>
              <label htmlFor="backtest-holdout" className="mb-1 block text-xs font-medium text-muted">Holdout portion</label>
              <select id="backtest-holdout" className={selectClass} value={outOfSamplePercent}
                onChange={(event) => setOutOfSamplePercent(event.target.value)}>
                <option value="20">20% holdout</option>
                <option value="30">30% holdout</option>
                <option value="40">40% holdout</option>
                <option value="50">50% holdout</option>
              </select>
            </div>
            <div>
              <label htmlFor="backtest-capital" className="mb-1 block text-xs font-medium text-muted">Initial paper capital (₹)</label>
              <input id="backtest-capital" className={inputClass} type="number" min="10000" max="100000000"
                step="1000" value={initialCapital} onChange={(event) => setInitialCapital(event.target.value)} required />
            </div>
          </div>

          {!validDates && <p className="text-xs text-loss">Choose a date range between 2 and 366 calendar days. The end date must be before today and the backend caps the range at 366 days.</p>}
          {Number(initialCapital) < 10000 || Number(initialCapital) > 100000000
            ? <p className="text-xs text-loss">Initial capital must be between ₹10,000 and ₹10,00,00,000.</p>
            : null}

          <div className="flex flex-wrap items-center justify-between gap-3 border-t border-border pt-3">
            <p className="max-w-2xl text-xs leading-5 text-muted">
              The app requires enough stored candles in both windows. Use Market data to import history before running a test. Each period starts fresh with the same capital.
            </p>
            <Button type="submit" variant="primary" icon={Play} loading={runMutation.isPending} disabled={!canRun}>
              Run holdout validation
            </Button>
          </div>
        </form>
        {runMutation.isError && <div className="mt-3"><Notice tone="loss" title="Validation failed">{runMutation.error?.message || 'The backtest could not run.'}</Notice></div>}
        {runMutation.isSuccess && !runMutation.isError && (
          <div className="mt-3"><Notice tone="neutral" title="Validation saved">
            Training and holdout results were saved separately. The comparison below is from historical data only.
          </Notice></div>
        )}
      </Panel>

      <Panel title="Recent validations" subtitle="Stored validation reports are reusable and auditable"
        actions={<Button size="sm" variant="ghost" icon={RefreshCw} loading={history.isFetching} onClick={() => history.refetch()}>Refresh</Button>}>
        {!apiKey && <EmptyState title="Connect the operator key" description="Saved validation history is available after connecting the operator API key." />}
        {apiKey && history.isPending && <Skeleton className="h-20 w-full" />}
        {apiKey && history.isError && <ErrorState error={history.error} onRetry={() => history.refetch()} />}
        {apiKey && history.data?.length === 0 && <EmptyState title="No validation runs yet" description="Run your first validation above once historical candles are available." />}
        {history.data?.length > 0 && (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[800px] text-left text-xs">
              <thead><tr className="border-b border-border text-[11px] uppercase tracking-wide text-muted">
                <th className="px-2 py-2 font-medium">Instrument</th>
                <th className="px-2 py-2 font-medium">Date range</th>
                <th className="px-2 py-2 font-medium">Split</th>
                <th className="px-2 py-2 text-right font-medium">Training</th>
                <th className="px-2 py-2 text-right font-medium">Holdout</th>
                <th className="px-2 py-2 text-right font-medium">Delta</th>
                <th className="px-2 py-2 font-medium">Created</th>
              </tr></thead>
              <tbody>
                {history.data.map((item) => (
                  <tr key={item.id} className={`cursor-pointer border-b border-border/60 last:border-0 hover:bg-surface-2 ${selectedValidationId === item.id ? 'bg-accent/10' : ''}`}
                    onClick={() => selectHistory(item)} tabIndex={0}
                    onKeyDown={(event) => { if (event.key === 'Enter') selectHistory(item); }}>
                    <td className="px-2 py-2">
                      <span className="block font-medium text-text">{item.tradingSymbol}</span>
                      <span className="text-[11px] text-muted">{item.interval}</span>
                    </td>
                    <td className="px-2 py-2 text-text">{formatBusinessDate(item.fromDate)} – {formatBusinessDate(item.toDate)}</td>
                    <td className="px-2 py-2 text-text">{formatBusinessDate(item.splitDate)} · {item.outOfSamplePercent}%</td>
                    <td className="px-2 py-2 text-right num text-text">{formatPct(item.trainingReturnPercent)}</td>
                    <td className={`px-2 py-2 text-right num ${pnlTone(item.outOfSampleReturnPercent)}`}>{formatPct(item.outOfSampleReturnPercent)}</td>
                    <td className={`px-2 py-2 text-right num ${pnlTone(item.returnDeltaPercentagePoints)}`}>{formatPct(item.returnDeltaPercentagePoints)}</td>
                    <td className="px-2 py-2 text-muted">{formatDateTime(item.createdAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Panel>

      {selectedValidationId && (
        <div className="flex flex-col gap-4">
          {detail.isPending && !result && <Skeleton className="h-36 w-full" />}
          {detail.isError && !result && <ErrorState error={detail.error} onRetry={() => detail.refetch()} />}
          {validation && (
            <>
              <Panel title="Validation comparison" subtitle={`${validation.tradingSymbol} · ${validation.interval} · split ${formatBusinessDate(validation.splitDate)}`}
                actions={<Badge tone="neutral">Saved report</Badge>}>
                <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-4">
                  <SummaryMetric label="Training return" value={<ReturnTone value={validation.trainingReturnPercent} />} hint={`${formatInteger(validation.trainingCandles)} candles`} />
                  <SummaryMetric label="Holdout return" value={<ReturnTone value={validation.outOfSampleReturnPercent} />} hint={`${formatInteger(validation.outOfSampleCandles)} candles`} />
                  <SummaryMetric label="Holdout minus training" value={formatPct(validation.returnDeltaPercentagePoints)} tone={pnlTone(validation.returnDeltaPercentagePoints)} hint="Percentage-point difference" />
                  <SummaryMetric label="Holdout size" value={`${validation.outOfSamplePercent}%`} hint={`${formatBusinessDate(validation.fromDate)} – ${formatBusinessDate(validation.toDate)}`} />
                </div>
                <p className="mt-3 text-xs text-muted">Created {formatDateTime(validation.createdAt)} · Report ID <span className="font-mono">{validation.id}</span></p>
              </Panel>

              <div className="grid grid-cols-1 gap-4 xl:grid-cols-2">
                <PeriodPanel title="Training period" period={result.training} badge="Earlier period" />
                <PeriodPanel title="Out-of-sample period" period={result.outOfSample} badge="Holdout period" />
              </div>

              <Panel title="Holdout paper trades" subtitle="Only trades whose entry timestamps fall on or after the split boundary">
                <TradeTable trades={holdoutTrades} />
              </Panel>
            </>
          )}
        </div>
      )}
    </div>
  );
}

import { Activity, Search, ShieldCheck, Send } from 'lucide-react';
import { Badge, Button, EmptyState, ErrorState, Notice, Panel, Skeleton } from '../../ui.jsx';
import { formatDateTime, formatInr, formatInteger } from '../../../lib/format.js';

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

function ActionBadge({ action }) {
  const tone = action === 'BUY' ? 'gain' : action === 'SELL' ? 'loss' : 'neutral';
  return <Badge tone={tone}>{action}</Badge>;
}

function EvaluationCard({ evaluation, onSimulate }) {
  if (!evaluation) return null;
  const signal = evaluation.signal;
  return (
    <Panel title="Strategy evaluation" subtitle={`${evaluation.symbol} · ${evaluation.exchange} · ${evaluation.interval}`}
      actions={signal ? <Badge tone="neutral">{signal.status}</Badge> : <Badge tone="warn">NO SIGNAL</Badge>}>
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
        <div className="rounded-md border border-border bg-surface-2/40 p-3">
          <p className="text-[11px] uppercase tracking-wide text-muted">History</p>
          <p className="mt-1 text-lg font-semibold text-text">{formatInteger(evaluation.availableCandles)} <span className="text-xs font-normal text-muted">/ {evaluation.requiredCandles}</span></p>
          <p className="mt-1 text-[11px] text-muted">candles required</p>
        </div>
        <div className="rounded-md border border-border bg-surface-2/40 p-3">
          <p className="text-[11px] uppercase tracking-wide text-muted">Reference close</p>
          <p className="mt-1 text-lg font-semibold text-text">{signal ? formatInr(signal.referencePrice) : '—'}</p>
          <p className="mt-1 text-[11px] text-muted">stored candle, not a live quote</p>
        </div>
        <div className="rounded-md border border-border bg-surface-2/40 p-3">
          <p className="text-[11px] uppercase tracking-wide text-muted">Fast / slow SMA</p>
          <p className="mt-1 text-sm font-semibold text-text">{signal ? `${formatInr(signal.fastAverage)} / ${formatInr(signal.slowAverage)}` : '—'}</p>
          <p className="mt-1 text-[11px] text-muted">trend condition</p>
        </div>
        <div className="rounded-md border border-border bg-surface-2/40 p-3">
          <p className="text-[11px] uppercase tracking-wide text-muted">Latest candle</p>
          <p className="mt-1 text-sm font-semibold text-text">{evaluation.latestCandleAt ? formatDateTime(evaluation.latestCandleAt) : '—'}</p>
          <p className="mt-1 text-[11px] text-muted">source: {evaluation.dataSource || 'unknown'}</p>
        </div>
      </div>

      {signal ? (
        <div className="mt-4 rounded-md border border-border p-4">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <div className="flex items-center gap-2"><ActionBadge action={signal.action} /><span className="text-sm font-semibold text-text">{signal.strategy}</span></div>
            <span className="text-xs text-muted">Candidate strength {(Number(signal.confidence) * 100).toFixed(0)}%</span>
          </div>
          <div className="mt-3 grid grid-cols-2 gap-3 sm:grid-cols-4">
            <div><p className="text-xs text-muted">Reference</p><p className="mt-1 text-sm font-medium text-text">{formatInr(signal.referencePrice)}</p></div>
            <div><p className="text-xs text-muted">Stop loss</p><p className="mt-1 text-sm font-medium text-text">{formatInr(signal.stopLoss)}</p></div>
            <div><p className="text-xs text-muted">Target</p><p className="mt-1 text-sm font-medium text-text">{formatInr(signal.target)}</p></div>
            <div><p className="text-xs text-muted">Prior average volume</p><p className="mt-1 text-sm font-medium text-text">{formatInteger(signal.averageVolume)}</p></div>
          </div>
          <div className="mt-3 flex flex-wrap gap-1.5">
            {(signal.reasonCodes || []).map((reason) => <Badge key={reason} tone="neutral">{reason}</Badge>)}
          </div>
          <div className="mt-4 flex flex-wrap items-center justify-between gap-2">
            <p className="text-xs text-muted">Evaluated {formatDateTime(signal.createdAt)}. Candidate only; risk checks still apply.</p>
            {signal.action === 'BUY' && <Button variant="primary" icon={Send} onClick={() => onSimulate(signal)}>Simulate paper order</Button>}
          </div>
        </div>
      ) : (
        <div className="mt-4">
          <Notice tone="warn" title="Not enough stored history">
            {evaluation.reasonCodes?.includes('INSUFFICIENT_HISTORY')
              ? `This strategy requires at least ${evaluation.requiredCandles} stored ${evaluation.interval} candles. Fetch historical candles on the Market data page, then evaluate again.`
              : (evaluation.reasonCodes || []).join(', ')}
          </Notice>
        </div>
      )}
    </Panel>
  );
}

export default function SignalsWorkspace({ model, actions, clear = false }) {
  const { connected, draftQuery, selectedInstrument, interval, instruments, evaluateMutation, history, evaluation } = model;
  const { setDraftQuery, submitSearch, onInstrumentChange, onIntervalChange, evaluate, openSimulate, retryInstruments, retryHistory } = actions;
  return (
    <div className={clear ? "flex flex-col gap-6" : "flex flex-col gap-5"}>
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-lg font-semibold text-text">Deterministic strategies</h1>
          <p className="mt-1 max-w-3xl text-xs leading-5 text-muted">
            Evaluate stored OHLCV candles with a fixed SMA trend and prior-volume confirmation strategy. Outputs are explainable candidates, not recommendations or executable orders.
          </p>
        </div>
        <Badge tone="neutral"><ShieldCheck className="mr-1 inline h-3 w-3" /> Paper only</Badge>
      </div>

      <Notice tone="neutral" title="How the strategy works">
        The default strategy compares a 5-candle simple moving average with a 20-candle average, checks the latest close and confirms that the latest volume meets the previous 20-candle average. Use a complete stored history before interpreting a candidate. It is long-only: a confirmed setup can emit BUY; other conditions emit WATCH. All orders remain subject to the existing risk policy, fees and slippage.
      </Notice>

      {!connected && <Notice tone="warn" title="Operator API key required">Connect the operator API key in the top bar to search instruments, evaluate strategies and view stored signals.</Notice>}

      <Panel title="Evaluate an instrument" subtitle="Uses only the currently stored historical candles; no live quote is fetched">
        <form onSubmit={submitSearch} className="grid grid-cols-1 gap-3 md:grid-cols-[minmax(14rem,2fr)_minmax(12rem,2fr)_minmax(9rem,1fr)_auto] md:items-end">
          <div>
            <label htmlFor="strategy-symbol" className="mb-1 block text-xs font-medium text-muted">NSE equity symbol or name</label>
            <input id="strategy-symbol" className={inputClass} value={draftQuery} onChange={(e) => setDraftQuery(e.target.value)}
              placeholder="RELIANCE, INFY, SBIN…" maxLength={100} autoComplete="off" />
          </div>
          <div>
            <label htmlFor="strategy-instrument" className="mb-1 block text-xs font-medium text-muted">Matched instrument</label>
            <select id="strategy-instrument" className={inputClass} value={selectedInstrument ? String(selectedInstrument.id) : ''}
              onChange={(e) => onInstrumentChange(e.target.value)}
              disabled={!instruments.data?.content?.length}>
              {!selectedInstrument && <option value="">Select a current instrument</option>}
              {(instruments.data?.content || []).map((item) =>
                <option key={item.id} value={String(item.id)}>{item.tradingSymbol} · {item.exchange}</option>)}
            </select>
          </div>
          <div>
            <label htmlFor="strategy-interval" className="mb-1 block text-xs font-medium text-muted">Candle interval</label>
            <select id="strategy-interval" className={inputClass} value={interval} onChange={(e) => onIntervalChange(e.target.value)}>
              {INTERVALS.map(([value, label]) => <option key={value} value={value}>{label}</option>)}
            </select>
          </div>
          <div className="flex gap-2">
            <Button type="submit" variant="secondary" icon={Search} disabled={!connected || !draftQuery.trim()}>Search</Button>
            <Button type="button" variant="primary" icon={Activity} loading={evaluateMutation.isPending}
              disabled={!connected || !selectedInstrument || instruments.isPending || instruments.isError || evaluateMutation.isPending} onClick={evaluate}>Evaluate</Button>
          </div>
        </form>
        {instruments.isError && <div className="mt-3"><ErrorState error={instruments.error} onRetry={() => retryInstruments()} /></div>}
        {instruments.data && instruments.data.content.length === 0 && <div className="mt-3"><EmptyState title="No matching NSE equity" description="Try another symbol, or import the latest instrument master from the Instruments page." /></div>}
        {evaluateMutation.isError && <div className="mt-3"><ErrorState error={evaluateMutation.error} /></div>}
      </Panel>

      {evaluateMutation.isPending && <Skeleton className="h-48 w-full" />}
      <EvaluationCard evaluation={evaluation} onSimulate={openSimulate} />

      <Panel title="Persisted signal history" subtitle="Deterministic strategy outputs saved in PostgreSQL; repeated evaluation of the same candle updates the same record">
        {history.isError && <ErrorState error={history.error} onRetry={() => retryHistory()} />}
        {history.isPending ? <Skeleton className="h-40 w-full" /> : history.data?.length ? (
          <div className="overflow-x-auto rounded-md border border-border">
            <table className="min-w-[850px] w-full text-left text-xs">
              <thead className="bg-surface-2/70 text-[11px] uppercase tracking-wide text-muted">
                <tr>
                  <th className="px-3 py-2 font-medium">Symbol</th>
                  <th className="px-3 py-2 font-medium">Interval</th>
                  <th className="px-3 py-2 font-medium">Action</th>
                  <th className="px-3 py-2 text-right font-medium">Reference</th>
                  <th className="px-3 py-2 text-right font-medium">Stop / target</th>
                  <th className="px-3 py-2 font-medium">Latest candle</th>
                  <th className="px-3 py-2 font-medium">Status</th>
                </tr>
              </thead>
              <tbody>
                {history.data.map((signal) => (
                  <tr key={signal.id} className="border-t border-border/70">
                    <td className="px-3 py-2 font-medium text-text">{signal.symbol}</td>
                    <td className="px-3 py-2 text-muted">{signal.interval}</td>
                    <td className="px-3 py-2"><ActionBadge action={signal.action} /></td>
                    <td className="px-3 py-2 text-right">{formatInr(signal.referencePrice)}</td>
                    <td className="px-3 py-2 text-right">{formatInr(signal.stopLoss)} / {formatInr(signal.target)}</td>
                    <td className="px-3 py-2 text-muted">{formatDateTime(signal.latestCandleAt)}</td>
                    <td className="px-3 py-2"><Badge tone="neutral">{signal.status}</Badge></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : <EmptyState title="No generated signals yet" description="Search for an NSE equity, ensure candles are stored on the Market data page, and evaluate it." />}
      </Panel>


    </div>
  );
}

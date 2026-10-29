import { useEffect, useMemo, useState } from 'react';
import { Download, Info, RefreshCw, ShieldCheck } from 'lucide-react';
import { useApp } from '../context/AppContext.jsx';
import { useBacktestValidationDetail, useBacktestValidations, useInstrumentSearch, useRunBacktestValidation } from '../hooks/useApi.js';
import { useBacktestDetail, useBacktestEquity, useBacktestRuns, useExportBacktestCsv, useRunBacktest, useRunWalkForward } from '../hooks/useBacktestStudio.js';
import { Badge, Button, EmptyState, ErrorState, Notice, Panel, Skeleton } from '../components/ui.jsx';
import BacktestForm from '../components/backtest/BacktestForm.jsx';
import BacktestMetricsGrid from '../components/backtest/BacktestMetricsGrid.jsx';
import EquityCurveChart from '../components/backtest/EquityCurveChart.jsx';
import DrawdownChart from '../components/backtest/DrawdownChart.jsx';
import BacktestTradeTable from '../components/backtest/BacktestTradeTable.jsx';
import ValidationComparison from '../components/backtest/ValidationComparison.jsx';
import WalkForwardPanel from '../components/backtest/WalkForwardPanel.jsx';
import AssumptionsDrawer from '../components/backtest/AssumptionsDrawer.jsx';
import { formatBusinessDate, formatDateTime, formatPct, pnlTone } from '../lib/format.js';

function todayInIndia() {
  const parts = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Kolkata', year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(new Date());
  const part = (type) => parts.find((item) => item.type === type)?.value;
  return `${part('year')}-${part('month')}-${part('day')}`;
}
function shiftDate(value, days) {
  const date = new Date(`${value}T00:00:00Z`);
  date.setUTCDate(date.getUTCDate() + days);
  return date.toISOString().slice(0, 10);
}

function RunHistory({ query, selectedId, onSelect }) {
  if (query.isPending) return <Skeleton className="h-28 w-full" />;
  if (query.isError) return <ErrorState error={query.error} onRetry={() => query.refetch()} />;
  const items = query.data?.items || [];
  if (!items.length) return <EmptyState title="No backtest runs yet" description="Run the first point-in-time simulation from the setup panel." />;
  return (
    <div className="overflow-x-auto">
      <table className="w-full min-w-[760px] text-left text-xs">
        <thead><tr className="border-b border-border text-[10px] uppercase tracking-wide text-muted"><th className="px-2 py-2 font-medium">Instrument</th><th className="px-2 py-2 font-medium">Period</th><th className="px-2 py-2 text-right font-medium">Return</th><th className="px-2 py-2 text-right font-medium">Win rate</th><th className="px-2 py-2 text-right font-medium">Max DD</th><th className="px-2 py-2 font-medium">Created</th></tr></thead>
        <tbody>{items.map((item) => <tr key={item.id} tabIndex={0} onClick={() => onSelect(item.id)} onKeyDown={(e) => e.key === 'Enter' && onSelect(item.id)} className={`cursor-pointer border-b border-border/60 last:border-0 hover:bg-surface-2 ${selectedId === item.id ? 'bg-accent/10' : ''}`}><td className="px-2 py-2"><span className="block font-semibold text-text">{item.tradingSymbol}</span><span className="text-[10px] text-muted">{item.strategyVersion}</span></td><td className="px-2 py-2 text-text">{formatBusinessDate(item.fromDate)} – {formatBusinessDate(item.toDate)}</td><td className={`px-2 py-2 text-right num font-semibold ${pnlTone(item.totalReturnPercent)}`}>{formatPct(item.totalReturnPercent)}</td><td className="px-2 py-2 text-right num text-text">{formatPct(item.winRatePercent)}</td><td className="px-2 py-2 text-right num text-warn">{formatPct(item.maxDrawdownPercent)}</td><td className="px-2 py-2 text-muted">{formatDateTime(item.createdAt)}</td></tr>)}</tbody>
      </table>
    </div>
  );
}

export default function BacktestingPage() {
  const { apiKey, experience } = useApp();
  const today = todayInIndia();
  const defaultTo = shiftDate(today, -1);
  const [form, setForm] = useState({ interval: 'ONE_DAY', fromDate: shiftDate(defaultTo, -179), toDate: defaultTo, initialCapital: '80000' });
  const [instrumentText, setInstrumentText] = useState('');
  const [searchTerm, setSearchTerm] = useState('');
  const [selectedInstrument, setSelectedInstrument] = useState(null);
  const [selectedRunId, setSelectedRunId] = useState(null);
  const [walkForward, setWalkForward] = useState(null);
  const [assumptionsOpen, setAssumptionsOpen] = useState(false);
  const [validationId, setValidationId] = useState(null);

  useEffect(() => { const timer = setTimeout(() => setSearchTerm(instrumentText.trim()), 250); return () => clearTimeout(timer); }, [instrumentText]);

  const matches = useInstrumentSearch({ q: searchTerm, exchange: 'NSE', type: 'EQ', page: 0, size: 12, enabled: !!apiKey && !selectedInstrument && searchTerm.length > 0 });
  const runs = useBacktestRuns({ instrumentId: selectedInstrument?.id, page: 0, size: 20 });
  const detail = useBacktestDetail(selectedRunId);
  const equity = useBacktestEquity(selectedRunId);
  const runMutation = useRunBacktest();
  const walkMutation = useRunWalkForward();
  const exportMutation = useExportBacktestCsv();

  const validations = useBacktestValidations(10);
  const validationDetail = useBacktestValidationDetail(validationId);
  const validationMutation = useRunBacktestValidation();

  const selectedDetail = useMemo(() => {
    if (runMutation.data?.run?.id === selectedRunId) return runMutation.data;
    return detail.data;
  }, [runMutation.data, selectedRunId, detail.data]);
  const run = selectedDetail?.run || runs.data?.items?.[0] || null;
  const totalRuns = runs.data?.totalElements || 0;
  const validDates = form.fromDate && form.toDate && form.fromDate < form.toDate;
  const validCapital = Number(form.initialCapital) >= 10000 && Number(form.initialCapital) <= 100000000;
  const canRun = !!apiKey && !!selectedInstrument && validDates && validCapital && !runMutation.isPending;

  const changeForm = (key, value) => setForm((current) => ({ ...current, [key]: value }));
  const selectInstrument = (item) => { setSelectedInstrument(item); setInstrumentText(item.tradingSymbol); setSearchTerm(''); };
  const clearInstrument = () => { setSelectedInstrument(null); setInstrumentText(''); setSearchTerm(''); setSelectedRunId(null); setWalkForward(null); };

  const payload = () => ({ instrumentId: selectedInstrument.id, interval: form.interval, fromDate: form.fromDate, toDate: form.toDate, initialCapital: Number(form.initialCapital) });
  const submit = (event) => {
    event.preventDefault();
    if (!canRun) return;
    runMutation.mutate(payload(), { onSuccess: (data) => { setSelectedRunId(data.run.id); setWalkForward(null); runs.refetch(); } });
  };
  const runWalkForward = () => {
    if (!canRun && !selectedInstrument) return;
    const days = Math.max(30, Math.floor((new Date(form.toDate) - new Date(form.fromDate)) / 86400000));
    const validationWindowDays = Math.max(10, Math.floor(days * 0.2));
    const trainingWindowDays = Math.max(20, Math.floor(days * 0.5));
    walkMutation.mutate({ ...payload(), trainingWindowDays, validationWindowDays, stepDays: validationWindowDays }, { onSuccess: setWalkForward });
  };
  const runHoldout = () => {
    if (!canRun) return;
    validationMutation.mutate({ ...payload(), outOfSamplePercent: 30 }, { onSuccess: (data) => { setValidationId(data.validation.id); validations.refetch(); } });
  };

  return (
    <div className={`backtest-studio relative flex flex-col gap-4 ${experience === 'pro-terminal' ? 'backtest-pro' : 'backtest-clear'}`}>
      <div className="backtest-ambient" aria-hidden />
      <header className="relative flex flex-wrap items-end justify-between gap-3">
        <div><h1 className="backtest-title text-xl font-bold text-text sm:text-3xl">Backtest Studio</h1><p className="mt-1 text-xs text-muted sm:text-sm">Point-in-time data, charges, slippage and walk-forward evaluation</p></div>
        <div className="flex items-center gap-2"><Badge tone="gain"><ShieldCheck className="mr-1 h-3 w-3" /> PAPER only</Badge><Button size="sm" variant="ghost" icon={Info} onClick={() => setAssumptionsOpen(true)}>Assumptions</Button></div>
      </header>

      {!apiKey && <Notice tone="warn" title="Operator API key required">Connect the operator key in the application header to run or inspect stored backtests.</Notice>}
      <BacktestMetricsGrid run={run} totalRuns={totalRuns} compact={experience === 'clear-invest'} />

      <div className="grid grid-cols-1 gap-4 xl:grid-cols-2">
        <Panel title="Backtest setup" subtitle="Deterministic historical PAPER simulation" className="backtest-glass">
          <BacktestForm apiKey={apiKey} form={form} onChange={changeForm} instrumentText={instrumentText} onInstrumentTextChange={setInstrumentText} selectedInstrument={selectedInstrument} onSelectInstrument={selectInstrument} onClearInstrument={clearInstrument} matches={matches} canRun={canRun} running={runMutation.isPending} onSubmit={submit} />
          {runMutation.isError && <div className="mt-3"><Notice tone="loss" title="Backtest failed">{runMutation.error?.message}</Notice></div>}
        </Panel>
        <Panel title="Walk-forward result" subtitle="Rolling out-of-sample stability check" className="backtest-glass">
          <WalkForwardPanel detail={walkForward} onRun={runWalkForward} running={walkMutation.isPending} disabled={!selectedInstrument || !validDates || !validCapital} />
          {walkMutation.isError && <div className="mt-3"><Notice tone="loss" title="Walk-forward failed">{walkMutation.error?.message}</Notice></div>}
        </Panel>
      </div>

      {experience === 'clear-invest' && (
        <Panel title="What this result means" subtitle="Plain-language view for decision support">
          {run ? <div className="grid gap-3 md:grid-cols-3"><div className="rounded-xl bg-surface-2 p-4"><p className="text-xs font-semibold text-muted">Outcome</p><p className={`mt-1 text-lg font-bold ${pnlTone(run.totalReturnPercent)}`}>{formatPct(run.totalReturnPercent)} historical return</p><p className="mt-1 text-xs text-muted">After modeled fees and slippage.</p></div><div className="rounded-xl bg-surface-2 p-4"><p className="text-xs font-semibold text-muted">Risk</p><p className="mt-1 text-lg font-bold text-text">{formatPct(run.maxDrawdownPercent)} max drawdown</p><p className="mt-1 text-xs text-muted">Largest peak-to-trough decline in this simulation.</p></div><div className="rounded-xl bg-surface-2 p-4"><p className="text-xs font-semibold text-muted">Confidence</p><p className="mt-1 text-lg font-bold text-text">Use walk-forward + holdout</p><p className="mt-1 text-xs text-muted">A single backtest is not sufficient evidence for live trading.</p></div></div> : <EmptyState title="Run a backtest first" description="A guided explanation will appear after a result is available." />}
        </Panel>
      )}

      {selectedRunId && (
        <>
          <div className="grid grid-cols-1 gap-4 xl:grid-cols-2"><Panel title="Equity curve" subtitle="Marked-to-market paper equity"><EquityCurveChart points={equity.data || []} /></Panel><Panel title="Drawdown" subtitle="Peak-to-trough decline derived from equity"><DrawdownChart points={equity.data || []} /></Panel></div>
          <Panel title="Trades" subtitle={`${run?.tradingSymbol || 'Selected run'} · ${run?.strategyVersion || 'strategy'}`} actions={<Button size="sm" icon={Download} loading={exportMutation.isPending} onClick={() => exportMutation.mutate({ id: selectedRunId, symbol: run?.tradingSymbol })}>Export CSV</Button>}>
            {detail.isPending && !selectedDetail ? <Skeleton className="h-32 w-full" /> : detail.isError && !selectedDetail ? <ErrorState error={detail.error} onRetry={() => detail.refetch()} /> : <BacktestTradeTable trades={selectedDetail?.trades || []} />}
          </Panel>
        </>
      )}

      <Panel title="Recent backtests" subtitle="Auditable stored runs" actions={<Button size="sm" variant="ghost" icon={RefreshCw} loading={runs.isFetching} onClick={() => runs.refetch()}>Refresh</Button>}><RunHistory query={runs} selectedId={selectedRunId} onSelect={setSelectedRunId} /></Panel>

      <Panel title="Holdout validation" subtitle="Compare an earlier training period with a later untouched evaluation period">
        <div className="mb-3 flex flex-wrap items-center justify-between gap-2"><p className="max-w-3xl text-xs text-muted">This preserves the existing P3 holdout workflow. The holdout cannot open trades before its split boundary and starts with separate paper capital.</p><Button size="sm" variant="primary" disabled={!canRun} loading={validationMutation.isPending} onClick={runHoldout}>Run 30% holdout</Button></div>
        {validationMutation.isError && <Notice tone="loss" title="Validation failed">{validationMutation.error?.message}</Notice>}
        {!validationId && validations.data?.length > 0 && <div className="mb-3 flex flex-wrap gap-2">{validations.data.slice(0, 5).map((item) => <Button key={item.id} size="sm" variant="ghost" onClick={() => setValidationId(item.id)}>{item.tradingSymbol} · {formatBusinessDate(item.splitDate)}</Button>)}</div>}
        {validationId && validationDetail.isPending ? <Skeleton className="h-28 w-full" /> : validationId && validationDetail.isError ? <ErrorState error={validationDetail.error} onRetry={() => validationDetail.refetch()} /> : <ValidationComparison detail={validationMutation.data?.validation?.id === validationId ? validationMutation.data : validationDetail.data} />}
      </Panel>

      <AssumptionsDrawer open={assumptionsOpen} onOpenChange={setAssumptionsOpen} run={run} />
    </div>
  );
}

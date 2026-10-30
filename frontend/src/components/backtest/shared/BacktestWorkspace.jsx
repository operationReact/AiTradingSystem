import { Download, Info, RefreshCw, ShieldCheck } from 'lucide-react';
import { Badge, Button, EmptyState, ErrorState, Notice, Panel, Skeleton } from '../../ui.jsx';
import BacktestForm from '../../backtest/BacktestForm.jsx';
import BacktestMetricsGrid from '../../backtest/BacktestMetricsGrid.jsx';
import EquityCurveChart from '../../backtest/EquityCurveChart.jsx';
import DrawdownChart from '../../backtest/DrawdownChart.jsx';
import BacktestTradeTable from '../../backtest/BacktestTradeTable.jsx';
import ValidationComparison from '../../backtest/ValidationComparison.jsx';
import WalkForwardPanel from '../../backtest/WalkForwardPanel.jsx';
import AssumptionsDrawer from '../../backtest/AssumptionsDrawer.jsx';
import { formatBusinessDate, formatDateTime, formatPct, pnlTone } from '../../../lib/format.js';


function RunHistory({ query, selectedId, onSelect, onRetry }) {
  if (query.isPending) return <Skeleton className="h-28 w-full" />;
  if (query.isError) return <ErrorState error={query.error} onRetry={() => onRetry()} />;
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

export default function BacktestWorkspace({ model, actions, clear = false }) {
  const { connected, run, totalRuns, form, instrumentText, selectedInstrument, matches, canRun, runMutation,
    walkForward, walkMutation, validDates, validCapital, selectedRunId, equity, selectedDetail, detail,
    exportMutation, runs, validationMutation, validationId, validations, validationDetail, assumptionsOpen } = model;
  const { changeForm, setInstrumentText, selectInstrument, clearInstrument, submit, runWalkForward, runHoldout,
    setAssumptionsOpen, setSelectedRunId, setValidationId, exportCsv, retryDetail, refreshRuns, retryValidationDetail } = actions;
  return (
    <div className={`backtest-studio relative flex flex-col gap-4 ${!clear ? 'backtest-pro' : 'backtest-clear'}`}>
      <div className="backtest-ambient" aria-hidden />
      <header className="relative flex flex-wrap items-end justify-between gap-3">
        <div><h1 className="backtest-title text-xl font-bold text-text sm:text-3xl">Backtest Studio</h1><p className="mt-1 text-xs text-muted sm:text-sm">Point-in-time data, charges, slippage and walk-forward evaluation</p></div>
        <div className="flex items-center gap-2"><Badge tone="gain"><ShieldCheck className="mr-1 h-3 w-3" /> PAPER only</Badge><Button size="sm" variant="ghost" icon={Info} onClick={() => setAssumptionsOpen(true)}>Assumptions</Button></div>
      </header>

      {!connected && <Notice tone="warn" title="Operator API key required">Connect the operator key in the application header to run or inspect stored backtests.</Notice>}
      <BacktestMetricsGrid run={run} totalRuns={totalRuns} compact={clear} />

      <div className="grid grid-cols-1 gap-4 xl:grid-cols-2">
        <Panel title="Backtest setup" subtitle="Deterministic historical PAPER simulation" className="backtest-glass">
          <BacktestForm connected={connected} form={form} onChange={changeForm} instrumentText={instrumentText} onInstrumentTextChange={setInstrumentText} selectedInstrument={selectedInstrument} onSelectInstrument={selectInstrument} onClearInstrument={clearInstrument} matches={matches} canRun={canRun} running={runMutation.isPending} onSubmit={submit} />
          {runMutation.isError && <div className="mt-3"><Notice tone="loss" title="Backtest failed">{runMutation.error?.message}</Notice></div>}
        </Panel>
        <Panel title="Walk-forward result" subtitle="Rolling out-of-sample stability check" className="backtest-glass">
          <WalkForwardPanel detail={walkForward} onRun={runWalkForward} running={walkMutation.isPending} disabled={!selectedInstrument || !validDates || !validCapital} />
          {walkMutation.isError && <div className="mt-3"><Notice tone="loss" title="Walk-forward failed">{walkMutation.error?.message}</Notice></div>}
        </Panel>
      </div>

      {clear && (
        <Panel title="What this result means" subtitle="Plain-language view for decision support">
          {run ? <div className="grid gap-3 md:grid-cols-3"><div className="rounded-xl bg-surface-2 p-4"><p className="text-xs font-semibold text-muted">Outcome</p><p className={`mt-1 text-lg font-bold ${pnlTone(run.totalReturnPercent)}`}>{formatPct(run.totalReturnPercent)} historical return</p><p className="mt-1 text-xs text-muted">After modeled fees and slippage.</p></div><div className="rounded-xl bg-surface-2 p-4"><p className="text-xs font-semibold text-muted">Risk</p><p className="mt-1 text-lg font-bold text-text">{formatPct(run.maxDrawdownPercent)} max drawdown</p><p className="mt-1 text-xs text-muted">Largest peak-to-trough decline in this simulation.</p></div><div className="rounded-xl bg-surface-2 p-4"><p className="text-xs font-semibold text-muted">Confidence</p><p className="mt-1 text-lg font-bold text-text">Use walk-forward + holdout</p><p className="mt-1 text-xs text-muted">A single backtest is not sufficient evidence for live trading.</p></div></div> : <EmptyState title="Run a backtest first" description="A guided explanation will appear after a result is available." />}
        </Panel>
      )}

      {selectedRunId && (
        <>
          <div className="grid grid-cols-1 gap-4 xl:grid-cols-2"><Panel title="Equity curve" subtitle="Marked-to-market paper equity"><EquityCurveChart points={equity.data || []} /></Panel><Panel title="Drawdown" subtitle="Peak-to-trough decline derived from equity"><DrawdownChart points={equity.data || []} /></Panel></div>
          <Panel title="Trades" subtitle={`${run?.tradingSymbol || 'Selected run'} · ${run?.strategyVersion || 'strategy'}`} actions={<Button size="sm" icon={Download} loading={exportMutation.isPending} onClick={() => exportCsv()}>Export CSV</Button>}>
            {detail.isPending && !selectedDetail ? <Skeleton className="h-32 w-full" /> : detail.isError && !selectedDetail ? <ErrorState error={detail.error} onRetry={() => retryDetail()} /> : <BacktestTradeTable trades={selectedDetail?.trades || []} />}
          </Panel>
        </>
      )}

      <Panel title="Recent backtests" subtitle="Auditable stored runs" actions={<Button size="sm" variant="ghost" icon={RefreshCw} loading={runs.isFetching} onClick={() => refreshRuns()}>Refresh</Button>}><RunHistory onRetry={refreshRuns} query={runs} selectedId={selectedRunId} onSelect={setSelectedRunId} /></Panel>

      <Panel title="Holdout validation" subtitle="Compare an earlier training period with a later untouched evaluation period">
        <div className="mb-3 flex flex-wrap items-center justify-between gap-2"><p className="max-w-3xl text-xs text-muted">This preserves the existing P3 holdout workflow. The holdout cannot open trades before its split boundary and starts with separate paper capital.</p><Button size="sm" variant="primary" disabled={!canRun} loading={validationMutation.isPending} onClick={runHoldout}>Run 30% holdout</Button></div>
        {validationMutation.isError && <Notice tone="loss" title="Validation failed">{validationMutation.error?.message}</Notice>}
        {!validationId && validations.data?.length > 0 && <div className="mb-3 flex flex-wrap gap-2">{validations.data.slice(0, 5).map((item) => <Button key={item.id} size="sm" variant="ghost" onClick={() => setValidationId(item.id)}>{item.tradingSymbol} · {formatBusinessDate(item.splitDate)}</Button>)}</div>}
        {validationId && validationDetail.isPending ? <Skeleton className="h-28 w-full" /> : validationId && validationDetail.isError ? <ErrorState error={validationDetail.error} onRetry={() => retryValidationDetail()} /> : <ValidationComparison detail={validationMutation.data?.validation?.id === validationId ? validationMutation.data : validationDetail.data} />}
      </Panel>

      <AssumptionsDrawer open={assumptionsOpen} onOpenChange={setAssumptionsOpen} run={run} />
    </div>
  );
}

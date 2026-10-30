import { Clock, Settings2 } from 'lucide-react';
import { DataState } from '../../DataState.jsx';
import { Badge, Button, KpiCard, Notice, Panel, SegmentedControl, Skeleton } from '../../ui.jsx';
import { EquityChart, PnlChart, DrawdownChart, AllocationChart } from '../../dashboard/PerformanceCharts.jsx';
import {
  AgentsPanel,
  PositionsPanel,
  ProvidersPanel,
  RiskAlerts,
  SignalsPanel,
  TradesPanel,
} from '../../dashboard/OperationalPanels.jsx';
import { NewsPanel } from '../../dashboard/NewsPanel.jsx';
import { ProvenanceNewsPanel } from '../../dashboard/ProvenanceNewsPanel.jsx';
import { AngelOneStatusPanel } from '../../dashboard/AngelOneStatusPanel.jsx';
import { formatAge, formatDateTime, formatInr, formatInteger, formatPct, formatSignedInr, ageSeconds, pnlTone } from '../../../lib/format.js';

import { RANGES, WIDGETS } from '../../../lib/dashboardPreferences.js';

function KpiStrip({ summary, loading }) {
  const cards = [
    { label: 'Total equity', value: formatInr(summary?.equity) },
    { label: 'Available cash', value: formatInr(summary?.availableCash), hint: `of ${formatInr(summary?.capital)} capital` },
    { label: 'Invested capital', value: formatInr(summary?.investedCapital), hint: `Market value ${formatInr(summary?.marketValue)}` },
    { label: 'Realized P&L', value: formatSignedInr(summary?.realizedPnl), tone: pnlTone(summary?.realizedPnl) },
    { label: 'Unrealized P&L', value: formatSignedInr(summary?.unrealizedPnl), tone: pnlTone(summary?.unrealizedPnl), hint: 'Mark: last paper fill' },
    { label: 'Daily P&L', value: formatSignedInr(summary?.dailyPnl), tone: pnlTone(summary?.dailyPnl), hint: 'Realized today' },
    { label: 'Open positions', value: formatInteger(summary?.openPositions) },
    { label: 'Risk exposure', value: formatPct(summary?.exposurePercent), hint: summary?.newOrdersHalted ? 'New orders halted' : 'Of total equity' },
  ];
  return (
    <div className="grid grid-cols-2 gap-3 lg:grid-cols-4 xl:grid-cols-8">
      {cards.map((c) => (
        <KpiCard key={c.label} {...c} loading={loading} />
      ))}
    </div>
  );
}

export default function OverviewWorkspace({ model, actions, clear = false }) {
  const { connected, fetching, days, hidden, showSettings, summary, equity, portfolio, orders, signals, riskConfig,
    curve, freshness, broker, news, rss } = model;
  const { show, toggleWidget, changeRange, setShowSettings, refresh } = actions;
  return (
    <div className={clear ? 'flex flex-col gap-7 md:gap-8' : 'flex flex-col gap-4'}>
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className={clear ? 'text-2xl font-semibold tracking-tight text-text md:text-3xl' : 'text-lg font-semibold text-text'}>{clear ? 'Your paper portfolio' : 'Overview'}</h1>
          <p className="mt-0.5 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted">
            <span className="inline-flex items-center gap-1"><Clock className="h-3 w-3" aria-hidden />Refreshed {freshness}</span>
            {summary.data?.asOf && <span>Snapshot {formatDateTime(summary.data.asOf)}</span>}
            <Badge tone="accent">Paper only</Badge>
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <SegmentedControl label="Date range" options={RANGES} value={days} onChange={changeRange} />
          <Button size="sm" variant="secondary" icon={Settings2} aria-expanded={showSettings} onClick={() => setShowSettings((s) => !s)}>
            Widgets
          </Button>
          <Button size="sm" variant="secondary" loading={fetching > 0} onClick={refresh} disabled={!connected}>
            {fetching > 0 ? 'Refreshing' : 'Refresh'}
          </Button>
        </div>
      </div>

      {showSettings && (
        <Panel title="Dashboard widgets" subtitle="Saved in this browser">
          <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
            {WIDGETS.map((w) => (
              <label key={w.id} className="flex cursor-pointer items-center gap-2 text-xs text-text">
                <input type="checkbox" className="accent-accent" checked={show(w.id)} onChange={() => toggleWidget(w.id)} />
                {w.label}
              </label>
            ))}
          </div>
        </Panel>
      )}

      {!connected && (
        <Notice tone="neutral" title="Operator key required">
          Enter the operator API key in the top bar to load portfolio data. Nothing is shown until the backend confirms it.
        </Notice>
      )}

      {show('kpis') && (
        <DataState query={summary} skeleton={<div className="grid grid-cols-2 gap-3 lg:grid-cols-4 xl:grid-cols-8">{Array.from({ length: 8 }).map((_, i) => <Skeleton key={i} className="h-20" />)}</div>}>
          {(data) => (
            <div className="flex flex-col gap-3">
              {data.systemStatus && <p className="text-xs text-muted">System status: {data.systemStatus}</p>}
              <KpiStrip summary={data} />
            </div>
          )}
        </DataState>
      )}

      <div className={clear ? 'grid grid-cols-1 gap-6 lg:grid-cols-2 xl:gap-7' : 'grid grid-cols-1 gap-4 xl:grid-cols-3'}>
        {show('equity') && (
          <Panel className={clear ? 'lg:col-span-2 experience-panel' : 'xl:col-span-2'} title="Realized equity" subtitle={`Last ${days} days · click to open trades`} >
            <DataState query={equity} skeleton={<Skeleton className="h-64 w-full" />}>
              {() => <EquityChart points={curve} />}
            </DataState>
          </Panel>
        )}
        {show('allocation') && (
          <Panel title="Allocation" subtitle="Positions and cash">
            <DataState query={portfolio}>
              {(p) => <AllocationChart positions={p.positions} cash={p.cash} />}
            </DataState>
          </Panel>
        )}
        {show('pnl') && (
          <Panel className="xl:col-span-2" title="Daily and cumulative P&L" subtitle={`Last ${days} days`}>
            <DataState query={equity} skeleton={<Skeleton className="h-64 w-full" />}>
              {() => <PnlChart points={curve} />}
            </DataState>
          </Panel>
        )}
        {show('drawdown') && (
          <Panel title="Drawdown" subtitle="From peak realized equity">
            <DataState query={equity} skeleton={<Skeleton className="h-64 w-full" />}>
              {() => <DrawdownChart points={curve} />}
            </DataState>
          </Panel>
        )}
        {show('signals') && (
          <div className="xl:col-span-2">
            <SignalsPanel query={signals} />
          </div>
        )}
        {show('risk') && (
          <Panel title="Risk alerts" subtitle="Derived from live summary and policy">
            <DataState query={summary}>
              {(s) => <RiskAlerts summary={s} portfolio={portfolio.data} config={riskConfig.data} />}
            </DataState>
          </Panel>
        )}
        {show('positions') && (
          <div className="xl:col-span-2">
            <PositionsPanel query={portfolio} />
          </div>
        )}
        {show('trades') && (
          <div className={clear ? 'experience-panel' : 'xl:col-span-1'}>
            <TradesPanel query={orders} />
          </div>
        )}
        {show('agents') && (
          <div className="xl:col-span-2">
            <DataState query={summary}>
              {(s) => (
                <AgentsPanel
                  agents={s.agents}
                  unavailable="Execution latency and success rates need agent telemetry, which is not implemented yet."
                />
              )}
            </DataState>
          </div>
        )}
        {show('providers') && (
          <DataState query={summary}>
            {(s) => <ProvidersPanel providers={s.providers} />}
          </DataState>
        )}
        {show('providers') && <AngelOneStatusPanel controller={broker} />}
        {show('news') && <NewsPanel controller={news} />}
        {show('provenance-news') && <div className={clear ? 'lg:col-span-2 experience-panel' : 'xl:col-span-3'}><ProvenanceNewsPanel controller={rss} /></div>}
      </div>
    </div>
  );
}

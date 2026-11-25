import { Plus } from 'lucide-react';
import { DataState } from '../../DataState.jsx';
import { Button, Notice, Panel, SegmentedControl } from '../../ui.jsx';
import { CashLedgerTable, FillBreakdown, PositionTable } from '../../PaperExecutionComponents.jsx';
import { OrdersSection, ReconciliationSection, TABS } from './OrderEvidence.jsx';

export default function OrdersWorkspace({ model, actions, clear = false }) {
  const { tab, orders, status, symbol, fills, positions, cash, portfolio, reconciliation, runReconciliation } = model;
  const { setNewOpen, setTab, setSelectedId, setPageIndex, updateParam, clearFilters, exportPage } = actions;
  return (
    <div className={clear ? "flex flex-col gap-6" : "flex flex-col gap-5"}>
      <div className="flex flex-wrap items-end justify-between gap-3"><div><h1 className="text-lg font-semibold text-text">Paper execution</h1><p className="mt-0.5 text-xs text-muted">Orders, fills, positions, cash and reconciliation. PAPER only; no broker orders are placed.</p></div><Button variant="primary" icon={Plus} onClick={() => setNewOpen(true)}>New paper order</Button></div>
      <SegmentedControl label="Paper execution section" options={TABS} value={tab} onChange={setTab} />
      <Panel>
        {tab === 'orders' && <DataState query={orders} skeleton={<div className="h-64 w-full animate-pulse rounded bg-surface-2" />}>{(page) => <OrdersSection page={page} status={status} symbol={symbol} onOpen={setSelectedId} onPage={setPageIndex} updateParam={updateParam} clearFilters={clearFilters} onExportPage={exportPage} />}</DataState>}
        {tab === 'fills' && (fills.isError ? <Notice tone="loss" title="Could not load fills">{fills.error?.message}</Notice> : fills.data ? <FillBreakdown rows={fills.data} /> : <div className="h-40 animate-pulse rounded bg-surface-2" />)}
        {tab === 'positions' && (positions.isError ? <Notice tone="loss" title="Could not load positions">{positions.error?.message}</Notice> : positions.data ? <PositionTable rows={positions.data} /> : <div className="h-40 animate-pulse rounded bg-surface-2" />)}
        {tab === 'cash' && (cash.isError ? <Notice tone="loss" title="Could not load cash ledger">{cash.error?.message}</Notice> : cash.data ? <CashLedgerTable rows={cash.data} /> : <div className="h-40 animate-pulse rounded bg-surface-2" />)}
        {tab === 'reconciliation' && (portfolio.data && reconciliation.data ? <ReconciliationSection portfolio={portfolio.data} runs={reconciliation.data} mutation={runReconciliation} /> : <div className="h-40 animate-pulse rounded bg-surface-2" />)}
      </Panel>
    </div>
  );
}

import { useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { Download, Plus } from 'lucide-react';
import { useOrderDetail, useOrders } from '../hooks/useApi.js';
import {
  useCanonicalPortfolio,
  usePaperCashLedger,
  usePaperFills,
  usePaperPositions,
  usePaperReconciliationRuns,
  useRunPaperReconciliation,
} from '../hooks/usePaperApi.js';
import { DataState } from '../components/DataState.jsx';
import { Badge, Button, DataTable, EmptyState, Modal, Notice, Panel, SegmentedControl } from '../components/ui.jsx';
import {
  CashLedgerTable,
  FillBreakdown,
  OrderStatusTimeline,
  PositionTable,
  ReconciliationBanner,
} from '../components/PaperExecutionComponents.jsx';
import SimulateTradeDialog from '../components/SimulateTradeDialog.jsx';
import { downloadCsv } from '../lib/csv.js';
import { formatDateTime, formatInr } from '../lib/format.js';

const STATUS_OPTIONS = [
  { value: '', label: 'All' },
  { value: 'FILLED', label: 'Filled' },
  { value: 'PARTIALLY_FILLED', label: 'Partial' },
  { value: 'READY', label: 'Ready' },
  { value: 'REJECTED', label: 'Rejected' },
  { value: 'CANCELLED', label: 'Cancelled' },
];
const TABS = [
  { value: 'orders', label: 'Orders' },
  { value: 'fills', label: 'Fills' },
  { value: 'positions', label: 'Positions' },
  { value: 'cash', label: 'Cash ledger' },
  { value: 'reconciliation', label: 'Reconciliation' },
];
const statusTone = {
  FILLED: 'gain', PARTIALLY_FILLED: 'warn', READY: 'accent', RISK_APPROVED: 'accent',
  REJECTED: 'loss', FAILED: 'loss', CANCELLED: 'neutral', EXPIRED: 'warn', NEW: 'neutral',
};
const PAGE_SIZE = 10;

function Field({ label, value }) {
  return <div className="flex flex-col gap-0.5"><span className="text-[11px] uppercase tracking-wider text-muted">{label}</span><span className="num text-sm text-text">{value ?? '—'}</span></div>;
}

function OrderDetailDialog({ orderId, onClose }) {
  const detail = useOrderDetail(orderId);
  const data = detail.data;
  return (
    <Modal open={!!orderId} onOpenChange={(open) => !open && onClose()} title={data ? `Order ${data.order.symbol} ${data.order.side}` : 'Order detail'} description={orderId ? `Paper order ${orderId}` : undefined} size="lg">
      {detail.isPending && <div className="h-32 animate-pulse rounded bg-surface-2" />}
      {detail.isError && <Notice tone="loss" title="Could not load order" action={<Button size="sm" onClick={() => detail.refetch()}>Retry</Button>}>{detail.error?.message}</Notice>}
      {data && <OrderBody result={data} />}
    </Modal>
  );
}

function OrderBody({ result }) {
  const { order, decision, fill, replayed } = result;
  return (
    <div className="flex flex-col gap-5">
      <OrderStatusTimeline order={order} />
      <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
        <Field label="Status" value={<Badge tone={statusTone[order.status]}>{order.status}</Badge>} />
        <Field label="Product" value={order.productType || 'DELIVERY'} />
        <Field label="Order type" value={order.orderType || 'MARKET'} />
        <Field label="Quantity" value={`${order.filledQuantity ?? 0} / ${order.submittedQuantity ?? order.quantity}`} />
        <Field label="Entry" value={formatInr(order.entryPrice)} />
        <Field label="Average fill" value={formatInr(order.averageFillPrice)} />
        <Field label="Stop / Target" value={`${formatInr(order.stopLoss)} / ${formatInr(order.target)}`} />
        <Field label="Exchange" value={order.exchange || '—'} />
        <Field label="Signal time" value={formatDateTime(order.signalGeneratedAt)} />
        <Field label="Created" value={formatDateTime(order.createdAt)} />
        <Field label="Correlation ID" value={<span className="break-all text-xs">{order.correlationId}</span>} />
        <Field label="Replayed" value={replayed ? 'Yes (idempotent)' : 'No'} />
      </div>
      <section className="flex flex-col gap-2">
        <h3 className="text-xs font-semibold uppercase tracking-wider text-muted">Risk decision</h3>
        {decision ? <div className="rounded-md border border-border bg-surface-2 p-3"><div className="flex flex-wrap items-center gap-2"><Badge tone={decision.decision === 'APPROVED' ? 'gain' : 'loss'}>{decision.decision}</Badge><span className="text-xs text-muted">Policy {decision.policyVersion} · {formatDateTime(decision.evaluatedAt)}</span></div>{decision.reasonCodes?.length > 0 && <div className="mt-2 flex flex-wrap gap-1.5">{decision.reasonCodes.map((code) => <Badge key={code} tone="warn">{code}</Badge>)}</div>}</div> : <EmptyState title="No risk decision recorded" />}
      </section>
      <section className="flex flex-col gap-2">
        <h3 className="text-xs font-semibold uppercase tracking-wider text-muted">Fill evidence</h3>
        {fill ? <div className="grid grid-cols-2 gap-3 rounded-md border border-border bg-surface-2 p-3 sm:grid-cols-4"><Field label="Fill price" value={formatInr(fill.fillPrice)} /><Field label="Gross" value={formatInr(fill.grossAmount)} /><Field label="Fees" value={formatInr(fill.feeAmount)} /><Field label="Realized P&L" value={formatInr(fill.realizedPnl)} /></div> : <EmptyState title="Not filled" />}
      </section>
    </div>
  );
}

function OrdersSection({ page, onOpen, onPage, status, symbol, updateParam, clearFilters }) {
  const columns = [
    { key: 'createdAt', header: 'Created', cell: (r) => formatDateTime(r.createdAt) },
    { key: 'symbol', header: 'Symbol', cell: (r) => <span className="font-medium">{r.symbol}</span> },
    { key: 'side', header: 'Side', cell: (r) => <Badge tone={r.side === 'BUY' ? 'gain' : 'loss'}>{r.side}</Badge> },
    { key: 'productType', header: 'Product', cell: (r) => r.productType || 'DELIVERY' },
    { key: 'quantity', header: 'Qty', align: 'right', cell: (r) => `${r.filledQuantity ?? 0}/${r.submittedQuantity ?? r.quantity}` },
    { key: 'entryPrice', header: 'Entry', align: 'right', cell: (r) => formatInr(r.entryPrice) },
    { key: 'status', header: 'Status', cell: (r) => <Badge tone={statusTone[r.status]}>{r.status}</Badge> },
    { key: 'rejectionReason', header: 'Note', cell: (r) => <span className="text-muted">{r.rejectionReason || '—'}</span> },
  ];
  const exportCols = [
    { label: 'Created at', value: (r) => r.createdAt }, { label: 'Symbol', value: (r) => r.symbol },
    { label: 'Side', value: (r) => r.side }, { label: 'Product', value: (r) => r.productType },
    { label: 'Submitted quantity', value: (r) => r.submittedQuantity ?? r.quantity }, { label: 'Filled quantity', value: (r) => r.filledQuantity ?? 0 },
    { label: 'Entry', value: (r) => r.entryPrice }, { label: 'Status', value: (r) => r.status }, { label: 'Order ID', value: (r) => r.id },
  ];
  const totalPages = Math.max(1, page.totalPages || 1);
  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center gap-3">
        <SegmentedControl label="Filter by status" options={STATUS_OPTIONS} value={status} onChange={(v) => updateParam('status', v)} />
        <input aria-label="Filter by symbol" placeholder="Symbol" value={symbol} onChange={(e) => updateParam('symbol', e.target.value.toUpperCase().trim())} className="h-8 w-36 rounded-md border border-border bg-surface-2 px-2.5 text-xs text-text placeholder:text-muted focus:border-accent focus:outline-none" />
        {(symbol || status) && <Button size="sm" variant="ghost" onClick={clearFilters}>Clear filters</Button>}
        <Button size="sm" variant="ghost" icon={Download} disabled={page.items.length === 0} onClick={() => downloadCsv(`paper-orders-page-${page.page + 1}.csv`, exportCols, page.items)}>Export page CSV</Button>
      </div>
      <DataTable columns={columns} rows={page.items} rowKey={(r) => r.id} pageSize={page.items.length || PAGE_SIZE} minWidth={900} onRowClick={(r) => onOpen(r.id)} empty={<EmptyState title="No paper orders" />} />
      {totalPages > 1 && <div className="flex items-center justify-between text-xs text-muted"><span>Page {page.page + 1} of {totalPages}</span><div className="flex gap-1"><Button size="sm" variant="ghost" disabled={page.page === 0} onClick={() => onPage(page.page - 1)}>Previous</Button><Button size="sm" variant="ghost" disabled={page.page + 1 >= totalPages} onClick={() => onPage(page.page + 1)}>Next</Button></div></div>}
    </div>
  );
}

function ReconciliationSection({ portfolio, runs, mutation }) {
  const columns = [
    { key: 'startedAt', header: 'Started', cell: (r) => formatDateTime(r.startedAt) },
    { key: 'status', header: 'Status', cell: (r) => <Badge tone={r.status === 'CLEAN' ? 'gain' : 'warn'}>{r.status}</Badge> },
    { key: 'differenceCount', header: 'Differences', align: 'right' },
    { key: 'completedAt', header: 'Completed', cell: (r) => formatDateTime(r.completedAt) },
  ];
  return <div className="flex flex-col gap-4"><ReconciliationBanner portfolio={portfolio} runs={runs} mutation={mutation} /><DataTable columns={columns} rows={runs || []} rowKey={(r) => r.id} minWidth={620} empty={<EmptyState title="No reconciliation runs" />} /></div>;
}

export default function PaperTradesPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const symbol = (searchParams.get('symbol') || '').toUpperCase();
  const status = searchParams.get('status') || '';
  const [tab, setTab] = useState('orders');
  const [pageIndex, setPageIndex] = useState(0);
  const [selectedId, setSelectedId] = useState(null);
  const [newOpen, setNewOpen] = useState(false);
  const orders = useOrders({ page: pageIndex, size: PAGE_SIZE, status, symbol });
  const fills = usePaperFills();
  const positions = usePaperPositions();
  const cash = usePaperCashLedger();
  const portfolio = useCanonicalPortfolio();
  const reconciliation = usePaperReconciliationRuns();
  const runReconciliation = useRunPaperReconciliation();

  const updateParam = (key, value) => { const next = new URLSearchParams(searchParams); if (value) next.set(key, value); else next.delete(key); setSearchParams(next, { replace: true }); setPageIndex(0); };

  return (
    <div className="flex flex-col gap-5">
      <div className="flex flex-wrap items-end justify-between gap-3"><div><h1 className="text-lg font-semibold text-text">Paper execution</h1><p className="mt-0.5 text-xs text-muted">Orders, fills, positions, cash and reconciliation. PAPER only; no broker orders are placed.</p></div><Button variant="primary" icon={Plus} onClick={() => setNewOpen(true)}>New paper order</Button></div>
      <SegmentedControl label="Paper execution section" options={TABS} value={tab} onChange={setTab} />
      <Panel>
        {tab === 'orders' && <DataState query={orders} skeleton={<div className="h-64 w-full animate-pulse rounded bg-surface-2" />}>{(page) => <OrdersSection page={page} status={status} symbol={symbol} onOpen={setSelectedId} onPage={setPageIndex} updateParam={updateParam} clearFilters={() => setSearchParams({}, { replace: true })} />}</DataState>}
        {tab === 'fills' && (fills.isError ? <Notice tone="loss" title="Could not load fills">{fills.error?.message}</Notice> : fills.data ? <FillBreakdown rows={fills.data} /> : <div className="h-40 animate-pulse rounded bg-surface-2" />)}
        {tab === 'positions' && (positions.isError ? <Notice tone="loss" title="Could not load positions">{positions.error?.message}</Notice> : positions.data ? <PositionTable rows={positions.data} /> : <div className="h-40 animate-pulse rounded bg-surface-2" />)}
        {tab === 'cash' && (cash.isError ? <Notice tone="loss" title="Could not load cash ledger">{cash.error?.message}</Notice> : cash.data ? <CashLedgerTable rows={cash.data} /> : <div className="h-40 animate-pulse rounded bg-surface-2" />)}
        {tab === 'reconciliation' && (portfolio.data && reconciliation.data ? <ReconciliationSection portfolio={portfolio.data} runs={reconciliation.data} mutation={runReconciliation} /> : <div className="h-40 animate-pulse rounded bg-surface-2" />)}
      </Panel>
      <OrderDetailDialog orderId={selectedId} onClose={() => setSelectedId(null)} />
      <SimulateTradeDialog open={newOpen} onOpenChange={setNewOpen} prefill={null} source={null} />
    </div>
  );
}

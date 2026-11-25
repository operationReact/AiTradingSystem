import { Download } from 'lucide-react';
import { Badge, Button, DataTable, EmptyState, Modal, Notice, SegmentedControl } from '../../ui.jsx';
import { OrderStatusTimeline, ReconciliationBanner } from '../../PaperExecutionComponents.jsx';
import { formatDateTime, formatInr } from '../../../lib/format.js';

const STATUS_OPTIONS = [
  { value: '', label: 'All' },
  { value: 'FILLED', label: 'Filled' },
  { value: 'PARTIALLY_FILLED', label: 'Partial' },
  { value: 'READY', label: 'Ready' },
  { value: 'REJECTED', label: 'Rejected' },
  { value: 'CANCELLED', label: 'Cancelled' },
];
export const TABS = [
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

export function OrderDetailDialog({ orderId, detail, onClose, onRetry }) {
  const data = detail.data;
  return (
    <Modal open={!!orderId} onOpenChange={(open) => !open && onClose()} title={data ? `Order ${data.order.symbol} ${data.order.side}` : 'Order detail'} description={orderId ? `Paper order ${orderId}` : undefined} size="lg">
      {detail.isPending && <div className="h-32 animate-pulse rounded bg-surface-2" />}
      {detail.isError && <Notice tone="loss" title="Could not load order" action={<Button size="sm" onClick={() => onRetry()}>Retry</Button>}>{detail.error?.message}</Notice>}
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

export function OrdersSection({ page, onOpen, onPage, status, symbol, updateParam, clearFilters, onExportPage }) {
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
  const totalPages = Math.max(1, page.totalPages || 1);
  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center gap-3">
        <SegmentedControl label="Filter by status" options={STATUS_OPTIONS} value={status} onChange={(v) => updateParam('status', v)} />
        <input aria-label="Filter by symbol" placeholder="Symbol" value={symbol} onChange={(e) => updateParam('symbol', e.target.value.toUpperCase().trim())} className="h-8 w-36 rounded-md border border-border bg-surface-2 px-2.5 text-xs text-text placeholder:text-muted focus:border-accent focus:outline-none" />
        {(symbol || status) && <Button size="sm" variant="ghost" onClick={clearFilters}>Clear filters</Button>}
        <Button size="sm" variant="ghost" icon={Download} disabled={page.items.length === 0} onClick={onExportPage}>Export page CSV</Button>
      </div>
      <DataTable columns={columns} rows={page.items} rowKey={(r) => r.id} pageSize={page.items.length || PAGE_SIZE} minWidth={900} onRowClick={(r) => onOpen(r.id)} empty={<EmptyState title="No paper orders" />} />
      {totalPages > 1 && <div className="flex items-center justify-between text-xs text-muted"><span>Page {page.page + 1} of {totalPages}</span><div className="flex gap-1"><Button size="sm" variant="ghost" disabled={page.page === 0} onClick={() => onPage(page.page - 1)}>Previous</Button><Button size="sm" variant="ghost" disabled={page.page + 1 >= totalPages} onClick={() => onPage(page.page + 1)}>Next</Button></div></div>}
    </div>
  );
}

export function ReconciliationSection({ portfolio, runs, mutation }) {
  const columns = [
    { key: 'startedAt', header: 'Started', cell: (r) => formatDateTime(r.startedAt) },
    { key: 'status', header: 'Status', cell: (r) => <Badge tone={r.status === 'CLEAN' ? 'gain' : 'warn'}>{r.status}</Badge> },
    { key: 'differenceCount', header: 'Differences', align: 'right' },
    { key: 'completedAt', header: 'Completed', cell: (r) => formatDateTime(r.completedAt) },
  ];
  return <div className="flex flex-col gap-4"><ReconciliationBanner portfolio={portfolio} runs={runs} mutation={mutation} /><DataTable columns={columns} rows={runs || []} rowKey={(r) => r.id} minWidth={620} empty={<EmptyState title="No reconciliation runs" />} /></div>;
}


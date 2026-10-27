import { useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { Download, Plus } from 'lucide-react';
import { useOrderDetail, useOrders } from '../hooks/useApi.js';
import { DataState } from '../components/DataState.jsx';
import { Badge, Button, DataTable, EmptyState, Modal, Notice, Panel, SegmentedControl } from '../components/ui.jsx';
import SimulateTradeDialog from '../components/SimulateTradeDialog.jsx';
import { downloadCsv } from '../lib/csv.js';
import { formatDateTime, formatInr } from '../lib/format.js';

const STATUS_OPTIONS = [
  { value: '', label: 'All' },
  { value: 'FILLED', label: 'Filled' },
  { value: 'RISK_APPROVED', label: 'Approved' },
  { value: 'REJECTED', label: 'Rejected' },
  { value: 'NEW', label: 'New' },
];

const statusTone = { FILLED: 'gain', REJECTED: 'loss', RISK_APPROVED: 'accent', NEW: 'neutral' };

const PAGE_SIZE = 10;

function OrderDetailDialog({ orderId, onClose }) {
  const detail = useOrderDetail(orderId);
  const data = detail.data;

  return (
    <Modal
      open={!!orderId}
      onOpenChange={(open) => !open && onClose()}
      title={data ? `Order ${data.order.symbol} ${data.order.side}` : 'Order detail'}
      description={orderId ? `Paper order ${orderId}` : undefined}
      size="lg"
    >
      {detail.isPending && <div className="h-32 animate-pulse rounded bg-surface-2" />}
      {detail.isError && (
        <Notice tone="loss" title="Could not load order" action={<Button size="sm" onClick={() => detail.refetch()}>Retry</Button>}>
          {detail.error?.message}
        </Notice>
      )}
      {data && <OrderBody result={data} />}
    </Modal>
  );
}

function Field({ label, value }) {
  return (
    <div className="flex flex-col gap-0.5">
      <span className="text-[11px] uppercase tracking-wider text-muted">{label}</span>
      <span className="num text-sm text-text">{value ?? '—'}</span>
    </div>
  );
}

function OrderBody({ result }) {
  const { order, decision, fill, replayed } = result;
  return (
    <div className="flex flex-col gap-5">
      <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
        <Field label="Status" value={<Badge tone={statusTone[order.status]}>{order.status}</Badge>} />
        <Field label="Quantity" value={order.quantity} />
        <Field label="Entry" value={formatInr(order.entryPrice)} />
        <Field label="Stop / Target" value={`${formatInr(order.stopLoss)} / ${formatInr(order.target)}`} />
        <Field label="Signal time" value={formatDateTime(order.signalGeneratedAt)} />
        <Field label="Created" value={formatDateTime(order.createdAt)} />
        <Field label="Correlation ID" value={<span className="break-all text-xs">{order.correlationId}</span>} />
        <Field label="Replayed" value={replayed ? 'Yes (idempotent)' : 'No'} />
      </div>

      <section className="flex flex-col gap-2">
        <h3 className="text-xs font-semibold uppercase tracking-wider text-muted">Risk decision</h3>
        {decision ? (
          <div className="flex flex-col gap-2 rounded-md border border-border bg-surface-2 p-3">
            <div className="flex flex-wrap items-center gap-2">
              <Badge tone={decision.decision === 'APPROVED' ? 'gain' : 'loss'}>{decision.decision}</Badge>
              <span className="text-xs text-muted">Policy {decision.policyVersion} · {formatDateTime(decision.evaluatedAt)}</span>
            </div>
            <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
              <Field label="Risk budget" value={formatInr(decision.riskBudget)} />
              <Field label="Estimated risk" value={formatInr(decision.estimatedRisk)} />
              <Field label="Available cash" value={formatInr(decision.availableCash)} />
              <Field label="Daily loss limit" value={formatInr(decision.dailyLossLimit)} />
            </div>
            {decision.reasonCodes?.length > 0 && (
              <ul className="flex flex-wrap gap-1.5">
                {decision.reasonCodes.map((code) => (
                  <li key={code}><Badge tone="warn">{code}</Badge></li>
                ))}
              </ul>
            )}
          </div>
        ) : (
          <EmptyState title="No risk decision recorded" />
        )}
      </section>

      <section className="flex flex-col gap-2">
        <h3 className="text-xs font-semibold uppercase tracking-wider text-muted">Paper fill</h3>
        {fill ? (
          <div className="grid grid-cols-2 gap-3 rounded-md border border-border bg-surface-2 p-3 sm:grid-cols-4">
            <Field label="Fill price" value={formatInr(fill.fillPrice)} />
            <Field label="Gross" value={formatInr(fill.grossAmount)} />
            <Field label="Fees" value={formatInr(fill.feeAmount)} />
            <Field label="Slippage" value={formatInr(fill.slippageAmount)} />
            <Field label="Net amount" value={formatInr(fill.netAmount)} />
            <Field label="Cost basis" value={formatInr(fill.costBasis)} />
            <Field label="Realized P&L" value={formatInr(fill.realizedPnl)} />
            <Field label="Filled at" value={formatDateTime(fill.filledAt)} />
          </div>
        ) : (
          <EmptyState title="Not filled" description={order.rejectionReason ? `Reason: ${order.rejectionReason}` : undefined} />
        )}
      </section>
    </div>
  );
}

function TradesTable({ page, onOpen, onPage, status, symbol }) {
  const columns = [
    { key: 'createdAt', header: 'Created', cell: (r) => formatDateTime(r.createdAt) },
    { key: 'symbol', header: 'Symbol', cell: (r) => <span className="font-medium">{r.symbol}</span> },
    { key: 'side', header: 'Side', cell: (r) => <Badge tone={r.side === 'BUY' ? 'gain' : 'loss'}>{r.side}</Badge> },
    { key: 'quantity', header: 'Qty', align: 'right' },
    { key: 'entryPrice', header: 'Entry', align: 'right', cell: (r) => formatInr(r.entryPrice) },
    { key: 'stopLoss', header: 'Stop', align: 'right', cell: (r) => formatInr(r.stopLoss) },
    { key: 'target', header: 'Target', align: 'right', cell: (r) => formatInr(r.target) },
    { key: 'status', header: 'Status', cell: (r) => <Badge tone={statusTone[r.status]}>{r.status}</Badge> },
    { key: 'rejectionReason', header: 'Note', cell: (r) => <span className="text-muted">{r.rejectionReason || '—'}</span> },
  ];

  const exportCols = [
    { label: 'Created at', value: (r) => r.createdAt },
    { label: 'Symbol', value: (r) => r.symbol },
    { label: 'Side', value: (r) => r.side },
    { label: 'Quantity', value: (r) => r.quantity },
    { label: 'Entry', value: (r) => r.entryPrice },
    { label: 'Stop loss', value: (r) => r.stopLoss },
    { label: 'Target', value: (r) => r.target },
    { label: 'Status', value: (r) => r.status },
    { label: 'Rejection reason', value: (r) => r.rejectionReason },
    { label: 'Order ID', value: (r) => r.id },
  ];

  const totalPages = Math.max(1, page.totalPages || 1);
  const from = page.totalItems === 0 ? 0 : page.page * page.size + 1;
  const to = Math.min(page.totalItems, (page.page + 1) * page.size);

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <span className="text-xs text-muted">
          {page.totalItems === 0 ? 'No orders' : `Showing ${from}–${to} of ${page.totalItems}`}
          {(status || symbol) && ' (filtered)'}
        </span>
        <Button size="sm" variant="ghost" icon={Download} disabled={page.items.length === 0} onClick={() => downloadCsv(`paper-orders-page-${page.page + 1}.csv`, exportCols, page.items)}>
          Export page CSV
        </Button>
      </div>
      <DataTable
        columns={columns}
        rows={page.items}
        rowKey={(r) => r.id}
        pageSize={page.items.length || PAGE_SIZE}
        minWidth={900}
        onRowClick={(r) => onOpen(r.id)}
        empty={<EmptyState title="No paper orders" description="Submit an order from a signal, or use New paper order." />}
      />
      {totalPages > 1 && (
        <div className="flex items-center justify-between text-xs text-muted">
          <span>Page {page.page + 1} of {totalPages}</span>
          <div className="flex gap-1">
            <Button size="sm" variant="ghost" disabled={page.page === 0} onClick={() => onPage(page.page - 1)}>Previous</Button>
            <Button size="sm" variant="ghost" disabled={page.page + 1 >= totalPages} onClick={() => onPage(page.page + 1)}>Next</Button>
          </div>
        </div>
      )}
    </div>
  );
}

export default function PaperTradesPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const symbol = (searchParams.get('symbol') || '').toUpperCase();
  const status = searchParams.get('status') || '';
  const [pageIndex, setPageIndex] = useState(0);
  const [selectedId, setSelectedId] = useState(null);
  const [newOpen, setNewOpen] = useState(false);

  const orders = useOrders({ page: pageIndex, size: PAGE_SIZE, status, symbol });

  const updateParam = (key, value) => {
    const next = new URLSearchParams(searchParams);
    if (value) next.set(key, value);
    else next.delete(key);
    setSearchParams(next, { replace: true });
    setPageIndex(0);
  };

  return (
    <div className="flex flex-col gap-5">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-lg font-semibold text-text">Paper trades</h1>
          <p className="mt-0.5 text-xs text-muted">Persisted paper orders, risk decisions and fills. Paper mode only; no broker orders are placed.</p>
        </div>
        <Button variant="primary" icon={Plus} onClick={() => setNewOpen(true)}>New paper order</Button>
      </div>

      <Panel>
        <div className="mb-4 flex flex-wrap items-center gap-3">
          <SegmentedControl label="Filter by status" options={STATUS_OPTIONS} value={status} onChange={(v) => updateParam('status', v)} />
          <input
            aria-label="Filter by symbol"
            placeholder="Symbol"
            value={symbol}
            onChange={(e) => updateParam('symbol', e.target.value.toUpperCase().trim())}
            className="h-8 w-36 rounded-md border border-border bg-surface-2 px-2.5 text-xs text-text placeholder:text-muted focus:border-accent focus:outline-none"
          />
          {(symbol || status) && (
            <Button size="sm" variant="ghost" onClick={() => setSearchParams({}, { replace: true })}>Clear filters</Button>
          )}
        </div>
        <DataState query={orders} skeleton={<div className="h-64 w-full animate-pulse rounded bg-surface-2" />}>
          {(page) => (
            <TradesTable
              page={page}
              status={status}
              symbol={symbol}
              onOpen={setSelectedId}
              onPage={setPageIndex}
            />
          )}
        </DataState>
      </Panel>

      <OrderDetailDialog orderId={selectedId} onClose={() => setSelectedId(null)} />
      <SimulateTradeDialog open={newOpen} onOpenChange={setNewOpen} prefill={null} source={null} />
    </div>
  );
}

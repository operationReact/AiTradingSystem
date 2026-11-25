import { useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { useApp } from '../context/AppContext.jsx';
import { useOrderDetail, useOrders } from '../hooks/useApi.js';
import { useCanonicalPortfolio, usePaperCashLedger, usePaperFills, usePaperPositions, usePaperReconciliationRuns, useRunPaperReconciliation } from '../hooks/usePaperApi.js';
import { requestState } from '../lib/viewModel.js';
import { downloadCsv } from '../lib/csv.js';
import { ORDER_EXPORT_COLUMNS } from '../lib/paperOrderCsv.js';
import ExperienceBoundary from '../components/experience/ExperienceBoundary.jsx';
import ProOrdersView from '../components/orders/pro/ProOrdersView.jsx';
import ClearOrdersView from '../components/orders/clear/ClearOrdersView.jsx';
import { OrderDetailDialog } from '../components/orders/shared/OrderEvidence.jsx';
import SimulateTradeDialog from '../components/SimulateTradeDialog.jsx';
const PAGE_SIZE = 10;

export default function PaperTradesPage() {
  const { apiKey } = useApp();
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

  const detail = useOrderDetail(selectedId);
  const project = query => ({ ...requestState(query), refetch: () => query.refetch() });
  const reconcile = () => { if (apiKey && !runReconciliation.isPending) runReconciliation.mutate(); };
  const model = { tab, orders: project(orders), status, symbol, fills: project(fills), positions: project(positions), cash: project(cash),
    portfolio: project(portfolio), reconciliation: project(reconciliation),
    runReconciliation: apiKey ? { ...requestState(runReconciliation), mutate: reconcile } : null };
  const actions = { setNewOpen, setTab, setSelectedId, setPageIndex, updateParam,
    clearFilters: () => { setSearchParams({}, { replace: true }); setPageIndex(0); },
    exportPage: () => { if (orders.data) downloadCsv(`paper-orders-page-${orders.data.page + 1}.csv`, ORDER_EXPORT_COLUMNS, orders.data.items); } };
  return <>
    <ExperienceBoundary pro={<ProOrdersView model={model} actions={actions} />} clear={<ClearOrdersView model={model} actions={actions} />} />
    <OrderDetailDialog orderId={selectedId} detail={requestState(detail)} onClose={() => setSelectedId(null)} onRetry={() => detail.refetch()} />
    <SimulateTradeDialog open={newOpen} onOpenChange={setNewOpen} prefill={null} source={null} />
  </>;
}

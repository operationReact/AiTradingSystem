import { useMemo, useState } from 'react';
import { Download, Send } from 'lucide-react';
import { useSignals } from '../hooks/useApi.js';
import { DataState } from '../components/DataState.jsx';
import { Badge, Button, DataTable, EmptyState, Notice, Panel } from '../components/ui.jsx';
import SimulateTradeDialog from '../components/SimulateTradeDialog.jsx';
import { downloadCsv } from '../lib/csv.js';
import { formatDateTime, formatInr } from '../lib/format.js';

const selectClass = 'h-8 rounded-md border border-border bg-surface-2 px-2 text-xs text-text focus:border-accent focus:outline-none';

function SignalsTable({ signals, onSimulate }) {
  const [query, setQuery] = useState('');
  const [action, setAction] = useState('ALL');
  const [status, setStatus] = useState('ALL');

  const statuses = useMemo(() => ['ALL', ...new Set(signals.map((s) => s.status).filter(Boolean))], [signals]);
  const actions = useMemo(() => ['ALL', ...new Set(signals.map((s) => s.action).filter(Boolean))], [signals]);

  const filtered = useMemo(() => {
    const q = query.trim().toUpperCase();
    return signals.filter(
      (s) =>
        (!q || String(s.symbol).toUpperCase().includes(q)) &&
        (action === 'ALL' || s.action === action) &&
        (status === 'ALL' || s.status === status),
    );
  }, [signals, query, action, status]);

  const columns = [
    { key: 'symbol', header: 'Symbol', cell: (r) => <span className="font-medium">{r.symbol}</span> },
    { key: 'action', header: 'Action', cell: (r) => <Badge tone={String(r.action).includes('SELL') ? 'loss' : 'gain'}>{r.action}</Badge> },
    { key: 'strategy', header: 'Strategy', sortable: true, cell: (r) => <span className="text-muted">{r.strategy}</span> },
    { key: 'confidence', header: 'Confidence', align: 'right', cell: (r) => (Number.isFinite(Number(r.confidence)) ? Number(r.confidence).toFixed(2) : '—') },
    { key: 'referencePrice', header: 'Ref. price', align: 'right', cell: (r) => formatInr(r.referencePrice) },
    { key: 'stopLoss', header: 'Stop', align: 'right', cell: (r) => formatInr(r.stopLoss) },
    { key: 'target', header: 'Target', align: 'right', cell: (r) => formatInr(r.target) },
    { key: 'status', header: 'Status', cell: (r) => <Badge>{r.status}</Badge> },
    { key: 'createdAt', header: 'Generated', cell: (r) => formatDateTime(r.createdAt) },
    {
      key: 'actions',
      header: 'Paper',
      sortable: false,
      cell: (r) => (
        <Button size="sm" variant="ghost" icon={Send} aria-label={`Simulate paper order for ${r.symbol}`} onClick={(e) => { e.stopPropagation(); onSimulate(r); }}>
          Simulate
        </Button>
      ),
    },
  ];

  const exportCols = [
    { label: 'Symbol', value: (r) => r.symbol },
    { label: 'Action', value: (r) => r.action },
    { label: 'Strategy', value: (r) => r.strategy },
    { label: 'Confidence', value: (r) => r.confidence },
    { label: 'Reference price', value: (r) => r.referencePrice },
    { label: 'Stop loss', value: (r) => r.stopLoss },
    { label: 'Target', value: (r) => r.target },
    { label: 'Status', value: (r) => r.status },
    { label: 'Generated at', value: (r) => r.createdAt },
    { label: 'Source', value: (r) => r.source },
  ];

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center gap-2">
        <input
          aria-label="Search symbol"
          placeholder="Search symbol"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          className="h-8 w-44 rounded-md border border-border bg-surface-2 px-2.5 text-xs text-text placeholder:text-muted focus:border-accent focus:outline-none"
        />
        <label className="flex items-center gap-1.5 text-xs text-muted">
          Action
          <select className={selectClass} value={action} onChange={(e) => setAction(e.target.value)}>
            {actions.map((a) => <option key={a} value={a}>{a === 'ALL' ? 'All' : a}</option>)}
          </select>
        </label>
        <label className="flex items-center gap-1.5 text-xs text-muted">
          Status
          <select className={selectClass} value={status} onChange={(e) => setStatus(e.target.value)}>
            {statuses.map((s) => <option key={s} value={s}>{s === 'ALL' ? 'All' : s}</option>)}
          </select>
        </label>
        <span className="text-xs text-muted">{filtered.length} of {signals.length}</span>
        <div className="ml-auto">
          <Button size="sm" variant="ghost" icon={Download} disabled={filtered.length === 0} onClick={() => downloadCsv('signals.csv', exportCols, filtered)}>
            Export CSV
          </Button>
        </div>
      </div>
      <DataTable
        columns={columns}
        rows={filtered}
        rowKey={(r) => r.id}
        pageSize={10}
        minWidth={900}
        empty={<EmptyState title={signals.length ? 'No signals match these filters' : 'No signals'} />}
      />
    </div>
  );
}

export default function SignalsPage() {
  const signals = useSignals();
  const [dialogOpen, setDialogOpen] = useState(false);
  const [prefill, setPrefill] = useState(null);

  const openSimulate = (signal) => {
    setPrefill(signal);
    setDialogOpen(true);
  };

  return (
    <div className="flex flex-col gap-5">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-lg font-semibold text-text">Signals</h1>
          <p className="mt-0.5 text-xs text-muted">Candidate signals from the strategy analyst. Records are not executable; paper orders go through the risk policy.</p>
        </div>
        <Badge tone="warn">Synthetic demo data</Badge>
      </div>

      <Notice tone="neutral" title="Demo signals">
        These signals are generated for demonstration. Their prices are not live market quotes. Submitting one as a paper order still goes through the deterministic risk policy.
      </Notice>

      <Panel>
        <DataState query={signals} skeleton={<div className="h-48 w-full animate-pulse rounded bg-surface-2" />}>
          {(data) => <SignalsTable signals={data} onSimulate={openSimulate} />}
        </DataState>
      </Panel>

      <SimulateTradeDialog
        open={dialogOpen}
        onOpenChange={setDialogOpen}
        prefill={prefill}
        source={prefill ? `${prefill.symbol} (${prefill.id})` : null}
      />
    </div>
  );
}

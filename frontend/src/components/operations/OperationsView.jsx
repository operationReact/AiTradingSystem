import { DataState } from '../DataState.jsx';
import { Badge, Button, Panel } from '../ui.jsx';

export default function OperationsView({ query, clear = false }) {
  return <div className="grid gap-4">
    <header><h1 className="text-xl font-semibold text-text">Operations health</h1><p className="mt-1 text-sm text-muted">{clear ? 'Check service evidence before making an operator decision.' : 'Persisted operational evidence and network-free provider status.'}</p></header>
    <DataState query={query}>{health => <>
      <Panel title="Execution controls" actions={<Button onClick={() => query.refetch()} loading={query.isFetching}>Refresh health</Button>}>
        <dl className="grid gap-2 text-sm sm:grid-cols-3"><div><dt className="text-muted">Mode</dt><dd>{health.executionMode}</dd></div><div><dt className="text-muted">Emergency stop</dt><dd>{health.emergencyStopActive == null ? 'Unknown' : health.emergencyStopActive ? 'Active' : 'Inactive'}</dd></div><div><dt className="text-muted">Overall status</dt><dd>{health.state}</dd></div></dl>
        <p className="mt-3 text-xs text-muted">Checked {health.checkedAt}. Diagnostic health does not authorize trading.</p>
      </Panel>
      <div className={clear ? 'grid gap-3' : 'grid gap-3 lg:grid-cols-2'}>{(health.components ?? []).map(item => <Panel key={item.component} title={item.component} actions={<Badge>{item.state}</Badge>}>
        <p className="break-words text-sm text-text">{item.reasonCode}</p><p className="mt-1 text-xs text-muted">Evidence: {item.asOf ?? 'Unavailable'} · Age: {item.ageSeconds == null ? 'Unknown' : `${item.ageSeconds}s`}</p>
      </Panel>)}</div>
    </>}</DataState>
  </div>;
}

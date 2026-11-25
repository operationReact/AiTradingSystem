import { DataState } from '../DataState.jsx';
import { Badge, Button, EmptyState, Notice, Panel } from '../ui.jsx';

export default function NotificationsView({ query, activeOnly, onActiveOnly, page, onPage, pending, error, success, onAcknowledge, clear = false }) {
  return <div className="grid gap-4">
    <header><h1 className="text-xl font-semibold text-text">Notifications</h1><p className="mt-1 text-sm text-muted">{clear ? 'Review incidents and acknowledge that you have seen them.' : 'Durable operator incidents with authenticated acknowledgement.'} Acknowledging does not resolve an incident or resume trading.</p></header>
    <label className="flex min-h-11 items-center gap-2 text-sm"><input type="checkbox" checked={activeOnly} onChange={e => onActiveOnly(e.target.checked)} />Unacknowledged only</label>
    {error && <Notice tone="loss" title="Acknowledgement failed">{error.message}</Notice>}
    {success && <Notice title="Notification acknowledged">Execution controls are unchanged.</Notice>}
    <DataState query={query}>{data => <>
      {data.projection?.backlogRemaining && <Notice title="More incident evidence is waiting" action={<Button onClick={() => query.refetch()}>Load more evidence</Button>}>This page does not yet contain every deferred failure.</Notice>}
      {!data.items?.length && <EmptyState title="No notifications in this view" />}
      {(data.items ?? []).map(item => <Panel key={item.id} title={item.title} actions={<Badge tone={item.severity === 'CRITICAL' ? 'loss' : 'warn'}>{item.severity}</Badge>}>
        <p className="break-words text-sm text-text">{item.message}</p><p className="mt-2 break-all text-xs text-muted">{item.type} · {item.entityType} {item.entityId} · {item.createdAt}</p>
        {item.acknowledgedAt ? <p className="mt-3 text-sm">Acknowledged by {item.acknowledgedBy} at {item.acknowledgedAt}</p> : <Button className="mt-3" loading={pending === item.id} disabled={!!pending} onClick={() => onAcknowledge(item.id)}>Acknowledge</Button>}
      </Panel>)}
      <nav aria-label="Notification pages" className="flex items-center gap-3"><Button disabled={page === 0 || query.isFetching} onClick={() => onPage(page - 1)}>Previous</Button><span className="text-sm">Page {page + 1} of {Math.max(1, data.totalPages ?? 1)}</span><Button disabled={page + 1 >= (data.totalPages ?? 0) || query.isFetching} onClick={() => onPage(page + 1)}>Next</Button></nav>
    </>}</DataState>
  </div>;
}

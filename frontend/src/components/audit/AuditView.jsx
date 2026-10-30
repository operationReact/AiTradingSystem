import { DataState } from '../DataState.jsx';
import { Button, EmptyState, Notice, Panel } from '../ui.jsx';

export default function AuditView({ query, draft, onDraft, onApply, onExport, exporting, exportError, page, onPage, clear = false }) {
  return <div className="grid gap-4">
    <header><h1 className="text-xl font-semibold text-text">Audit history</h1><p className="mt-1 text-sm text-muted">{clear ? 'Review recorded actions and the authenticated actor.' : 'Bounded audit evidence with exact server-side filters.'} Defaults to the last seven days. Historical actors may be unknown.</p></header>
    <form onSubmit={onApply} className="flex flex-wrap items-end gap-3">{[['eventType','Event type'],['entityId','Entity ID'],['actor','Authenticated actor']].map(([key,label]) => <label key={key} className="grid gap-1 text-xs text-muted">{label}<input className="min-h-11 min-w-0 rounded-md border border-border bg-surface px-3 text-sm text-text" value={draft[key]} onChange={e => onDraft({ ...draft, [key]: e.target.value })} /></label>)}<Button type="submit">Apply filters</Button><Button onClick={onExport} loading={exporting}>Export CSV</Button></form>
    {exportError && <Notice tone="loss" title="Export failed">{exportError.message}</Notice>}
    <DataState query={query}>{data => <>
      {!data.items?.length && <EmptyState title="No matching audit events" />}
      {(data.items ?? []).map(item => <Panel key={item.id} title={item.eventType} subtitle={item.createdAt}>
        <dl className={clear ? 'grid gap-2 text-sm' : 'grid gap-2 text-sm sm:grid-cols-2'}><div><dt className="text-muted">Entity</dt><dd className="break-all">{item.entityType} · {item.entityId}</dd></div><div><dt className="text-muted">Actor / source</dt><dd>{item.actor ?? 'Unknown'} · {item.actorSource}</dd></div><div><dt className="text-muted">Correlation</dt><dd className="break-all">{item.correlationId ?? 'Unavailable'}</dd></div></dl>
        <details className="mt-3"><summary className="min-h-11 cursor-pointer text-sm">Recorded payload</summary><pre className="max-h-80 overflow-auto whitespace-pre-wrap break-all text-xs text-muted">{typeof item.details === 'string' ? item.details : JSON.stringify(item.details,null,2)}</pre></details>
      </Panel>)}
      <nav aria-label="Audit pages" className="flex items-center gap-3"><Button disabled={page === 0 || query.isFetching} onClick={() => onPage(page - 1)}>Previous</Button><span className="text-sm">Page {page + 1} of {Math.max(1, data.totalPages ?? 1)}</span><Button disabled={page + 1 >= (data.totalPages ?? 0) || query.isFetching} onClick={() => onPage(page + 1)}>Next</Button></nav>
    </>}</DataState>
  </div>;
}

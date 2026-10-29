export default function ResearchDiagnosticsDrawer({ diagnostics = [], open, onClose }) {
  if (!open) return null;
  return (
    <div className="fixed inset-0 z-50 flex justify-end bg-black/50" role="dialog" aria-modal="true" aria-label="Research scan diagnostics">
      <button type="button" className="absolute inset-0" aria-label="Close diagnostics" onClick={onClose} />
      <aside className="relative h-full w-full max-w-lg overflow-y-auto border-l border-border bg-surface p-4 shadow-xl">
        <div className="mb-4 flex items-center justify-between gap-3">
          <div><h2 className="text-base font-semibold text-text">Scan diagnostics</h2><p className="text-xs text-muted">Persisted runtime issues for this scan.</p></div>
          <button type="button" onClick={onClose} className="rounded-md border border-border px-3 py-2 text-xs text-muted hover:text-text">Close</button>
        </div>
        {!diagnostics.length ? <p className="text-sm text-muted">No diagnostics recorded.</p> : (
          <div className="grid gap-2">
            {diagnostics.map((item) => (
              <article key={item.id} className="rounded-md border border-border bg-surface-2 p-3">
                <div className="flex flex-wrap items-center gap-2 text-xs"><span className="font-semibold text-text">{item.severity}</span><span className="text-muted">{item.code}</span></div>
                <p className="mt-1 text-sm text-text">{item.message}</p>
                <p className="mt-2 text-[11px] text-muted">{item.instrumentId ? `Instrument ${item.instrumentId}` : 'Run level'}{item.strategy ? ` · ${item.strategy}` : ''}</p>
              </article>
            ))}
          </div>
        )}
      </aside>
    </div>
  );
}

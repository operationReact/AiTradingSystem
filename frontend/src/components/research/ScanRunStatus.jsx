export default function ScanRunStatus({ run }) {
  if (!run) return null;
  const statusClass = run.status === 'SUCCEEDED' ? 'text-gain' : run.status === 'FAILED' ? 'text-loss' : run.status === 'PARTIAL' ? 'text-warning' : 'text-accent';
  return (
    <section className="grid gap-3 rounded-lg border border-border bg-surface p-4 sm:grid-cols-4">
      <div><p className="text-xs text-muted">Status</p><p className={`text-sm font-semibold ${statusClass}`}>{run.status}</p></div>
      <div><p className="text-xs text-muted">Scanned</p><p className="text-sm font-semibold text-text">{run.scannedCount ?? 0}</p></div>
      <div><p className="text-xs text-muted">Candidates</p><p className="text-sm font-semibold text-text">{run.candidateCount ?? 0}</p></div>
      <div><p className="text-xs text-muted">Requested</p><p className="text-sm font-semibold text-text">{run.requestedLimit ?? 0}</p></div>
      {run.errorMessage && <p className="text-xs text-muted sm:col-span-4">{run.errorCode}: {run.errorMessage}</p>}
    </section>
  );
}

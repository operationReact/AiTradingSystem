import CandidateTable from '../CandidateTable.jsx';
import ResearchDiagnosticsDrawer from '../ResearchDiagnosticsDrawer.jsx';
import ResearchScanLauncher from '../ResearchScanLauncher.jsx';
import ScanRunStatus from '../ScanRunStatus.jsx';
import { Button, Notice, ErrorState } from '../../ui.jsx';

export default function ResearchWorkspace({ model, actions, clear = false }) {
  const { connected, strategies, strategyRows, startScan, run, activeRunId, candidates, diagnostics, diagnosticsOpen, scanForm } = model;
  const { onScanForm, onStart, onOpenDiagnostics, onCloseDiagnostics, onRetryCandidates } = actions;
  return (
    <div className={clear ? "grid gap-5" : "grid gap-4"}>
      <header className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <p className="text-xs uppercase tracking-[0.2em] text-muted">Research</p>
          <h1 className="mt-1 text-xl font-semibold text-text md:text-2xl">Strategy scanner</h1>
          <p className="mt-1 max-w-3xl text-sm text-muted">{clear ? 'Explore stored market research and review the evidence behind each candidate.' : 'Run deterministic, bounded research scans and inspect candidate provenance and runtime diagnostics.'} This workspace does not submit broker orders.</p>
        </div>
        {activeRunId && <Button variant="ghost" onClick={onOpenDiagnostics}>Diagnostics{diagnostics.data?.length ? ` (${diagnostics.data.length})` : ''}</Button>}
      </header>

      {!connected && <Notice tone="neutral" title="Operator key required">Connect the operator key to run research scans.</Notice>}
      {strategies.isError && <div className="rounded-md border border-loss/40 bg-loss/10 p-3 text-sm text-loss">Unable to load strategy catalogue.</div>}
      <ResearchScanLauncher strategies={strategyRows} onStart={onStart} pending={startScan.isPending} connected={connected} value={scanForm} onChange={onScanForm} />
      {startScan.isError && <div className="rounded-md border border-loss/40 bg-loss/10 p-3 text-sm text-loss">{startScan.error?.message ?? 'Scan could not be started.'}</div>}
      <ScanRunStatus run={run} />

      <section className="grid gap-2">
        <div className="flex items-center justify-between gap-3"><div><h2 className="text-base font-semibold text-text">Candidates</h2><p className="text-xs text-muted">Ranked by the backend; candidate identity and provenance remain server authoritative.</p></div></div>
        {candidates.isError ? <ErrorState error={candidates.error} onRetry={onRetryCandidates} /> : candidates.isLoading && activeRunId ? <div className="rounded-lg border border-border p-6 text-sm text-muted">Loading candidates…</div> : <CandidateTable candidates={candidates.data ?? []} />}
      </section>

      <ResearchDiagnosticsDrawer diagnostics={diagnostics.data ?? []} open={diagnosticsOpen} onClose={onCloseDiagnostics} />
    </div>
  );
}

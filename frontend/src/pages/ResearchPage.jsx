import { useMemo, useState } from 'react';
import CandidateTable from '../components/research/CandidateTable.jsx';
import ResearchDiagnosticsDrawer from '../components/research/ResearchDiagnosticsDrawer.jsx';
import ResearchScanLauncher from '../components/research/ResearchScanLauncher.jsx';
import ScanRunStatus from '../components/research/ScanRunStatus.jsx';
import { Button } from '../components/ui.jsx';
import { useResearchCandidates, useResearchDiagnostics, useResearchScan, useResearchStrategies, useStartResearchScan } from '../hooks/useResearch.js';

export default function ResearchPage() {
  const strategies = useResearchStrategies();
  const startScan = useStartResearchScan();
  const [runId, setRunId] = useState(null);
  const [diagnosticsOpen, setDiagnosticsOpen] = useState(false);
  const scan = useResearchScan(runId);
  const run = scan.data ?? startScan.data ?? null;
  const activeRunId = run?.id ?? runId;
  const candidates = useResearchCandidates(activeRunId, 100);
  const diagnostics = useResearchDiagnostics(activeRunId);

  const strategyRows = useMemo(() => strategies.data ?? [], [strategies.data]);

  async function handleStart(input) {
    const created = await startScan.mutateAsync({ interval: input.interval, limit: input.limit });
    setRunId(created.id);
  }

  return (
    <div className="grid gap-4">
      <header className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <p className="text-xs uppercase tracking-[0.2em] text-muted">Research</p>
          <h1 className="mt-1 text-xl font-semibold text-text md:text-2xl">Strategy scanner</h1>
          <p className="mt-1 max-w-3xl text-sm text-muted">Run deterministic, bounded research scans and inspect candidates, provenance, and runtime diagnostics. This workspace does not submit broker orders.</p>
        </div>
        {activeRunId && <Button variant="ghost" onClick={() => setDiagnosticsOpen(true)}>Diagnostics{diagnostics.data?.length ? ` (${diagnostics.data.length})` : ''}</Button>}
      </header>

      {strategies.isError && <div className="rounded-md border border-loss/40 bg-loss/10 p-3 text-sm text-loss">Unable to load strategy catalogue.</div>}
      <ResearchScanLauncher strategies={strategyRows} onStart={handleStart} pending={startScan.isPending} />
      {startScan.isError && <div className="rounded-md border border-loss/40 bg-loss/10 p-3 text-sm text-loss">{startScan.error?.message ?? 'Scan could not be started.'}</div>}
      <ScanRunStatus run={run} />

      <section className="grid gap-2">
        <div className="flex items-center justify-between gap-3"><div><h2 className="text-base font-semibold text-text">Candidates</h2><p className="text-xs text-muted">Ranked by the backend; candidate identity and provenance remain server authoritative.</p></div></div>
        {candidates.isLoading && activeRunId ? <div className="rounded-lg border border-border p-6 text-sm text-muted">Loading candidates…</div> : <CandidateTable candidates={candidates.data ?? []} />}
      </section>

      <ResearchDiagnosticsDrawer diagnostics={diagnostics.data ?? []} open={diagnosticsOpen} onClose={() => setDiagnosticsOpen(false)} />
    </div>
  );
}

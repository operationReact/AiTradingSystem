import { useMemo, useState } from 'react';
import { requestState } from '../lib/viewModel.js';
import { useApp } from '../context/AppContext.jsx';
import ExperienceBoundary from '../components/experience/ExperienceBoundary.jsx';
import ProResearchView from '../components/research/pro/ProResearchView.jsx';
import ClearResearchView from '../components/research/clear/ClearResearchView.jsx';
import { useResearchCandidates, useResearchDiagnostics, useResearchScan, useResearchStrategies, useStartResearchScan } from '../hooks/useResearch.js';

export default function ResearchPage() {
  const { apiKey } = useApp();
  const [scanForm, setScanForm] = useState({ strategy: 'ALL', interval: 'FIVE_MINUTE', limit: 100 });
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
    if (!apiKey || startScan.isPending) return;
    try {
      const created = await startScan.mutateAsync({ interval: input.interval, limit: input.limit });
      setRunId(created.id);
    } catch { /* Mutation state renders the error; keep the form for retry. */ }
  }

  const model = { connected: !!apiKey, strategies: requestState(strategies), strategyRows, startScan: requestState(startScan), run, activeRunId,
    candidates: requestState(candidates), diagnostics: requestState(diagnostics), diagnosticsOpen, scanForm };
  const actions = { onScanForm: setScanForm, onStart: handleStart, onOpenDiagnostics: () => setDiagnosticsOpen(true),
    onCloseDiagnostics: () => setDiagnosticsOpen(false), onRetryCandidates: () => candidates.refetch() };
  return <ExperienceBoundary pro={<ProResearchView model={model} actions={actions} />} clear={<ClearResearchView model={model} actions={actions} />} />;
}

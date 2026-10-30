import { Link } from 'react-router-dom';
import { DataState } from '../DataState.jsx';
import { Badge, EmptyState, Panel } from '../ui.jsx';
import { RESEARCH_ONLY_LABEL } from './CandidateResearchDrawer.contract.js';

export default function CandidateEvidenceView({ candidateId, query, clear = false }) {
  return <div className="grid gap-4"><Link to="/research" className="text-sm text-accent">Back to research scanner</Link>
    <header><h1 className="text-xl font-semibold text-text">Candidate {candidateId}</h1><p className="mt-1 text-sm text-muted">{clear ? 'Recorded research evaluations for this candidate.' : 'Immutable evaluation identity, decisions and scenario provenance.'}</p><p className="mt-2 text-sm font-semibold text-warn">{RESEARCH_ONLY_LABEL}</p></header>
    <DataState query={query}>{items => <>{!items.length && <EmptyState title="No recorded evaluations" description="This candidate has no persisted evaluation evidence. An empty history does not establish whether the candidate exists." />}{items.map(item => <Panel key={item.evaluationId} title={item.decisionLabel} actions={<Badge>Research evidence</Badge>}>
      <dl className="grid gap-2 text-xs"><div><dt className="text-muted">Evaluation</dt><dd className="break-all">{item.evaluationId}</dd></div><div><dt className="text-muted">Scenario run</dt><dd className="break-all">{item.scenarioRunId}</dd></div><div><dt className="text-muted">Backtest run</dt><dd className="break-all">{item.backtestRunId ?? 'Unavailable'}</dd></div></dl><p className="mt-3 break-words text-sm">{(item.reasonCodes ?? []).join(' · ') || 'No reason codes recorded'}</p><p className="mt-2 text-xs text-muted">{item.createdAt}</p>
    </Panel>)}</>}</DataState>
  </div>;
}

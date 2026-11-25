import { PolicyStatus, LimitsGrid, EmergencyStopPanel, DecisionsPanel } from '../shared/RiskEvidence.jsx';
import { Panel, Badge } from '../../ui.jsx';
import { ShieldCheck } from 'lucide-react';

export default function ClearInvestRiskView({ policy, summary, stopController, decisionsController }) {
  const snapshot = policy?.snapshot;
  const healthy = summary.data?.newOrdersHalted === false && summary.data?.cashReconciled === true && !summary.isError;
  return (
    <div className="flex flex-col gap-5">
      <Panel title="Your risk health" subtitle="The same backend risk rules, explained in simpler language.">
        <div className="flex flex-wrap items-center justify-between gap-4">
          <div>
            <div className="flex items-center gap-2">
              <ShieldCheck className="h-5 w-5 text-accent" />
              <span className="text-base font-semibold text-text">{healthy ? 'Protection checks are active' : 'Attention required'}</span>
            </div>
            <p className="mt-1 text-xs text-muted">Policy v{policy?.version ?? '—'} limits each trade, daily losses, exposure and trading frequency before a PAPER order can execute.</p>
          </div>
          <Badge tone={healthy ? 'gain' : 'loss'}>{healthy ? 'Risk controls healthy' : 'Risk controls restricted'}</Badge>
        </div>
      </Panel>
      <PolicyStatus policy={policy} summary={summary.data} />
      <Panel title="What the system will not exceed" subtitle="These are enforced limits, not AI recommendations.">
        <LimitsGrid policy={policy} clear />
        {snapshot && <p className="mt-3 text-xs text-muted">Signals older than {snapshot.signalMaxAgeMinutes} minutes and market state older than {snapshot.marketStateMaxAgeSeconds} seconds fail the freshness checks.</p>}
      </Panel>
      <EmergencyStopPanel controller={stopController} summary={summary} clear />
      <DecisionsPanel controller={decisionsController} clear />
    </div>
  );
}


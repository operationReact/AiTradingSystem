import { PolicyStatus, LimitsGrid, EmergencyStopPanel, DecisionsPanel } from '../shared/RiskEvidence.jsx';
import { Panel, Badge } from '../../ui.jsx';
import { ShieldCheck } from 'lucide-react';

export default function ProTerminalRiskView({ policy, summary, stopController, decisionsController }) {
  return (
    <div className="flex flex-col gap-5">
      <PolicyStatus policy={policy} summary={summary.data} compact />
      <Panel title="Hard risk limits" subtitle="Dense view of the active immutable policy used immediately before execution.">
        <LimitsGrid policy={policy} />
      </Panel>
      <EmergencyStopPanel controller={stopController} summary={summary} />
      <DecisionsPanel controller={decisionsController} />
    </div>
  );
}


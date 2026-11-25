import ExperienceBoundary from '../components/experience/ExperienceBoundary.jsx';
import OperationsView from '../components/operations/OperationsView.jsx';
import { useOperatorEvidence } from '../hooks/useOperatorEvidence.js';

export default function OperationsPage() {
  const query = useOperatorEvidence(['health'], '/operations/health');
  return <ExperienceBoundary pro={<OperationsView query={query} />} clear={<OperationsView query={query} clear />} />;
}

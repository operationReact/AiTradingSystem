import { useParams } from 'react-router-dom';
import { Notice } from '../components/ui.jsx';
import ExperienceBoundary from '../components/experience/ExperienceBoundary.jsx';
import CandidateEvidenceView from '../components/research/CandidateEvidenceView.jsx';
import { useOperatorEvidence } from '../hooks/useOperatorEvidence.js';

export default function CandidateDetailPage() {
  const { candidateId } = useParams();
  const valid = /^[1-9]\d*$/.test(candidateId) && Number.isSafeInteger(Number(candidateId));
  const query = useOperatorEvidence(['candidate', candidateId], `/research/candidates/${encodeURIComponent(candidateId)}/evaluations`, valid);
  if (!valid) return <Notice tone="loss" title="Invalid candidate ID">Use a positive numeric candidate ID from the scanner.</Notice>;
  return <ExperienceBoundary pro={<CandidateEvidenceView candidateId={candidateId} query={query} />} clear={<CandidateEvidenceView candidateId={candidateId} query={query} clear />} />;
}

import { useParams } from 'react-router-dom';
import { Modal, Notice } from '../components/ui.jsx';
import ExperienceBoundary from '../components/experience/ExperienceBoundary.jsx';
import CandidateEvidenceView from '../components/research/CandidateEvidenceView.jsx';
import { useCandidateResearch } from '../hooks/useCandidateResearch.js';

export default function CandidateDetailPage() {
  const { candidateId } = useParams();
  const valid = /^[1-9]\d*$/.test(candidateId) && Number.isSafeInteger(Number(candidateId));
  const controller = useCandidateResearch(candidateId, valid);
  if (!valid) return <Notice tone="loss" title="Invalid candidate ID">Use a positive numeric candidate ID from the scanner.</Notice>;
  return <><ExperienceBoundary pro={<CandidateEvidenceView controller={controller} />} clear={<CandidateEvidenceView controller={controller} clear />} />
    <Modal open={controller.provenanceOpen} onOpenChange={controller.setProvenanceOpen} title="Methodology and provenance" description="Persisted research assumptions and source identity."><dl className="grid max-h-[60vh] gap-3 overflow-y-auto text-xs">{Object.entries(controller.model ? { ...controller.model.methodologyProvenance, catalogueVersion: controller.candidate?.catalogueVersion, candidateFingerprint: controller.candidate?.candidateFingerprint, signalCandleTime: controller.candidate?.signalCandleTime, slippageBps: controller.scenarioQuery.data?.slippageBps, roundTripCostBps: controller.scenarioQuery.data?.roundTripCostBps } : {}).map(([key,value]) => <div key={key}><dt className="text-muted">{key}</dt><dd className="break-all">{value ?? 'Unavailable'}</dd></div>)}</dl></Modal></>;
}

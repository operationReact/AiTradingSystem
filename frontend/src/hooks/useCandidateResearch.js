import { useRef, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useApp } from '../context/AppContext.jsx';
import { apiFetch } from '../lib/api.js';
import { defaultResearchForm, researchRequest, candidateEvidence } from '../lib/candidateEvidence.js';
import { useOperatorEvidence } from './useOperatorEvidence.js';
import { requestState } from '../lib/viewModel.js';

export function useCandidateResearch(candidateId, valid) {
  const { apiKey } = useApp();
  const cache = useQueryClient();
  const current = useRef(candidateId);
  current.current = candidateId;
  const submitting = useRef(false);
  const candidateQuery = useOperatorEvidence(['candidate-identity', candidateId], `/research/candidates/${candidateId}`, valid);
  const historyQuery = useOperatorEvidence(['candidate', candidateId], `/research/candidates/${candidateId}/evaluations`, valid);
  const candidate = candidateQuery.data?.id === Number(candidateId) ? candidateQuery.data : null;
  const [draft, setDraft] = useState(null);
  const form = draft?.candidateId === candidateId ? draft.form : defaultResearchForm(candidate);
  const [selection, setSelection] = useState(null);
  const [provenanceOpen, setProvenanceOpen] = useState(null);
  const [outcome, setOutcome] = useState(null);
  const history = Array.isArray(historyQuery.data) ? historyQuery.data.filter(e => Number(e.candidateId) === Number(candidateId)) : [];
  const selected = selection?.candidateId === candidateId ? history.find(e => e.evaluationId === selection.id) : history[0];
  const scenarioQuery = useOperatorEvidence(['scenario', selected?.scenarioRunId], `/research/simulations/${encodeURIComponent(selected?.scenarioRunId ?? '')}`, valid && !!selected?.scenarioRunId);
  const mutation = useMutation({ mutationFn: ({ id, body, key }) => apiFetch(`/research/candidates/${id}/evaluate`, { apiKey: key, method: 'POST', body }) });
  let validationError = null, body = null;
  try { body = researchRequest(form, candidate); } catch (e) { validationError = e.message; }
  const canEvaluate = !!apiKey && !!body && !candidateQuery.isError && !candidateQuery.isFetching && !mutation.isPending;
  let model = null, evidenceError = null;
  if (apiKey && selected && scenarioQuery.data) {
    try { model = candidateEvidence(candidate, selected, scenarioQuery.data); } catch (e) { evidenceError = e.message; }
  }
  const project = q => ({ ...requestState(q), refetch: () => q.refetch() });
  return {
    candidateId, candidate, candidateQuery: project(candidateQuery), historyQuery: project(historyQuery), scenarioQuery: project(scenarioQuery),
    history, selected, form, validationError, canEvaluate, pending: mutation.isPending,
    model, evidenceError, provenanceOpen: !!model && provenanceOpen?.candidateId === candidateId && provenanceOpen?.evaluationId === selected?.evaluationId,
    setProvenanceOpen: open => setProvenanceOpen(open ? { candidateId, evaluationId: selected?.evaluationId } : null),
    outcome: outcome?.candidateId === candidateId ? outcome : null,
    setField: (key, value) => setDraft({ candidateId, form: { ...form, [key]: value } }),
    selectEvaluation: id => { setSelection({ candidateId, id }); setProvenanceOpen(false); },
    evaluate: async () => {
      if (!canEvaluate || submitting.current) return;
      submitting.current = true;
      const id = candidateId;
      setOutcome(null);
      try {
        const result = await mutation.mutateAsync({ id, body, key: apiKey });
        if (Number(result?.candidateId) !== Number(id) || !result.evaluationId || !result.scenarioRunId) throw new Error('Evaluation response identity does not match the candidate.');
        await cache.invalidateQueries({ queryKey: ['operator-evidence', 'candidate', id] });
        if (current.current === id) {
          setSelection({ candidateId: id, id: result.evaluationId });
          setOutcome({ candidateId: id, message: result.scenarioReused ? 'Evaluation recorded using the existing reproducible scenario.' : 'Evaluation recorded. Scenario evidence is persisted.' });
        }
      } catch (e) { if (current.current === id) setOutcome({ candidateId: id, error: true, message: e.message }); }
      finally { submitting.current = false; }
    },
  };
}

import { buildCandidateResearchDrawerModel } from '../components/research/CandidateResearchDrawer.contract.js';

export const toISTInput = value => new Date((typeof value === 'number' ? value : Date.parse(value)) + 330 * 60_000).toISOString().slice(0, 16);
export const fromISTInput = value => new Date(`${value}${value.length === 16 ? ':00' : ''}+05:30`).toISOString();
export function defaultResearchForm(candidate) {
  if (!candidate || !Number.isFinite(Date.parse(candidate.dataCutoffAt))) return null;
  return { historyFrom: toISTInput(Date.parse(candidate.dataCutoffAt) - 180 * 86400_000), historyTo: toISTInput(candidate.dataCutoffAt), simulations: '1000', horizonBars: '20', blockSize: '5', seed: '42', slippageBps: '5', roundTripCostBps: '10' };
}
export function researchRequest(form, candidate) {
  if (!form || !candidate) throw new Error('Candidate provenance is required.');
  const body = { historyFrom: fromISTInput(form.historyFrom), historyTo: fromISTInput(form.historyTo) };
  if (Date.parse(body.historyFrom) >= Date.parse(body.historyTo)) throw new Error('History start must precede history end.');
  if (Date.parse(body.historyTo) > Date.parse(candidate.dataCutoffAt)) throw new Error('History must end at or before the candidate data cutoff.');
  for (const [key, min, max] of [['simulations', 100, 100000], ['horizonBars', 1, 500], ['blockSize', 1, 50], ['seed', Number.MIN_SAFE_INTEGER, Number.MAX_SAFE_INTEGER], ['slippageBps', 0, 500], ['roundTripCostBps', 0, 500]]) {
    const value = Number(form[key]);
    if (String(form[key]).trim() === '' || !Number.isSafeInteger(value) || value < min || value > max) throw new Error(`${key} must be an integer between ${min} and ${max}.`);
    body[key] = value;
  }
  if (body.blockSize > body.horizonBars) throw new Error('Block size cannot exceed horizon bars.');
  return body;
}

/** Bind persisted evidence before displaying statistics; never repair or normalize invalid results. */
export function candidateEvidence(candidate, evaluation, scenario) {
  if (!candidate || !evaluation || !scenario) throw new Error('Candidate, evaluation and scenario evidence are required.');
  if (Number(evaluation.candidateId) !== Number(candidate.id) || Number(scenario.candidateId) !== Number(candidate.id)
      || scenario.scanRunId !== candidate.scanRunId || Number(scenario.instrumentId) !== Number(candidate.instrumentId)
      || scenario.strategy !== candidate.strategy || (scenario.strategyConfigVersion ?? null) !== (candidate.strategyConfigVersion ?? null)
      || scenario.exchange !== candidate.exchange || scenario.tradingSymbol !== candidate.tradingSymbol
      || ['entryPrice', 'stopPrice', 'targetPrice'].some(key => typeof candidate[key] !== 'number' || !Number.isFinite(candidate[key]) || candidate[key] !== scenario[key])
      || scenario.interval !== candidate.interval || !Number.isFinite(Date.parse(candidate.dataCutoffAt))
      || Date.parse(scenario.dataCutoffAt) !== Date.parse(candidate.dataCutoffAt)
      || !Number.isFinite(Date.parse(scenario.historyFrom)) || !Number.isFinite(Date.parse(scenario.historyTo))
      || Date.parse(scenario.historyFrom) >= Date.parse(scenario.historyTo)
      || Date.parse(scenario.historyTo) > Date.parse(candidate.dataCutoffAt)) throw new Error('Persisted candidate and scenario provenance do not match.');
  if (scenario.status !== 'SUCCEEDED') throw new Error(`Scenario results unavailable: ${scenario.status ?? 'unknown status'}. ${scenario.errorCode ?? ''}`);
  const model = buildCandidateResearchDrawerModel(evaluation, scenario);
  const probabilities = Object.values(model.scenarioProbabilityChart);
  if (probabilities.some(p => typeof p !== 'number' || !Number.isFinite(p) || p < 0 || p > 1) || Math.abs(probabilities.reduce((a, b) => a + b, 0) - 1) > 0.00001) throw new Error('Stored scenario probabilities are invalid.');
  const { expectedR, p05R, p50R, p95R } = model.rDistributionSummary;
  if ([expectedR, p05R, p50R, p95R].some(v => typeof v !== 'number' || !Number.isFinite(v)) || p05R > p50R || p50R > p95R) throw new Error('Stored R distribution is invalid.');
  return model;
}

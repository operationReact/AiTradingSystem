export const RESEARCH_ONLY_LABEL = "Research only — not an order";

export const CANDIDATE_RESEARCH_DRAWER_CONTRACT = Object.freeze({
  component: "CandidateResearchDrawer",
  visualImplementationPhase: 9,
  safetyLabel: RESEARCH_ONLY_LABEL,
  dataSources: Object.freeze({
    evaluations: "/api/research/candidates/{candidateId}/evaluations",
    scenario: "/api/research/simulations/{scenarioRunId}",
  }),
  sections: Object.freeze([
    Object.freeze({
      component: "ScenarioProbabilityChart",
      fields: Object.freeze([
        "targetFirstProbability",
        "stopFirstProbability",
        "neitherProbability",
      ]),
    }),
    Object.freeze({
      component: "RDistributionSummary",
      fields: Object.freeze(["expectedR", "p05R", "p50R", "p95R"]),
    }),
    Object.freeze({
      component: "RegimeChips",
      fields: Object.freeze(["regime", "fallbackTier"]),
    }),
    Object.freeze({
      component: "MethodologyProvenanceDrawer",
      fields: Object.freeze([
        "methodology",
        "strategy",
        "strategyConfigVersion",
        "scanRunId",
        "candidateId",
        "requestFingerprint",
        "historyFrom",
        "historyTo",
        "dataCutoffAt",
        "seed",
        "simulationCount",
        "horizonBars",
        "blockSize",
        "historicalSamples",
        "eligibleBlockCount",
      ]),
    }),
  ]),
  forbiddenActions: Object.freeze([
    "placeOrder",
    "submitOrder",
    "approveOrder",
    "executeTrade",
  ]),
});

/**
 * Stable non-visual Phase 4 projection consumed by the visual Phase 9 implementation.
 * It deliberately exposes research evidence only; no order/trade action is part of this model.
 */
export function buildCandidateResearchDrawerModel(evaluation, scenario) {
  if (!evaluation || !scenario) {
    throw new Error("evaluation and scenario are required");
  }
  if (!evaluation.scenarioRunId || evaluation.scenarioRunId !== scenario.id) {
    throw new Error("evaluation and scenario provenance do not match");
  }
  if (
    scenario.candidateId != null &&
    Number(scenario.candidateId) !== Number(evaluation.candidateId)
  ) {
    throw new Error("candidate provenance does not match scenario evidence");
  }

  return Object.freeze({
    component: CANDIDATE_RESEARCH_DRAWER_CONTRACT.component,
    safetyLabel: RESEARCH_ONLY_LABEL,
    evaluation: Object.freeze({
      evaluationId: evaluation.evaluationId,
      candidateId: evaluation.candidateId,
      scenarioRunId: evaluation.scenarioRunId,
      backtestRunId: evaluation.backtestRunId ?? null,
      decisionLabel: evaluation.decisionLabel,
      reasonCodes: Object.freeze([...(evaluation.reasonCodes ?? [])]),
      createdAt: evaluation.createdAt,
    }),
    scenarioProbabilityChart: Object.freeze({
      targetFirstProbability: scenario.targetFirstProbability,
      stopFirstProbability: scenario.stopFirstProbability,
      neitherProbability: scenario.neitherProbability,
    }),
    rDistributionSummary: Object.freeze({
      expectedR: scenario.expectedR,
      p05R: scenario.p05R,
      p50R: scenario.p50R,
      p95R: scenario.p95R,
    }),
    regimeChips: Object.freeze({
      marketRegime: scenario.regime,
      samplingFallbackTier: scenario.fallbackTier,
    }),
    methodologyProvenance: Object.freeze({
      methodology: scenario.methodology,
      strategy: scenario.strategy,
      strategyConfigVersion: scenario.strategyConfigVersion,
      scanRunId: scenario.scanRunId,
      candidateId: scenario.candidateId,
      requestFingerprint: scenario.requestFingerprint,
      historyFrom: scenario.historyFrom,
      historyTo: scenario.historyTo,
      dataCutoffAt: scenario.dataCutoffAt,
      seed: scenario.seed,
      simulationCount: scenario.simulationCount,
      horizonBars: scenario.horizonBars,
      blockSize: scenario.blockSize,
      historicalSamples: scenario.historicalSamples,
      eligibleBlockCount: scenario.eligibleBlockCount,
    }),
  });
}

export const candidate = {
  id:42, scanRunId:'scan-1', instrumentId:1, exchange:'NSE', tradingSymbol:'RELIANCE',
  interval:'FIVE_MINUTE', strategy:'OPENING_RANGE_BREAKOUT_V1', strategyConfigVersion:null,
  catalogueVersion:'catalogue-1', candidateFingerprint:'c'.repeat(64),
  signalCandleTime:'2026-06-30T09:55:00Z', dataCutoffAt:'2026-06-30T09:55:00Z',
  entryPrice:2500, stopPrice:2450, targetPrice:2600,
};
export const evaluation = { evaluationId:'eval-1', candidateId:42, scenarioRunId:'scenario-1', decisionLabel:'FAVORABLE', reasonCodes:['POSITIVE_EXPECTANCY'], createdAt:'2026-10-30T05:00:00Z' };
export const scenario = {
  ...candidate, id:'scenario-1', candidateId:42, status:'SUCCEEDED',
  historyFrom:'2026-01-01T03:45:00Z', historyTo:candidate.dataCutoffAt,
  targetFirstProbability:.55, stopFirstProbability:.30, neitherProbability:.15,
  expectedR:.50, p05R:-1.02, p50R:.40, p95R:2.05,
  regime:'HIGH_VOL', fallbackTier:'EXACT_VOLATILITY_AND_VOLUME',
  methodology:'SESSION_REGIME_BLOCK_BOOTSTRAP_V2', requestFingerprint:'a'.repeat(64),
  seed:42, simulationCount:1000, horizonBars:20, blockSize:5,
  historicalSamples:480, eligibleBlockCount:350, slippageBps:5, roundTripCostBps:10,
};

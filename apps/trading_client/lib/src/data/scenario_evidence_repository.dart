import 'authenticated_api_client.dart';

class ScenarioRunEvidence {
  const ScenarioRunEvidence({
    required this.id,
    required this.scanRunId,
    required this.candidateId,
    required this.instrumentId,
    required this.exchange,
    required this.tradingSymbol,
    required this.interval,
    required this.strategy,
    required this.strategyConfigVersion,
    required this.entryPrice,
    required this.stopPrice,
    required this.targetPrice,
    required this.historyFrom,
    required this.historyTo,
    required this.dataCutoffAt,
    required this.simulationCount,
    required this.horizonBars,
    required this.blockSize,
    required this.seed,
    required this.slippageBps,
    required this.roundTripCostBps,
    required this.requestFingerprint,
    required this.methodology,
    required this.regime,
    required this.historicalSamples,
    required this.eligibleBlockCount,
    required this.fallbackTier,
    required this.targetFirstCount,
    required this.stopFirstCount,
    required this.neitherCount,
    required this.targetFirstProbability,
    required this.stopFirstProbability,
    required this.neitherProbability,
    required this.expectedR,
    required this.p05R,
    required this.p50R,
    required this.p95R,
    required this.status,
    required this.errorCode,
    required this.errorMessage,
    required this.startedAt,
    required this.completedAt,
    required this.createdAt,
  });

  final String id;
  final String scanRunId;
  final int? candidateId;
  final int instrumentId;
  final String exchange;
  final String tradingSymbol;
  final String interval;
  final String strategy;
  final int? strategyConfigVersion;
  final num? entryPrice;
  final num? stopPrice;
  final num? targetPrice;
  final DateTime? historyFrom;
  final DateTime? historyTo;
  final DateTime? dataCutoffAt;
  final int simulationCount;
  final int horizonBars;
  final int blockSize;
  final int seed;
  final int slippageBps;
  final int roundTripCostBps;
  final String requestFingerprint;
  final String methodology;
  final String regime;
  final int historicalSamples;
  final int eligibleBlockCount;
  final String? fallbackTier;
  final int targetFirstCount;
  final int stopFirstCount;
  final int neitherCount;
  final num? targetFirstProbability;
  final num? stopFirstProbability;
  final num? neitherProbability;
  final num? expectedR;
  final num? p05R;
  final num? p50R;
  final num? p95R;
  final String status;
  final String? errorCode;
  final String? errorMessage;
  final DateTime? startedAt;
  final DateTime? completedAt;
  final DateTime? createdAt;

  bool get hasRequiredProvenance =>
      id.isNotEmpty &&
      scanRunId.isNotEmpty &&
      instrumentId > 0 &&
      interval.isNotEmpty &&
      methodology.isNotEmpty &&
      regime.isNotEmpty &&
      dataCutoffAt != null &&
      createdAt != null;

  factory ScenarioRunEvidence.fromJson(Map<String, dynamic> json) => ScenarioRunEvidence(
        id: '${json['id'] ?? ''}',
        scanRunId: '${json['scanRunId'] ?? ''}',
        candidateId: (json['candidateId'] as num?)?.toInt(),
        instrumentId: (json['instrumentId'] as num?)?.toInt() ?? 0,
        exchange: '${json['exchange'] ?? ''}',
        tradingSymbol: '${json['tradingSymbol'] ?? ''}',
        interval: '${json['interval'] ?? ''}',
        strategy: '${json['strategy'] ?? ''}',
        strategyConfigVersion: (json['strategyConfigVersion'] as num?)?.toInt(),
        entryPrice: json['entryPrice'] as num?,
        stopPrice: json['stopPrice'] as num?,
        targetPrice: json['targetPrice'] as num?,
        historyFrom: DateTime.tryParse('${json['historyFrom'] ?? ''}'),
        historyTo: DateTime.tryParse('${json['historyTo'] ?? ''}'),
        dataCutoffAt: DateTime.tryParse('${json['dataCutoffAt'] ?? ''}'),
        simulationCount: (json['simulationCount'] as num?)?.toInt() ?? 0,
        horizonBars: (json['horizonBars'] as num?)?.toInt() ?? 0,
        blockSize: (json['blockSize'] as num?)?.toInt() ?? 0,
        seed: (json['seed'] as num?)?.toInt() ?? 0,
        slippageBps: (json['slippageBps'] as num?)?.toInt() ?? 0,
        roundTripCostBps: (json['roundTripCostBps'] as num?)?.toInt() ?? 0,
        requestFingerprint: '${json['requestFingerprint'] ?? ''}',
        methodology: '${json['methodology'] ?? ''}',
        regime: '${json['regime'] ?? ''}',
        historicalSamples: (json['historicalSamples'] as num?)?.toInt() ?? 0,
        eligibleBlockCount: (json['eligibleBlockCount'] as num?)?.toInt() ?? 0,
        fallbackTier: json['fallbackTier']?.toString(),
        targetFirstCount: (json['targetFirstCount'] as num?)?.toInt() ?? 0,
        stopFirstCount: (json['stopFirstCount'] as num?)?.toInt() ?? 0,
        neitherCount: (json['neitherCount'] as num?)?.toInt() ?? 0,
        targetFirstProbability: json['targetFirstProbability'] as num?,
        stopFirstProbability: json['stopFirstProbability'] as num?,
        neitherProbability: json['neitherProbability'] as num?,
        expectedR: json['expectedR'] as num?,
        p05R: json['p05R'] as num?,
        p50R: json['p50R'] as num?,
        p95R: json['p95R'] as num?,
        status: '${json['status'] ?? 'UNKNOWN'}',
        errorCode: json['errorCode']?.toString(),
        errorMessage: json['errorMessage']?.toString(),
        startedAt: DateTime.tryParse('${json['startedAt'] ?? ''}'),
        completedAt: DateTime.tryParse('${json['completedAt'] ?? ''}'),
        createdAt: DateTime.tryParse('${json['createdAt'] ?? ''}'),
      );
}

abstract interface class ScenarioEvidenceRepository {
  Future<ScenarioRunEvidence> fetch(String scenarioRunId);
}

class HttpScenarioEvidenceRepository implements ScenarioEvidenceRepository {
  HttpScenarioEvidenceRepository(this.api);

  final AuthenticatedApiClient api;

  @override
  Future<ScenarioRunEvidence> fetch(String scenarioRunId) async {
    if (scenarioRunId.trim().isEmpty) {
      throw const ApiTransportFailure('SCENARIO_RUN_ID_REQUIRED', null);
    }
    final decoded = await api.getJson('/api/research/simulations/$scenarioRunId');
    if (decoded is! Map) {
      throw const ApiTransportFailure('INVALID_SCENARIO_EVIDENCE_RESPONSE', null);
    }
    final evidence = ScenarioRunEvidence.fromJson(Map<String, dynamic>.from(decoded));
    if (!evidence.hasRequiredProvenance) {
      throw const ApiTransportFailure('INCOMPLETE_SCENARIO_PROVENANCE', null);
    }
    return evidence;
  }
}

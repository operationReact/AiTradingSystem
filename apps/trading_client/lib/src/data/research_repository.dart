import 'authenticated_api_client.dart';

class ResearchScanSummary {
  const ResearchScanSummary({
    required this.id,
    required this.interval,
    required this.status,
    required this.requestedLimit,
    required this.scannedCount,
    required this.candidateCount,
    required this.strategySet,
    required this.startedAt,
    required this.completedAt,
    required this.errorCode,
    required this.errorMessage,
  });

  final String id;
  final String interval;
  final String status;
  final int requestedLimit;
  final int scannedCount;
  final int candidateCount;
  final String strategySet;
  final DateTime? startedAt;
  final DateTime? completedAt;
  final String? errorCode;
  final String? errorMessage;

  factory ResearchScanSummary.fromJson(Map<String, dynamic> json) => ResearchScanSummary(
        id: '${json['id'] ?? ''}',
        interval: '${json['interval'] ?? ''}',
        status: '${json['status'] ?? 'UNKNOWN'}',
        requestedLimit: (json['requestedLimit'] as num?)?.toInt() ?? 0,
        scannedCount: (json['scannedCount'] as num?)?.toInt() ?? 0,
        candidateCount: (json['candidateCount'] as num?)?.toInt() ?? 0,
        strategySet: '${json['strategySet'] ?? ''}',
        startedAt: DateTime.tryParse('${json['startedAt'] ?? ''}'),
        completedAt: DateTime.tryParse('${json['completedAt'] ?? ''}'),
        errorCode: json['errorCode']?.toString(),
        errorMessage: json['errorMessage']?.toString(),
      );
}

class ResearchCandidate {
  const ResearchCandidate({
    required this.candidateId,
    required this.scanRunId,
    required this.instrumentId,
    required this.tradingSymbol,
    required this.exchange,
    required this.interval,
    required this.strategy,
    required this.score,
    required this.referencePrice,
    required this.suggestedStop,
    required this.suggestedTarget,
    required this.candleTime,
    required this.reasons,
    required this.fingerprint,
  });

  final int candidateId;
  final String scanRunId;
  final int instrumentId;
  final String tradingSymbol;
  final String exchange;
  final String interval;
  final String strategy;
  final double score;
  final num referencePrice;
  final num suggestedStop;
  final num suggestedTarget;
  final DateTime? candleTime;
  final List<String> reasons;
  final String fingerprint;

  factory ResearchCandidate.fromJson(Map<String, dynamic> json) => ResearchCandidate(
        candidateId: (json['candidateId'] as num?)?.toInt() ?? 0,
        scanRunId: '${json['scanRunId'] ?? ''}',
        instrumentId: (json['instrumentId'] as num?)?.toInt() ?? 0,
        tradingSymbol: '${json['tradingSymbol'] ?? ''}',
        exchange: '${json['exchange'] ?? ''}',
        interval: '${json['interval'] ?? ''}',
        strategy: '${json['strategy'] ?? ''}',
        score: (json['score'] as num?)?.toDouble() ?? 0,
        referencePrice: json['referencePrice'] as num? ?? 0,
        suggestedStop: json['suggestedStop'] as num? ?? 0,
        suggestedTarget: json['suggestedTarget'] as num? ?? 0,
        candleTime: DateTime.tryParse('${json['candleTime'] ?? ''}'),
        reasons: (json['reasons'] as List<dynamic>? ?? const []).map((value) => '$value').toList(growable: false),
        fingerprint: '${json['candidateFingerprint'] ?? ''}',
      );
}

class ResearchCandidateDetail {
  const ResearchCandidateDetail({
    required this.id,
    required this.scanRunId,
    required this.instrumentId,
    required this.tradingSymbol,
    required this.exchange,
    required this.interval,
    required this.strategy,
    required this.entryPrice,
    required this.stopPrice,
    required this.targetPrice,
    required this.signalCandleTime,
    required this.dataCutoffAt,
  });

  final int id;
  final String scanRunId;
  final int instrumentId;
  final String tradingSymbol;
  final String exchange;
  final String interval;
  final String strategy;
  final num entryPrice;
  final num stopPrice;
  final num targetPrice;
  final DateTime? signalCandleTime;
  final DateTime? dataCutoffAt;

  factory ResearchCandidateDetail.fromJson(Map<String, dynamic> json) => ResearchCandidateDetail(
        id: (json['id'] as num?)?.toInt() ?? 0,
        scanRunId: '${json['scanRunId'] ?? ''}',
        instrumentId: (json['instrumentId'] as num?)?.toInt() ?? 0,
        tradingSymbol: '${json['tradingSymbol'] ?? ''}',
        exchange: '${json['exchange'] ?? ''}',
        interval: '${json['interval'] ?? ''}',
        strategy: '${json['strategy'] ?? ''}',
        entryPrice: json['entryPrice'] as num? ?? 0,
        stopPrice: json['stopPrice'] as num? ?? 0,
        targetPrice: json['targetPrice'] as num? ?? 0,
        signalCandleTime: DateTime.tryParse('${json['signalCandleTime'] ?? ''}'),
        dataCutoffAt: DateTime.tryParse('${json['dataCutoffAt'] ?? ''}'),
      );
}

class ResearchEvaluation {
  const ResearchEvaluation({
    required this.evaluationId,
    required this.candidateId,
    required this.scenarioRunId,
    required this.decisionLabel,
    required this.reasonCodes,
    required this.createdAt,
  });

  final String evaluationId;
  final int candidateId;
  final String scenarioRunId;
  final String decisionLabel;
  final List<String> reasonCodes;
  final DateTime? createdAt;

  factory ResearchEvaluation.fromJson(Map<String, dynamic> json) => ResearchEvaluation(
        evaluationId: '${json['evaluationId'] ?? ''}',
        candidateId: (json['candidateId'] as num?)?.toInt() ?? 0,
        scenarioRunId: '${json['scenarioRunId'] ?? ''}',
        decisionLabel: '${json['decisionLabel'] ?? 'UNKNOWN'}',
        reasonCodes: (json['reasonCodes'] as List<dynamic>? ?? const []).map((value) => '$value').toList(growable: false),
        createdAt: DateTime.tryParse('${json['createdAt'] ?? ''}'),
      );
}

abstract interface class ResearchRepository {
  Future<ResearchScanSummary> runScan({String interval = 'FIVE_MINUTE', int limit = 100});
  Future<List<ResearchCandidate>> candidates(String scanId, {int limit = 50});
  Future<ResearchCandidateDetail> candidate(int candidateId);
  Future<List<ResearchEvaluation>> evaluations(int candidateId);
  Future<ResearchEvaluation> evaluate({
    required int candidateId,
    required DateTime historyFrom,
    required DateTime historyTo,
    int simulations = 1000,
    int horizonBars = 24,
    int blockSize = 4,
    int seed = 42,
    int slippageBps = 5,
    int roundTripCostBps = 10,
  });
}

class HttpResearchRepository implements ResearchRepository {
  HttpResearchRepository(this.api);

  final AuthenticatedApiClient api;

  @override
  Future<ResearchScanSummary> runScan({String interval = 'FIVE_MINUTE', int limit = 100}) async {
    final decoded = await api.postJson(
      '/api/research/scans',
      query: {'interval': interval, 'limit': '$limit'},
      timeout: const Duration(seconds: 60),
    );
    if (decoded is! Map<String, dynamic>) {
      throw const ApiTransportFailure('INVALID_RESEARCH_SCAN_RESPONSE', null);
    }
    return ResearchScanSummary.fromJson(decoded);
  }

  @override
  Future<List<ResearchCandidate>> candidates(String scanId, {int limit = 50}) async {
    final decoded = await api.getJson('/api/research/scans/$scanId/candidates', query: {'limit': '$limit'});
    if (decoded is! List<dynamic>) {
      throw const ApiTransportFailure('INVALID_RESEARCH_CANDIDATE_RESPONSE', null);
    }
    return decoded.whereType<Map<String, dynamic>>().map(ResearchCandidate.fromJson).toList(growable: false);
  }

  @override
  Future<ResearchCandidateDetail> candidate(int candidateId) async {
    final decoded = await api.getJson('/api/research/candidates/$candidateId');
    if (decoded is! Map<String, dynamic>) {
      throw const ApiTransportFailure('INVALID_RESEARCH_CANDIDATE_DETAIL_RESPONSE', null);
    }
    return ResearchCandidateDetail.fromJson(decoded);
  }

  @override
  Future<List<ResearchEvaluation>> evaluations(int candidateId) async {
    final decoded = await api.getJson('/api/research/candidates/$candidateId/evaluations');
    if (decoded is! List<dynamic>) {
      throw const ApiTransportFailure('INVALID_RESEARCH_EVALUATION_HISTORY_RESPONSE', null);
    }
    return decoded.whereType<Map<String, dynamic>>().map(ResearchEvaluation.fromJson).toList(growable: false);
  }

  @override
  Future<ResearchEvaluation> evaluate({
    required int candidateId,
    required DateTime historyFrom,
    required DateTime historyTo,
    int simulations = 1000,
    int horizonBars = 24,
    int blockSize = 4,
    int seed = 42,
    int slippageBps = 5,
    int roundTripCostBps = 10,
  }) async {
    final decoded = await api.postJson(
      '/api/research/candidates/$candidateId/evaluate',
      body: {
        'historyFrom': historyFrom.toUtc().toIso8601String(),
        'historyTo': historyTo.toUtc().toIso8601String(),
        'simulations': simulations,
        'horizonBars': horizonBars,
        'blockSize': blockSize,
        'seed': seed,
        'slippageBps': slippageBps,
        'roundTripCostBps': roundTripCostBps,
        'backtestRunId': null,
      },
      timeout: const Duration(seconds: 90),
    );
    if (decoded is! Map<String, dynamic>) {
      throw const ApiTransportFailure('INVALID_RESEARCH_EVALUATION_RESPONSE', null);
    }
    return ResearchEvaluation.fromJson(decoded);
  }
}

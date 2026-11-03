import 'authenticated_api_client.dart';

enum AiScannerState {
  eligible('ELIGIBLE'),
  watch('WATCH'),
  abstain('ABSTAIN'),
  riskBlocked('RISK_BLOCKED'),
  stale('STALE');

  const AiScannerState(this.apiValue);
  final String apiValue;

  static AiScannerState parse(String value) => switch (value) {
        'ELIGIBLE' => AiScannerState.eligible,
        'WATCH' => AiScannerState.watch,
        'ABSTAIN' => AiScannerState.abstain,
        'RISK_BLOCKED' => AiScannerState.riskBlocked,
        'STALE' => AiScannerState.stale,
        _ => throw FormatException('Unsupported AI scanner state: $value'),
      };
}

enum AiScannerSort {
  expectedNetEdge('EXPECTED_NET_EDGE'),
  calibratedProbability('CALIBRATED_PROBABILITY'),
  opportunityRank('OPPORTUNITY_RANK'),
  liquidity('LIQUIDITY');

  const AiScannerSort(this.apiValue);
  final String apiValue;
}

enum AiScannerUncertainty {
  low('LOW'),
  medium('MEDIUM'),
  high('HIGH');

  const AiScannerUncertainty(this.apiValue);
  final String apiValue;
}

enum AiScannerSourceStatus {
  ready,
  noRankedData;

  static AiScannerSourceStatus parse(String value) => switch (value) {
        'READY' => AiScannerSourceStatus.ready,
        'NO_RANKED_DATA' => AiScannerSourceStatus.noRankedData,
        _ => throw FormatException('Unsupported AI scanner source status: $value'),
      };
}

class AiScannerQuery {
  const AiScannerQuery({
    this.state,
    this.sector,
    this.uncertainty,
    this.minRegimeFit,
    this.minLiquidityPercentile,
    this.sort = AiScannerSort.expectedNetEdge,
    this.limit = 50,
  });

  final AiScannerState? state;
  final String? sector;
  final AiScannerUncertainty? uncertainty;
  final double? minRegimeFit;
  final double? minLiquidityPercentile;
  final AiScannerSort sort;
  final int limit;

  Map<String, String?> toQueryParameters() {
    if (limit < 1 || limit > 100) {
      throw ArgumentError.value(limit, 'limit', 'must be within [1, 100]');
    }
    _validateOptionalUnitInterval(minRegimeFit, 'minRegimeFit');
    _validateOptionalUnitInterval(
      minLiquidityPercentile,
      'minLiquidityPercentile',
    );
    if (sector != null && sector!.trim().isEmpty) {
      throw ArgumentError.value(sector, 'sector', 'cannot be blank');
    }
    return {
      'state': state?.apiValue,
      'sector': sector?.trim(),
      'uncertainty': uncertainty?.apiValue,
      'minRegimeFit': minRegimeFit?.toString(),
      'minLiquidityPercentile': minLiquidityPercentile?.toString(),
      'sort': sort.apiValue,
      'limit': '$limit',
    };
  }
}

class AiScannerSnapshot {
  AiScannerSnapshot({
    required this.contractVersion,
    required this.generatedAt,
    required this.asOf,
    required this.universeId,
    required this.universeVersion,
    required this.sourceStatus,
    required this.sourceCount,
    required this.filteredCount,
    required this.eligibleCount,
    required this.zeroEligibleTrades,
    required List<AiScannerOpportunity> opportunities,
    required this.canAuthorizeCapital,
  }) : opportunities = List.unmodifiable(opportunities) {
    if (contractVersion != 'ai-scanner-snapshot.v1') {
      throw FormatException('Unsupported AI scanner snapshot: $contractVersion');
    }
    if (sourceCount < 0 || filteredCount < 0 || eligibleCount < 0) {
      throw const FormatException('AI scanner counts cannot be negative');
    }
    if (eligibleCount > filteredCount || filteredCount > sourceCount) {
      throw const FormatException('AI scanner counts are inconsistent');
    }
    if (zeroEligibleTrades != (eligibleCount == 0)) {
      throw const FormatException(
        'zero_eligible_trades must match eligible_count',
      );
    }
    if (canAuthorizeCapital) {
      throw const FormatException('AI scanner cannot authorize capital');
    }
    if (sourceStatus == AiScannerSourceStatus.noRankedData &&
        (sourceCount != 0 || opportunities.isNotEmpty)) {
      throw const FormatException(
        'NO_RANKED_DATA cannot include ranked opportunities',
      );
    }
  }

  factory AiScannerSnapshot.fromJson(Map<String, dynamic> json) {
    final opportunities = _readList(json, 'opportunities')
        .map((value) => AiScannerOpportunity.fromJson(_asMap(value, 'opportunity')))
        .toList(growable: false);
    return AiScannerSnapshot(
      contractVersion: _readString(json, 'contract_version'),
      generatedAt: _readDateTime(json, 'generated_at'),
      asOf: _readNullableDateTime(json, 'as_of'),
      universeId: _readNullableString(json, 'universe_id'),
      universeVersion: _readNullableString(json, 'universe_version'),
      sourceStatus: AiScannerSourceStatus.parse(
        _readString(json, 'source_status'),
      ),
      sourceCount: _readInt(json, 'source_count'),
      filteredCount: _readInt(json, 'filtered_count'),
      eligibleCount: _readInt(json, 'eligible_count'),
      zeroEligibleTrades: _readBool(json, 'zero_eligible_trades'),
      opportunities: opportunities,
      canAuthorizeCapital: _readBool(json, 'can_authorize_capital'),
    );
  }

  final String contractVersion;
  final DateTime generatedAt;
  final DateTime? asOf;
  final String? universeId;
  final String? universeVersion;
  final AiScannerSourceStatus sourceStatus;
  final int sourceCount;
  final int filteredCount;
  final int eligibleCount;
  final bool zeroEligibleTrades;
  final List<AiScannerOpportunity> opportunities;
  final bool canAuthorizeCapital;
}

class AiScannerOpportunity {
  AiScannerOpportunity({
    required this.instrumentId,
    required this.symbol,
    required this.state,
    required this.direction,
    required this.rank,
    required this.percentile,
    required this.sectorRank,
    required this.liquidityRank,
    required this.sector,
    required this.calibratedProbability,
    required this.expectedNetEdgeBps,
    required this.estimatedRoundTripCostBps,
    required this.uncertaintyScore,
    required this.uncertaintyLevel,
    required this.disagreementScore,
    required this.regime,
    required this.regimeFit,
    required this.advNotional,
    required this.liquidityPercentile,
    required this.validUntil,
    required this.timeToExpirySeconds,
    required this.riskRewardInputs,
    required List<String> reasonCodes,
    required List<String> invalidationConditions,
    required this.opportunityFingerprint,
    required this.canAuthorizeCapital,
  })  : reasonCodes = List.unmodifiable(reasonCodes),
        invalidationConditions = List.unmodifiable(invalidationConditions) {
    if (rank < 1 || sectorRank < 1 || liquidityRank < 1) {
      throw const FormatException('AI scanner ranks must be positive');
    }
    _requireUnitInterval(percentile, 'percentile');
    _requireUnitInterval(uncertaintyScore, 'uncertainty_score');
    _requireUnitInterval(disagreementScore, 'disagreement_score');
    _requireUnitInterval(regimeFit, 'regime_fit');
    _requireUnitInterval(liquidityPercentile, 'liquidity_percentile');
    if (calibratedProbability != null) {
      _requireUnitInterval(
        calibratedProbability!,
        'calibrated_probability',
      );
    }
    if (state == AiScannerState.abstain && calibratedProbability != null) {
      throw const FormatException(
        'ABSTAIN must withhold calibrated probability',
      );
    }
    if (!expectedNetEdgeBps.isFinite ||
        !estimatedRoundTripCostBps.isFinite ||
        estimatedRoundTripCostBps < 0 ||
        !advNotional.isFinite ||
        advNotional <= 0) {
      throw const FormatException('AI scanner numeric evidence is invalid');
    }
    if (timeToExpirySeconds < 0) {
      throw const FormatException('time_to_expiry_seconds cannot be negative');
    }
    if (!_isSha256(opportunityFingerprint)) {
      throw const FormatException('opportunity_fingerprint must be SHA-256 hex');
    }
    if (canAuthorizeCapital) {
      throw const FormatException('AI scanner opportunity cannot authorize capital');
    }
  }

  factory AiScannerOpportunity.fromJson(Map<String, dynamic> json) {
    final probability = json['calibrated_probability'];
    return AiScannerOpportunity(
      instrumentId: _readString(json, 'instrument_id'),
      symbol: _readString(json, 'symbol'),
      state: AiScannerState.parse(_readString(json, 'state')),
      direction: _readString(json, 'direction'),
      rank: _readInt(json, 'rank'),
      percentile: _readDouble(json, 'percentile'),
      sectorRank: _readInt(json, 'sector_rank'),
      liquidityRank: _readInt(json, 'liquidity_rank'),
      sector: _readString(json, 'sector'),
      calibratedProbability:
          probability == null ? null : _asDouble(probability, 'calibrated_probability'),
      expectedNetEdgeBps: _readDouble(json, 'expected_net_edge_bps'),
      estimatedRoundTripCostBps:
          _readDouble(json, 'estimated_round_trip_cost_bps'),
      uncertaintyScore: _readDouble(json, 'uncertainty_score'),
      uncertaintyLevel: _readString(json, 'uncertainty_level'),
      disagreementScore: _readDouble(json, 'disagreement_score'),
      regime: _readString(json, 'regime'),
      regimeFit: _readDouble(json, 'regime_fit'),
      advNotional: _readDouble(json, 'adv_notional'),
      liquidityPercentile: _readDouble(json, 'liquidity_percentile'),
      validUntil: _readDateTime(json, 'valid_until'),
      timeToExpirySeconds: _readInt(json, 'time_to_expiry_seconds'),
      riskRewardInputs: AiScannerRiskRewardInputs.fromJson(
        _readMap(json, 'risk_reward_inputs'),
      ),
      reasonCodes: _readStringList(json, 'reason_codes'),
      invalidationConditions:
          _readStringList(json, 'invalidation_conditions'),
      opportunityFingerprint: _readString(json, 'opportunity_fingerprint'),
      canAuthorizeCapital: _readBool(json, 'can_authorize_capital'),
    );
  }

  final String instrumentId;
  final String symbol;
  final AiScannerState state;
  final String direction;
  final int rank;
  final double percentile;
  final int sectorRank;
  final int liquidityRank;
  final String sector;
  final double? calibratedProbability;
  final double expectedNetEdgeBps;
  final double estimatedRoundTripCostBps;
  final double uncertaintyScore;
  final String uncertaintyLevel;
  final double disagreementScore;
  final String regime;
  final double regimeFit;
  final double advNotional;
  final double liquidityPercentile;
  final DateTime validUntil;
  final int timeToExpirySeconds;
  final AiScannerRiskRewardInputs riskRewardInputs;
  final List<String> reasonCodes;
  final List<String> invalidationConditions;
  final String opportunityFingerprint;
  final bool canAuthorizeCapital;
}

class AiScannerRiskRewardInputs {
  const AiScannerRiskRewardInputs({
    required this.upperBarrierBps,
    required this.lowerBarrierBps,
    required this.horizonBars,
    required this.rewardRiskRatio,
  });

  factory AiScannerRiskRewardInputs.fromJson(Map<String, dynamic> json) {
    final result = AiScannerRiskRewardInputs(
      upperBarrierBps: _readDouble(json, 'upper_barrier_bps'),
      lowerBarrierBps: _readDouble(json, 'lower_barrier_bps'),
      horizonBars: _readInt(json, 'horizon_bars'),
      rewardRiskRatio: _readDouble(json, 'reward_risk_ratio'),
    );
    if (result.upperBarrierBps <= 0 ||
        result.lowerBarrierBps <= 0 ||
        result.horizonBars < 1) {
      throw const FormatException('Invalid risk/reward barrier inputs');
    }
    final expected = result.upperBarrierBps / result.lowerBarrierBps;
    if ((result.rewardRiskRatio - expected).abs() > 1e-12) {
      throw const FormatException('reward_risk_ratio does not match barriers');
    }
    return result;
  }

  final double upperBarrierBps;
  final double lowerBarrierBps;
  final int horizonBars;
  final double rewardRiskRatio;
}

abstract interface class AiScannerRepository {
  Future<AiScannerSnapshot> scan({AiScannerQuery query = const AiScannerQuery()});
}

class HttpAiScannerRepository implements AiScannerRepository {
  HttpAiScannerRepository(this.api);

  final AuthenticatedApiClient api;

  @override
  Future<AiScannerSnapshot> scan({AiScannerQuery query = const AiScannerQuery()}) async {
    final json = await api.getJson(
      '/api/ai/scanner',
      query: query.toQueryParameters(),
    );
    if (json is! Map<String, dynamic>) {
      throw const FormatException('AI scanner response must be a JSON object');
    }
    return AiScannerSnapshot.fromJson(json);
  }
}

void _validateOptionalUnitInterval(double? value, String field) {
  if (value != null && (!value.isFinite || value < 0 || value > 1)) {
    throw ArgumentError.value(value, field, 'must be within [0, 1]');
  }
}

void _requireUnitInterval(double value, String field) {
  if (!value.isFinite || value < 0 || value > 1) {
    throw FormatException('$field must be within [0, 1]');
  }
}

bool _isSha256(String value) => RegExp(r'^[a-f0-9]{64}$').hasMatch(value);

Map<String, dynamic> _readMap(Map<String, dynamic> json, String key) =>
    _asMap(json[key], key);

Map<String, dynamic> _asMap(Object? value, String key) {
  if (value is Map<String, dynamic>) return value;
  throw FormatException('$key must be a JSON object');
}

List<dynamic> _readList(Map<String, dynamic> json, String key) {
  final value = json[key];
  if (value is List<dynamic>) return value;
  throw FormatException('$key must be a JSON array');
}

String _readString(Map<String, dynamic> json, String key) {
  final value = json[key];
  if (value is String && value.isNotEmpty) return value;
  throw FormatException('$key must be a non-empty string');
}

String? _readNullableString(Map<String, dynamic> json, String key) {
  final value = json[key];
  if (value == null) return null;
  if (value is String && value.isNotEmpty) return value;
  throw FormatException('$key must be null or a non-empty string');
}

int _readInt(Map<String, dynamic> json, String key) {
  final value = json[key];
  if (value is int) return value;
  throw FormatException('$key must be an integer');
}

double _readDouble(Map<String, dynamic> json, String key) =>
    _asDouble(json[key], key);

double _asDouble(Object? value, String key) {
  if (value is num) return value.toDouble();
  throw FormatException('$key must be numeric');
}

bool _readBool(Map<String, dynamic> json, String key) {
  final value = json[key];
  if (value is bool) return value;
  throw FormatException('$key must be boolean');
}

DateTime _readDateTime(Map<String, dynamic> json, String key) {
  final value = _readString(json, key);
  final parsed = DateTime.tryParse(value);
  if (parsed == null || !parsed.isUtc) {
    throw FormatException('$key must be an ISO-8601 UTC timestamp');
  }
  return parsed;
}

DateTime? _readNullableDateTime(Map<String, dynamic> json, String key) {
  if (json[key] == null) return null;
  return _readDateTime(json, key);
}

List<String> _readStringList(Map<String, dynamic> json, String key) {
  final values = _readList(json, key);
  return values.map((value) {
    if (value is! String || value.isEmpty) {
      throw FormatException('$key must contain non-empty strings');
    }
    return value;
  }).toList(growable: false);
}

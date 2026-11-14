import '../../../data/authenticated_api_client.dart';

/// Read-only history of persisted research candidates. Every record is research
/// evidence only: no order, no execution and no live money, enforced on decode.
abstract interface class MindDecisionHistoryRepository {
  Future<MindDecisionHistory> load();
}

class MindDecisionRecord {
  const MindDecisionRecord({
    required this.scanRunId,
    required this.runCompletedAt,
    required this.strategy,
    required this.strategyVersion,
    required this.catalogueVersion,
    required this.tradingSymbol,
    required this.exchange,
    required this.interval,
    required this.signalCandleTime,
    required this.dataCutoffAt,
    required this.candidateFingerprint,
    required this.reasons,
  });

  final String scanRunId;
  final DateTime runCompletedAt;
  final String strategy;
  final int? strategyVersion;
  final String? catalogueVersion;
  final String tradingSymbol;
  final String exchange;
  final String interval;
  final DateTime signalCandleTime;
  final DateTime dataCutoffAt;
  final String candidateFingerprint;
  final List<String> reasons;

  static MindDecisionRecord decode(Object? value) {
    if (value is! Map<String, dynamic>) throw const FormatException('Invalid decision record');
    final signal = _time(value['signalCandleTime']);
    final cutoff = _time(value['dataCutoffAt']);
    final completed = _time(value['runCompletedAt']);
    final strategy = value['strategy'];
    final symbol = value['tradingSymbol'];
    final exchange = value['exchange'];
    final interval = value['interval'];
    final fingerprint = value['candidateFingerprint'];
    final run = value['scanRunId'];
    final version = value['strategyVersion'];
    final reasons = value['reasons'];
    if (strategy is! String || strategy.isEmpty || symbol is! String || symbol.isEmpty ||
        exchange is! String || interval is! String || fingerprint is! String || fingerprint.isEmpty ||
        run is! String || (version != null && version is! int) ||
        reasons is! List || reasons.any((r) => r is! String) ||
        value['decisionPosture'] != 'RECORDED_CANDIDATE' ||
        value['paperOrderSubmitted'] != false || value['executionEnabled'] != false ||
        value['liveMoney'] != false ||
        // Future-informed lineage is rejected, never displayed.
        cutoff.isBefore(signal)) {
      throw const FormatException('Untrustworthy decision record');
    }
    final catalogue = value['catalogueVersion'];
    return MindDecisionRecord(
      scanRunId: run,
      runCompletedAt: completed,
      strategy: strategy,
      strategyVersion: version as int?,
      catalogueVersion: catalogue is String ? catalogue : null,
      tradingSymbol: symbol,
      exchange: exchange,
      interval: interval,
      signalCandleTime: signal,
      dataCutoffAt: cutoff,
      candidateFingerprint: fingerprint,
      reasons: List.unmodifiable(reasons.cast<String>()),
    );
  }

  static DateTime _time(Object? value) {
    final parsed = value is String ? DateTime.tryParse(value) : null;
    if (parsed == null) throw const FormatException('Invalid decision timestamp');
    return parsed.toUtc();
  }
}

class MindDecisionHistory {
  const MindDecisionHistory({
    required this.generatedAt,
    required this.decisions,
    required this.limitations,
  });

  final DateTime generatedAt;
  final List<MindDecisionRecord> decisions;
  final List<String> limitations;

  static const contract = 'mind.decision-history.v1';

  static MindDecisionHistory decode(Object? value) {
    if (value is! Map<String, dynamic> || value['contract'] != contract) {
      throw const FormatException('Unknown decision history contract');
    }
    final generated = DateTime.tryParse(value['generatedAt']?.toString() ?? '');
    final rows = value['decisions'];
    final limits = value['limitations'];
    if (generated == null || rows is! List || limits is! List || limits.any((l) => l is! String)) {
      throw const FormatException('Untrustworthy decision history');
    }
    return MindDecisionHistory(
      generatedAt: generated.toUtc(),
      decisions: rows.map(MindDecisionRecord.decode).toList(growable: false),
      limitations: List.unmodifiable(limits.cast<String>()),
    );
  }
}

class HttpMindDecisionHistoryRepository implements MindDecisionHistoryRepository {
  const HttpMindDecisionHistoryRepository(this.api);

  final AuthenticatedApiClient api;

  @override
  Future<MindDecisionHistory> load() async =>
      MindDecisionHistory.decode(await api.getJson('/api/customer/mind/v1/decision-history'));
}

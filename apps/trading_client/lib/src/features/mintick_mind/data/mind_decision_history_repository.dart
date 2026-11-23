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
    this.news,
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

  /// Advisory as-of news context recorded at scan time. Null means it was not recorded for
  /// this candidate, which is never shown as "no news".
  final MindNewsContext? news;

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
      news: MindNewsContext.decode(value['news']),
    );
  }

  static DateTime _time(Object? value) {
    final parsed = value is String ? DateTime.tryParse(value) : null;
    if (parsed == null) throw const FormatException('Invalid decision timestamp');
    return parsed.toUtc();
  }
}

/// Coarse status of one as-of news context. `untrustworthy` means the payload failed strict
/// validation: it is shown as rejected, never as evidence.
enum MindNewsStatus {
  includedEvidence,
  noIncludedEvidence,
  noCoverage,
  unavailable,
  untrustworthy,
}

class MindNewsEvidence {
  const MindNewsEvidence({
    required this.source,
    required this.title,
    required this.url,
    required this.publishedAt,
    required this.analysisAvailableAt,
    required this.validUntil,
    required this.horizon,
    required this.status,
    required this.included,
    this.sentiment,
    this.directionScore,
    this.confidence,
  });

  final String source;
  final String title;
  final String url;
  final DateTime publishedAt;
  final DateTime analysisAvailableAt;
  final DateTime validUntil;
  final String horizon;
  final String status;
  final bool included;
  final String? sentiment;
  final double? directionScore;
  final double? confidence;
}

class MindNewsContext {
  const MindNewsContext({
    required this.status,
    required this.asOf,
    required this.netDirection,
    required this.direction,
    required this.uncertainty,
    required this.includedCount,
    required this.excludedCount,
    required this.evidence,
  });

  static const contract = 'news.context.v1';
  static const _directions = {'POSITIVE', 'NEGATIVE', 'NEUTRAL', 'UNKNOWN'};
  static const _uncertainties = {'LOW', 'MEDIUM', 'HIGH', 'UNKNOWN'};
  static const _statuses = {
    'INCLUDED_EVIDENCE': MindNewsStatus.includedEvidence,
    'NO_INCLUDED_EVIDENCE': MindNewsStatus.noIncludedEvidence,
    'NO_COVERAGE': MindNewsStatus.noCoverage,
    'UNAVAILABLE': MindNewsStatus.unavailable,
  };

  final MindNewsStatus status;
  final DateTime? asOf;
  /// Confidence- and materiality-weighted evidence direction in [-1, 1]; null without evidence.
  final double? netDirection;
  final String direction;
  final String uncertainty;
  final int includedCount;
  final int excludedCount;
  final List<MindNewsEvidence> evidence;

  /// Null when the field is absent (not recorded). Malformed input is `untrustworthy`.
  static MindNewsContext? decode(Object? value) {
    if (value == null) return null;
    try {
      return _strict(value);
    } catch (_) {
      return const MindNewsContext(
        status: MindNewsStatus.untrustworthy,
        asOf: null,
        netDirection: null,
        direction: 'UNKNOWN',
        uncertainty: 'UNKNOWN',
        includedCount: 0,
        excludedCount: 0,
        evidence: [],
      );
    }
  }

  static MindNewsContext _strict(Object value) {
    if (value is! Map<String, dynamic> || value['contract'] != contract) {
      throw const FormatException('Unknown news contract');
    }
    final status = _statuses[value['status']];
    final direction = value['direction'];
    final uncertainty = value['uncertainty'];
    final net = value['netDirection'];
    final included = value['includedCount'];
    final excluded = value['excludedCount'];
    final rows = value['evidence'];
    final asOf = DateTime.tryParse(value['asOf']?.toString() ?? '');
    if (status == null || asOf == null || direction is! String || !_directions.contains(direction) ||
        uncertainty is! String || !_uncertainties.contains(uncertainty) ||
        included is! int || excluded is! int || included < 0 || excluded < 0 ||
        rows is! List || (net != null && net is! num) ||
        (net is num && (net < -1 || net > 1))) {
      throw const FormatException('Untrustworthy news context');
    }
    final hasNet = net is num;
    final netValue = hasNet ? net.toDouble() : null;
    final utcAsOf = asOf.toUtc();
    // Fail closed on every status/count/net/direction inconsistency. Each status has one legal shape.
    switch (status) {
      case MindNewsStatus.noCoverage:
      case MindNewsStatus.unavailable:
        // Nothing was known: no counts, no net, no evidence, never a directional label.
        if (included != 0 || excluded != 0 || hasNet || direction != 'UNKNOWN' ||
            uncertainty != 'UNKNOWN' || rows.isNotEmpty) {
          throw const FormatException('Coverage-free status carries evidence');
        }
      case MindNewsStatus.noIncludedEvidence:
        // Evidence was found but none was admitted: no net, no direction, and at least one excluded item.
        if (included != 0 || hasNet || direction != 'UNKNOWN' || rows.isEmpty) {
          throw const FormatException('No-included status is inconsistent');
        }
      case MindNewsStatus.includedEvidence:
        // A numeric net and a non-UNKNOWN label must come together, and the label must match the net band.
        if (included < 1 || netValue == null || direction == 'UNKNOWN' || uncertainty == 'UNKNOWN') {
          throw const FormatException('Included status is inconsistent');
        }
        final expected = netValue > 0.20 ? 'POSITIVE' : netValue < -0.20 ? 'NEGATIVE' : 'NEUTRAL';
        if (direction != expected) throw const FormatException('Direction contradicts net');
      case MindNewsStatus.untrustworthy:
        throw const FormatException('Untrustworthy status');
    }
    final evidence = List<MindNewsEvidence>.unmodifiable(rows.map((r) => _evidence(r, utcAsOf)));
    // Included rows can never outnumber the included count.
    if (evidence.where((e) => e.included).length > included) {
      throw const FormatException('Included rows exceed included count');
    }
    return MindNewsContext(
      status: status,
      asOf: utcAsOf,
      netDirection: netValue,
      direction: direction,
      uncertainty: uncertainty,
      includedCount: included,
      excludedCount: excluded,
      evidence: evidence,
    );
  }

  static const _reasonCodes = {
    'INCLUDED',
    'EXCLUDED_PUBLISHED_AFTER_AS_OF',
    'EXCLUDED_ANALYSIS_NOT_YET_AVAILABLE',
    'EXCLUDED_UNVERIFIED_INSTRUMENT',
    'EXCLUDED_ANALYSIS_FAILED',
    'EXCLUDED_EXPIRED',
    'EXCLUDED_LOW_CONFIDENCE',
    'EXCLUDED_INVALID_SCORE',
    'EXCLUDED_UNCERTAIN',
  };

  static MindNewsEvidence _evidence(Object? value, DateTime asOf) {
    if (value is! Map<String, dynamic>) throw const FormatException('Invalid news evidence');
    final source = value['source'];
    final title = value['title'];
    final url = value['url'];
    final horizon = value['horizon'];
    final status = value['status'];
    final included = value['included'];
    final published = DateTime.tryParse(value['publishedAt']?.toString() ?? '');
    final available = DateTime.tryParse(value['analysisAvailableAt']?.toString() ?? '');
    final validUntil = DateTime.tryParse(value['validUntil']?.toString() ?? '');
    final score = value['directionScore'];
    final confidence = value['confidence'];
    if (source is! String || title is! String || url is! String || horizon is! String ||
        status is! String || included is! bool || published == null || available == null ||
        validUntil == null || (score != null && score is! num) || (confidence != null && confidence is! num)) {
      throw const FormatException('Invalid news evidence');
    }
    if (!_reasonCodes.contains(status)) throw const FormatException('Unknown evidence reason');
    if (included) {
      // Included evidence must be knowable at asOf and still valid there.
      if (status != 'INCLUDED' || published.toUtc().isAfter(asOf) || available.toUtc().isAfter(asOf) ||
          !asOf.isBefore(validUntil.toUtc())) {
        throw const FormatException('Included evidence violates as-of or validity');
      }
    } else if (status == 'INCLUDED') {
      throw const FormatException('Excluded evidence marked INCLUDED');
    }
    return MindNewsEvidence(
      source: source,
      title: title,
      url: url,
      publishedAt: published.toUtc(),
      analysisAvailableAt: available.toUtc(),
      validUntil: validUntil.toUtc(),
      horizon: horizon,
      status: status,
      included: included,
      sentiment: value['sentiment'] is String ? value['sentiment'] as String : null,
      directionScore: score is num ? score.toDouble() : null,
      confidence: confidence is num ? confidence.toDouble() : null,
    );
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

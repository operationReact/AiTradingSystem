import '../../../data/authenticated_api_client.dart';

/// Read-only deterministic replay of an intraday opening-range candidate.
class MindOpeningRangeReplay {
  const MindOpeningRangeReplay({
    required this.generatedAt,
    required this.strategyVersion,
    required this.replayStatus,
    required this.decisionPosture,
    required this.blockingReasons,
    required this.paperShadowStatus,
    this.instrument,
    this.interval,
    this.signalAt,
    this.dataCutoffAt,
    this.fingerprint,
  });

  final DateTime generatedAt;
  final String strategyVersion;
  final String? instrument;
  final String? interval;
  final DateTime? signalAt;
  final DateTime? dataCutoffAt;
  final String? fingerprint;
  final String replayStatus;
  final String decisionPosture;
  final List<String> blockingReasons;
  final String paperShadowStatus;

  static const _statuses = {
    'UNAVAILABLE', 'INVALID', 'NO_MATCH', 'REPRODUCED', 'STALE'
  };
  static const _intervals = {
    'ONE_MINUTE', 'THREE_MINUTE', 'FIVE_MINUTE'
  };

  factory MindOpeningRangeReplay.fromJson(Object? source) {
    if (source is! Map<String, dynamic> ||
        source['contractVersion'] != 'mind.opening-range-replay.v1' ||
        source['strategyCode'] != 'OPENING_RANGE_BREAKOUT_V1' ||
        source['strategyVersion'] != '1' ||
        source['executionEnabled'] != false ||
        source['paperOrderSubmitted'] != false ||
        source['independentlyVerifiedSignal'] != false ||
        source['paperShadowStatus'] != 'NOT_ENROLLED' ||
        source['replayStatus'] is! String ||
        !_statuses.contains(source['replayStatus'])) {
      throw const FormatException('Unsafe opening-range replay contract');
    }
    final status = source['replayStatus'] as String;
    final posture = source['decisionPosture'];
    if (posture != 'WAIT' && posture != 'ABSTAIN') {
      throw const FormatException('Unapproved opening-range posture');
    }
    if (posture == 'WAIT' && status != 'REPRODUCED') {
      throw const FormatException('WAIT requires reproducible opening-range evidence');
    }

    final rawTime = source['generatedAt'];
    final generatedAt = rawTime is String ? DateTime.tryParse(rawTime) : null;
    final rawReasons = source['blockingReasons'];
    if (generatedAt == null || rawReasons is! List) {
      throw const FormatException('Missing opening-range replay lineage');
    }
    final reasons = rawReasons.map((value) => value is String && value.isNotEmpty
        ? value
        : throw const FormatException('Invalid opening-range gate'))
        .toList(growable: false);
    if (!reasons.contains('ACCOUNT_RISK_NOT_EVALUATED') ||
        !reasons.contains('PAPER_SHADOW_ENROLLMENT_REQUIRED')) {
      throw const FormatException('Missing opening-range capital safety gates');
    }

    final rawSignal = source['signalAt'];
    final rawCutoff = source['dataCutoffAt'];
    final signal = rawSignal is String ? DateTime.tryParse(rawSignal) : null;
    final cutoff = rawCutoff is String ? DateTime.tryParse(rawCutoff) : null;
    final instrument = source['instrument'];
    final interval = source['interval'];
    final fingerprint = source['candidateFingerprint'];
    if ((rawSignal != null && signal == null) ||
        (rawCutoff != null && cutoff == null) ||
        (signal != null && signal.isAfter(generatedAt)) ||
        (cutoff != null && cutoff.isAfter(generatedAt)) ||
        (signal != null && cutoff != signal) ||
        (instrument != null &&
            (instrument is! String ||
             !RegExp(r'^(NSE|BSE):[A-Z0-9._-]+-EQ$').hasMatch(instrument))) ||
        (interval != null && !_intervals.contains(interval)) ||
        (fingerprint != null &&
            (fingerprint is! String ||
             !RegExp(r'^[a-f0-9]{64}$').hasMatch(fingerprint)))) {
      throw const FormatException('Unverified opening-range source identity');
    }
    if (posture == 'WAIT' &&
        (signal == null || cutoff == null || instrument == null ||
         interval == null || fingerprint == null ||
         !reasons.contains('STRUCTURAL_REPLAY_ONLY'))) {
      throw const FormatException('WAIT missing bounded opening-range replay');
    }
    return MindOpeningRangeReplay(
      generatedAt: generatedAt,
      strategyVersion: source['strategyVersion'] as String,
      replayStatus: status,
      decisionPosture: posture as String,
      blockingReasons: reasons,
      paperShadowStatus: source['paperShadowStatus'] as String,
      instrument: instrument as String?,
      interval: interval as String?,
      signalAt: signal,
      dataCutoffAt: cutoff,
      fingerprint: fingerprint as String?,
    );
  }
}

abstract interface class MindOpeningRangeReplayRepository {
  Future<MindOpeningRangeReplay> load();
}

class HttpMindOpeningRangeReplayRepository
    implements MindOpeningRangeReplayRepository {
  const HttpMindOpeningRangeReplayRepository(this.api);
  final AuthenticatedApiClient api;

  @override
  Future<MindOpeningRangeReplay> load() async =>
      MindOpeningRangeReplay.fromJson(
        await api.getJson('/api/customer/mind/v1/opening-range-replay'));
}

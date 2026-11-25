import '../../../data/authenticated_api_client.dart';

/// Read-only historical reconstruction. It cannot approve risk, paper
/// orders, live broker execution, or production model promotion.
class MindStrategyReplay {
  const MindStrategyReplay({
    required this.generatedAt,
    required this.strategyCode,
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
  final String strategyCode;
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

  factory MindStrategyReplay.fromJson(Object? source) {
    if (source is! Map<String, dynamic> ||
        source['contractVersion'] != 'mind.strategy-replay.v1' ||
        source['strategyCode'] != 'MA_RECLAIM_V1' ||
        source['strategyVersion'] != '1' ||
        source['executionEnabled'] != false ||
        source['paperOrderSubmitted'] != false ||
        source['independentlyVerifiedSignal'] != false ||
        source['paperShadowStatus'] != 'NOT_ENROLLED' ||
        source['replayStatus'] is! String ||
        !_statuses.contains(source['replayStatus'])) {
      throw const FormatException('Unsafe Mind strategy replay contract');
    }

    final status = source['replayStatus'] as String;
    final posture = source['decisionPosture'];
    if (posture != 'WAIT' && posture != 'ABSTAIN') {
      throw const FormatException('Unapproved strategy posture');
    }
    if (posture == 'WAIT' && status != 'REPRODUCED') {
      throw const FormatException('WAIT requires reproducible strategy evidence');
    }
    final rawTime = source['generatedAt'];
    final at = rawTime is String ? DateTime.tryParse(rawTime) : null;
    if (at == null || source['blockingReasons'] is! List) {
      throw const FormatException('Missing replay lineage');
    }
    final blockers = (source['blockingReasons'] as List)
        .map((value) => value is String && value.isNotEmpty
          ? value : throw const FormatException('Invalid replay gate'))
        .toList(growable: false);
    if (!blockers.contains('ACCOUNT_RISK_NOT_EVALUATED') ||
        !blockers.contains('PAPER_SHADOW_ENROLLMENT_REQUIRED')) {
      throw const FormatException('Missing capital safety gates');
    }

    final rawSignal = source['signalAt'];
    final rawCutoff = source['dataCutoffAt'];
    final signal = rawSignal is String ? DateTime.tryParse(rawSignal) : null;
    final cutoff = rawCutoff is String ? DateTime.tryParse(rawCutoff) : null;
    final instrument = source['instrument'];
    final interval = source['interval'];
    final fp = source['candidateFingerprint'];
    if ((rawSignal != null && signal == null) ||
        (rawCutoff != null && cutoff == null) ||
        (signal != null && signal.isAfter(at)) ||
        (cutoff != null && cutoff.isAfter(at)) ||
        (signal != null && cutoff != signal) ||
        (instrument != null && (instrument is! String ||
            !RegExp(r'^(NSE|BSE):[A-Z0-9._-]+-EQ$').hasMatch(instrument))) ||
        (interval != null && interval != 'FIVE_MINUTE') ||
        (fp != null && (fp is! String ||
            !RegExp(r'^[a-f0-9]{64}$').hasMatch(fp)))) {
      throw const FormatException('Unverified research source identity');
    }
    if (posture == 'WAIT' &&
        (signal == null || cutoff == null || instrument == null ||
         interval == null || fp == null ||
         !blockers.contains('STRUCTURAL_REPLAY_ONLY'))) {
      throw const FormatException('WAIT missing bounded replay');
    }

    return MindStrategyReplay(
      generatedAt: at,
      strategyCode: source['strategyCode'] as String,
      strategyVersion: source['strategyVersion'] as String,
      replayStatus: status,
      decisionPosture: posture as String,
      blockingReasons: blockers,
      paperShadowStatus: source['paperShadowStatus'] as String,
      instrument: instrument as String?,
      interval: interval as String?,
      signalAt: signal,
      dataCutoffAt: cutoff,
      fingerprint: fp as String?,
    );
  }
}

abstract interface class MindStrategyReplayRepository {
  Future<MindStrategyReplay> load();
}

class HttpMindStrategyReplayRepository implements MindStrategyReplayRepository {
  const HttpMindStrategyReplayRepository(this.api);
  final AuthenticatedApiClient api;

  @override
  Future<MindStrategyReplay> load() async => MindStrategyReplay.fromJson(
    await api.getJson('/api/customer/mind/v1/strategy-replay'));
}

import '../../../data/authenticated_api_client.dart';

/// Forward-only cohort observation, never verified strategy performance.
class MindProspectiveMember {
  const MindProspectiveMember({
    required this.slot, required this.symbol, required this.validCandles,
    required this.expectedCandles, required this.coveragePercent,
    required this.state,
  });

  final int slot;
  final String symbol;
  final int validCandles;
  final int expectedCandles;
  final num coveragePercent;
  final String state;

  factory MindProspectiveMember.fromJson(Object? raw) {
    if (raw is! Map<String, dynamic> ||
        raw['slot'] is! int || (raw['slot'] as int) < 1 ||
        (raw['slot'] as int) > 20 ||
        raw['exchange'] != 'NSE' ||
        raw['symbol'] is! String ||
        !RegExp(r'^[A-Z0-9._&-]+-EQ$').hasMatch(raw['symbol'] as String) ||
        raw['validCandles'] is! int || (raw['validCandles'] as int) < 0 ||
        raw['expectedCandles'] is! int || (raw['expectedCandles'] as int) < 0 ||
        (raw['validCandles'] as int) > (raw['expectedCandles'] as int) ||
        raw['coveragePercent'] is! num ||
        !(raw['coveragePercent'] as num).toDouble().isFinite ||
        (raw['coveragePercent'] as num) < 0 ||
        (raw['coveragePercent'] as num) > 100 ||
        !const {'OBSERVATION_PENDING', 'SOURCE_COVERAGE_RECORDED',
          'INSUFFICIENT_SOURCE_COVERAGE'}.contains(raw['state'])) {
      throw const FormatException('Invalid prospective member');
    }
    return MindProspectiveMember(
      slot: raw['slot'] as int,
      symbol: raw['symbol'] as String,
      validCandles: raw['validCandles'] as int,
      expectedCandles: raw['expectedCandles'] as int,
      coveragePercent: raw['coveragePercent'] as num,
      state: raw['state'] as String,
    );
  }
}

class MindProspectiveCohort {
  const MindProspectiveCohort({
    required this.generatedAt, required this.sourceStatus,
    required this.completedSessions, required this.targetSessions,
    required this.memberCount, required this.readyMembers,
    required this.members, required this.blockers,
    this.registeredAt, this.observationStartsAt, this.maturityAt,
  });

  final DateTime generatedAt;
  final String sourceStatus;
  final DateTime? registeredAt;
  final DateTime? observationStartsAt;
  final DateTime? maturityAt;
  final int completedSessions;
  final int targetSessions;
  final int memberCount;
  final int readyMembers;
  final List<MindProspectiveMember> members;
  final List<String> blockers;

  factory MindProspectiveCohort.fromJson(Object? raw) {
    if (raw is! Map<String, dynamic> ||
        raw['contractVersion'] != 'mind.prospective-cohort.v1' ||
        raw['strategyCode'] != 'MA_RECLAIM_V1' ||
        raw['selectionMethod'] !=
            'DETERMINISTIC_CATALOGUE_HASH_NO_SIGNAL_RANKING' ||
        raw['decisionPosture'] != 'ABSTAIN' ||
        raw['outcomeValidated'] != false ||
        raw['paperOrderSubmitted'] != false ||
        raw['executionEnabled'] != false) {
      throw const FormatException('Unsafe prospective research contract');
    }
    final status = raw['sourceStatus'];
    if (!const {'UNREGISTERED', 'UNAVAILABLE', 'AWAITING_FUTURE_SESSION',
      'COLLECTING', 'INSUFFICIENT_COVERAGE',
      'SOURCE_COVERAGE_READY_RESEARCH_ONLY'}.contains(status)) {
      throw const FormatException('Unknown prospective state');
    }
    final generated = _timestamp(raw['generatedAt']);
    final registered = _timestamp(raw['registeredAt']);
    final start = _timestamp(raw['observationStartsAt']);
    final mature = _timestamp(raw['maturityAt']);
    final target = raw['targetSessions'];
    final completed = raw['completedSessions'];
    final count = raw['memberCount'];
    final ready = raw['membersWithSufficientCoverage'];
    if (generated == null || target != 10 ||
        completed is! int || completed < 0 || completed > 10 ||
        count is! int || !const {0, 20}.contains(count) ||
        ready is! int || ready < 0 || ready > count ||
        raw['members'] is! List || raw['blockers'] is! List) {
      throw const FormatException('Missing prospective evidence');
    }
    final members = (raw['members'] as List)
        .map(MindProspectiveMember.fromJson).toList(growable: false);
    final blockers = (raw['blockers'] as List).map((item) =>
        item is String && item.isNotEmpty
            ? item : throw const FormatException('Invalid blocker'))
        .toList(growable: false);
    for (final required in const [
      'PROSPECTIVE_RETURNS_NOT_EVALUATED',
      'LIQUIDITY_AND_SURVIVORSHIP_NOT_INDEPENDENTLY_VERIFIED',
      'SOURCE_KNOWN_AT_NOT_INDEPENDENTLY_ATTESTED',
      'BROKER_STATUTORY_COST_MODEL_NOT_COMPLETE',
      'ACCOUNT_RISK_NOT_EVALUATED',
      'PAPER_SHADOW_ENROLLMENT_REQUIRED',
      'LIVE_EXECUTION_DISABLED',
    ]) {
      if (!blockers.contains(required)) {
        throw const FormatException('Missing prospective research safety gate');
      }
    }
    final slots = members.map((m) => m.slot).toSet();
    final symbols = members.map((m) => m.symbol).toSet();
    if (members.length != count || slots.length != count ||
        symbols.length != count ||
        ((status == 'UNREGISTERED' || status == 'UNAVAILABLE') &&
          (registered != null || start != null || mature != null ||
            count != 0 || completed != 0 || ready != 0)) ||
        (status != 'UNREGISTERED' && status != 'UNAVAILABLE' &&
          (registered == null || start == null || mature == null ||
            registered.isAfter(generated) || !start.isAfter(registered) ||
            !mature.isAfter(start) || count != 20)) ||
        (status == 'SOURCE_COVERAGE_READY_RESEARCH_ONLY' &&
          (completed != 10 || ready != 20)) ||
        (status == 'AWAITING_FUTURE_SESSION' && completed != 0)) {
      throw const FormatException('Inconsistent prospective observation status');
    }
    return MindProspectiveCohort(
      generatedAt: generated, sourceStatus: status as String,
      registeredAt: registered, observationStartsAt: start,
      maturityAt: mature, targetSessions: target as int,
      completedSessions: completed,
      memberCount: count, readyMembers: ready, members: members,
      blockers: blockers,
    );
  }
}

DateTime? _timestamp(Object? raw) {
  if (raw == null) return null;
  if (raw is! String) throw const FormatException('Invalid timestamp');
  return DateTime.tryParse(raw) ??
      (throw const FormatException('Invalid timestamp'));
}

abstract interface class MindProspectiveCohortRepository {
  Future<MindProspectiveCohort> load();
}

class HttpMindProspectiveCohortRepository
    implements MindProspectiveCohortRepository {
  const HttpMindProspectiveCohortRepository(this.api);
  final AuthenticatedApiClient api;

  @override
  Future<MindProspectiveCohort> load() async =>
      MindProspectiveCohort.fromJson(
        await api.getJson('/api/customer/mind/v1/prospective-cohort'));
}

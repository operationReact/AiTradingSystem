import '../../../data/authenticated_api_client.dart';

/// Historical research cannot be re-labelled as prospective. This reads
/// immutable receipts recorded near the original bar close, never trades.
class MindProspectiveSignalMember {
  const MindProspectiveSignalMember({
    required this.slot, required this.symbol,
    required this.recordedWindows, required this.researchCandidates,
  });

  final int slot;
  final String symbol;
  final int recordedWindows;
  final int researchCandidates;

  factory MindProspectiveSignalMember.fromJson(Object? raw) {
    if (raw is! Map<String, dynamic> ||
        raw['slot'] is! int || (raw['slot'] as int) < 1 ||
        (raw['slot'] as int) > 20 ||
        raw['symbol'] is! String ||
        !RegExp(r'^[A-Z0-9._&-]+-EQ$').hasMatch(raw['symbol'] as String) ||
        raw['recordedWindows'] is! int ||
        (raw['recordedWindows'] as int) < 0 ||
        raw['researchCandidates'] is! int ||
        (raw['researchCandidates'] as int) < 0 ||
        (raw['researchCandidates'] as int) > (raw['recordedWindows'] as int)) {
      throw const FormatException('Invalid prospective signal member');
    }
    return MindProspectiveSignalMember(
      slot: raw['slot'] as int, symbol: raw['symbol'] as String,
      recordedWindows: raw['recordedWindows'] as int,
      researchCandidates: raw['researchCandidates'] as int,
    );
  }
}

class MindProspectiveSignalsStatus {
  const MindProspectiveSignalsStatus({
    required this.generatedAt, required this.status,
    required this.memberCount, required this.recordedWindows,
    required this.researchCandidates, required this.members,
    required this.blockers, this.registeredAt, this.observationStartsAt,
  });

  final DateTime generatedAt;
  final String status;
  final DateTime? registeredAt;
  final DateTime? observationStartsAt;
  final int memberCount;
  final int recordedWindows;
  final int researchCandidates;
  final List<MindProspectiveSignalMember> members;
  final List<String> blockers;

  factory MindProspectiveSignalsStatus.fromJson(Object? raw) {
    if (raw is! Map<String, dynamic> ||
        raw['contractVersion'] != 'mind.prospective-signals.v1' ||
        raw['decisionPosture'] != 'ABSTAIN' ||
        raw['outcomeValidated'] != false ||
        raw['paperOrderSubmitted'] != false ||
        raw['executionEnabled'] != false ||
        !const {
          'UNREGISTERED', 'UNAVAILABLE',
          'AWAITING_RECEIPTS', 'RESEARCH_RECEIPTS_RECORDED',
        }.contains(raw['status'])) {
      throw const FormatException('Unsafe prospective signal status');
    }
    final generated = DateTime.tryParse(raw['generatedAt']?.toString() ?? '');
    final registered = _parseTime(raw['registeredAt']);
    final start = _parseTime(raw['observationStartsAt']);
    final membersRaw = raw['members'], blockersRaw = raw['blockers'];
    final count = raw['memberCount'];
    final receipts = raw['recordedWindows'];
    final signals = raw['researchCandidates'];
    if (generated == null || membersRaw is! List || blockersRaw is! List ||
        count is! int || !const {0, 20}.contains(count) ||
        receipts is! int || receipts < 0 ||
        signals is! int || signals < 0 || signals > receipts) {
      throw const FormatException('Invalid prospective source totals');
    }
    final members = membersRaw
        .map(MindProspectiveSignalMember.fromJson).toList(growable: false);
    final blockers = blockersRaw.map((x) => x is String && x.isNotEmpty
        ? x : throw const FormatException('Invalid blocker'))
        .toList(growable: false);
    for (final required in const [
      'ORIGINAL_PROVIDER_KNOWN_AT_NOT_INDEPENDENTLY_ATTESTED',
      'BROKER_STATUTORY_COST_MODEL_NOT_COMPLETE',
      'ACCOUNT_RISK_NOT_EVALUATED',
      'PAPER_SHADOW_ENROLLMENT_REQUIRED',
      'LIVE_EXECUTION_DISABLED',
    ]) {
      if (!blockers.contains(required)) {
        throw const FormatException('Missing trading safety gate');
      }
    }
    final state = raw['status'] as String;
    final unregistered = state == 'UNREGISTERED' || state == 'UNAVAILABLE';
    if (members.length != count ||
        members.map((e) => e.slot).toSet().length != count ||
        members.map((e) => e.symbol).toSet().length != count ||
        members.fold<int>(0, (a, e) => a + e.recordedWindows) != receipts ||
        members.fold<int>(0, (a, e) => a + e.researchCandidates) != signals ||
        (unregistered &&
            (registered != null || start != null || count != 0 ||
             receipts != 0 || signals != 0)) ||
        (!unregistered &&
            (registered == null || start == null ||
             !start.isAfter(registered) || registered.isAfter(generated) ||
             count != 20)) ||
        (state == 'AWAITING_RECEIPTS' && receipts != 0) ||
        (state == 'RESEARCH_RECEIPTS_RECORDED' && receipts == 0)) {
      throw const FormatException('Misleading prospective totals');
    }
    return MindProspectiveSignalsStatus(
      generatedAt: generated, status: state,
      registeredAt: registered, observationStartsAt: start,
      memberCount: count, recordedWindows: receipts,
      researchCandidates: signals, members: members,
      blockers: blockers,
    );
  }

  static DateTime? _parseTime(Object? raw) {
    if (raw == null) return null;
    if (raw is! String) throw const FormatException('Invalid date');
    return DateTime.tryParse(raw) ??
        (throw const FormatException('Invalid date'));
  }
}

abstract interface class MindProspectiveSignalsRepository {
  Future<MindProspectiveSignalsStatus> load();
}

class HttpMindProspectiveSignalsRepository
    implements MindProspectiveSignalsRepository {
  const HttpMindProspectiveSignalsRepository(this.api);

  final AuthenticatedApiClient api;

  @override
  Future<MindProspectiveSignalsStatus> load() async =>
      MindProspectiveSignalsStatus.fromJson(
        await api.getJson('/api/customer/mind/v1/prospective-signals'));
}

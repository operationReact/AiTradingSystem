import '../../../data/authenticated_api_client.dart';

/// Internal prospective research accounting; never proof of profitability,
/// exchange provenance, PAPER enrollment or authority to trade.
class MindProspectiveEvidenceCompleteness {
  const MindProspectiveEvidenceCompleteness({
    required this.generatedAt,
    required this.status,
    required this.completedSessions,
    required this.membersWithSufficientSourceCoverage,
    required this.expectedSignalWindowsAtMaturity,
    required this.recordedSignalWindows,
    required this.missingSignalWindows,
    required this.precommittedCandidates,
    required this.reconciledOutcomes,
    required this.pendingOutcomes,
    required this.blockers,
  });

  final DateTime generatedAt;
  final String status;
  final int completedSessions;
  final int membersWithSufficientSourceCoverage;
  final int? expectedSignalWindowsAtMaturity;
  final int recordedSignalWindows;
  final int? missingSignalWindows;
  final int precommittedCandidates;
  final int reconciledOutcomes;
  final int pendingOutcomes;
  final List<String> blockers;

  static const states = {
    'UNAVAILABLE',
    'OBSERVATION_SESSIONS_IN_PROGRESS',
    'SOURCE_COVERAGE_INCOMPLETE',
    'SIGNAL_RECEIPT_WINDOWS_INCOMPLETE',
    'NO_PROSPECTIVE_CANDIDATES',
    'OUTCOME_RECONCILIATION_INCOMPLETE',
    'RESEARCH_ACCOUNTING_COMPLETE_NOT_VALIDATED',
  };

  static const safetyBlockers = [
    'PAPER_SHADOW_ENROLLMENT_REQUIRED',
    'LIVE_EXECUTION_DISABLED',
  ];

  static const evidenceBlockers = [
    'INTERNAL_COUNTS_NOT_INDEPENDENT_EXCHANGE_ATTESTATION',
    'SIGNAL_SELECTION_AND_SAMPLE_INDEPENDENCE_NOT_VALIDATED',
    'REALISTIC_INDIAN_FEES_TAXES_AND_SLIPPAGE_NOT_VERIFIED',
    'ACCOUNT_RISK_AND_OUT_OF_SAMPLE_GENERALIZATION_NOT_EVALUATED',
  ];

  factory MindProspectiveEvidenceCompleteness.fromJson(Object? raw) {
    if (raw is! Map<String, dynamic> ||
        raw['contractVersion'] !=
            'mind.prospective-evidence-completeness.v1' ||
        raw['decisionPosture'] != 'ABSTAIN' ||
        raw['outcomeValidated'] != false ||
        raw['paperOrderSubmitted'] != false ||
        raw['executionEnabled'] != false ||
        !states.contains(raw['status']) ||
        raw['targetSessions'] != 10) {
      throw const FormatException('Unsafe prospective evidence contract');
    }

    final timestamp = raw['generatedAt'];
    final at = timestamp is String && timestamp.endsWith('Z')
        ? DateTime.tryParse(timestamp) : null;
    final completed = raw['completedSessions'];
    final members = raw['memberCount'];
    final covered = raw['membersWithSufficientSourceCoverage'];
    final expected = raw['expectedSignalWindowsAtMaturity'];
    final recorded = raw['recordedSignalWindows'];
    final missing = raw['missingSignalWindows'];
    final candidates = raw['precommittedCandidates'];
    final reconciled = raw['reconciledOutcomes'];
    final pending = raw['pendingOutcomes'];
    final gates = raw['blockers'];
    final state = raw['status'] as String;

    if (at == null ||
        completed is! int || completed < 0 || completed > 10 ||
        members is! int || (members != 0 && members != 20) ||
        covered is! int || covered < 0 || covered > members ||
        recorded is! int || recorded < 0 || recorded > 20000 ||
        candidates is! int || candidates < 0 || candidates > recorded ||
        reconciled is! int || reconciled < 0 || reconciled > candidates ||
        pending is! int || pending != candidates - reconciled ||
        gates is! List || gates.isEmpty ||
        gates.any((item) => item is! String || item.isEmpty) ||
        (state != 'UNAVAILABLE' && members != 20)) {
      throw const FormatException('Invalid prospective evidence totals');
    }
    final blockers = gates.cast<String>();
    if (safetyBlockers.any((code) => !blockers.contains(code)) ||
        (state != 'UNAVAILABLE' &&
         evidenceBlockers.any((code) => !blockers.contains(code)))) {
      throw const FormatException('Mandatory research blockers omitted');
    }

    final matured = completed == 10;
    if (state == 'UNAVAILABLE') {
      if (completed != 0 || members != 0 || covered != 0 ||
          recorded != 0 || candidates != 0 || reconciled != 0 ||
          expected != null || missing != null) {
        throw const FormatException('Unavailable research cannot contain results');
      }
    } else if (matured) {
      if (expected is! int || expected <= 0 || expected > 15000 ||
          missing is! int || missing < 0 || missing > expected ||
          missing > 0 && recorded >= expected) {
        throw const FormatException('Invalid mature signal-window accounting');
      }
    } else if (expected != null || missing != null) {
      throw const FormatException('Future windows cannot be marked missing early');
    }

    final consistentState = switch (state) {
      'UNAVAILABLE' => true,
      'OBSERVATION_SESSIONS_IN_PROGRESS' => !matured,
      'SOURCE_COVERAGE_INCOMPLETE' => matured,
      'SIGNAL_RECEIPT_WINDOWS_INCOMPLETE' =>
        matured && (recorded != expected || missing != 0),
      'NO_PROSPECTIVE_CANDIDATES' =>
        matured && covered == 20 && recorded == expected &&
        missing == 0 && candidates == 0,
      'OUTCOME_RECONCILIATION_INCOMPLETE' =>
        matured && covered == 20 && recorded == expected &&
        missing == 0 && candidates > 0 && pending > 0,
      'RESEARCH_ACCOUNTING_COMPLETE_NOT_VALIDATED' =>
        matured && covered == 20 && recorded == expected &&
        missing == 0 && candidates > 0 && pending == 0,
      _ => false,
    };
    if (!consistentState) {
      throw const FormatException('Contradictory research readiness status');
    }

    return MindProspectiveEvidenceCompleteness(
      generatedAt: at.toUtc(),
      status: state,
      completedSessions: completed,
      membersWithSufficientSourceCoverage: covered,
      expectedSignalWindowsAtMaturity: expected as int?,
      recordedSignalWindows: recorded,
      missingSignalWindows: missing as int?,
      precommittedCandidates: candidates,
      reconciledOutcomes: reconciled,
      pendingOutcomes: pending,
      blockers: List.unmodifiable(blockers),
    );
  }
}

abstract interface class MindProspectiveEvidenceCompletenessRepository {
  Future<MindProspectiveEvidenceCompleteness> load();
}

class HttpMindProspectiveEvidenceCompletenessRepository
    implements MindProspectiveEvidenceCompletenessRepository {
  const HttpMindProspectiveEvidenceCompletenessRepository(this.api);

  final AuthenticatedApiClient api;

  @override
  Future<MindProspectiveEvidenceCompleteness> load() async =>
      MindProspectiveEvidenceCompleteness.fromJson(await api.getJson(
        '/api/customer/mind/v1/prospective-evidence-completeness'));
}

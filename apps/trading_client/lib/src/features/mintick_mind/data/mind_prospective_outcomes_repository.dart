import '../../../data/authenticated_api_client.dart';

/// Counts ONLY immutable prospective research outcomes read from the backend.
/// This is not user P&L, executed trades, or verified investment performance.
class MindProspectiveOutcomesStatus {
  const MindProspectiveOutcomesStatus({
    required this.generatedAt,
    required this.status,
    required this.precommittedCandidates,
    required this.reconciledOutcomes,
    required this.positiveNetObservations,
    required this.blockers,
  });

  final DateTime generatedAt;
  final String status;
  final int precommittedCandidates;
  final int reconciledOutcomes;
  final int positiveNetObservations;
  final List<String> blockers;

  static const states = {
    'UNREGISTERED',
    'UNAVAILABLE',
    'AWAITING_CANDIDATES',
    'AWAITING_OUTCOMES',
    'PARTIAL_RESEARCH_OUTCOMES',
    'RESEARCH_OUTCOMES_RECORDED',
  };

  static const mandatoryGates = [
    'SOURCE_PROVENANCE_NOT_INDEPENDENTLY_ATTESTED',
    'ESTIMATED_COSTS_NOT_ACTUAL_INDIAN_STATUTORY_CHARGES',
    'ACCOUNT_RISK_NOT_EVALUATED',
    'PAPER_SHADOW_ENROLLMENT_REQUIRED',
    'LIVE_EXECUTION_DISABLED',
  ];

  factory MindProspectiveOutcomesStatus.fromJson(Object? raw) {
    if (raw is! Map<String, dynamic> ||
        raw['contractVersion'] != 'mind.prospective-outcomes.v1' ||
        raw['decisionPosture'] != 'ABSTAIN' ||
        raw['outcomeValidated'] != false ||
        raw['paperOrderSubmitted'] != false ||
        raw['executionEnabled'] != false ||
        !states.contains(raw['status'])) {
      throw const FormatException('Unsafe MintTick Mind outcome evidence');
    }
    final rawTimestamp = raw['generatedAt'];
    // Java Instant timestamps must include their UTC Z suffix.
    final generated = rawTimestamp is String && rawTimestamp.endsWith('Z')
        ? DateTime.tryParse(rawTimestamp) : null;
    final candidates = raw['precommittedCandidates'];
    final reconciled = raw['reconciledOutcomes'];
    final positive = raw['positiveNetObservations'];
    final gates = raw['blockers'];
    if (generated == null ||
        candidates is! int || reconciled is! int || positive is! int ||
        candidates < 0 || candidates > 15000 ||
        reconciled < 0 || positive < 0 ||
        reconciled > candidates || positive > reconciled ||
        gates is! List || gates.isEmpty ||
        gates.any((element) => element is! String || element.isEmpty)) {
      throw const FormatException('Invalid prospective outcome totals');
    }
    final blockers = gates.cast<String>();
    if (mandatoryGates.any((gate) => !blockers.contains(gate))) {
      throw const FormatException('Research safety blockers were omitted');
    }
    final state = raw['status'] as String;
    if (switch (state) {
      'UNREGISTERED' || 'UNAVAILABLE' => candidates != 0 || reconciled != 0,
      'AWAITING_CANDIDATES' => candidates != 0,
      'AWAITING_OUTCOMES' => candidates <= 0 || reconciled != 0,
      'PARTIAL_RESEARCH_OUTCOMES' =>
        reconciled <= 0 || reconciled >= candidates,
      'RESEARCH_OUTCOMES_RECORDED' =>
        candidates <= 0 || reconciled != candidates,
      _ => true,
    }) {
      throw const FormatException('Misleading research outcome status');
    }
    return MindProspectiveOutcomesStatus(
      generatedAt: generated.toUtc(),
      status: state,
      precommittedCandidates: candidates,
      reconciledOutcomes: reconciled,
      positiveNetObservations: positive,
      blockers: List.unmodifiable(blockers),
    );
  }
}

abstract interface class MindProspectiveOutcomesRepository {
  Future<MindProspectiveOutcomesStatus> load();
}

class HttpMindProspectiveOutcomesRepository
    implements MindProspectiveOutcomesRepository {
  const HttpMindProspectiveOutcomesRepository(this.api);

  final AuthenticatedApiClient api;

  @override
  Future<MindProspectiveOutcomesStatus> load() async =>
      MindProspectiveOutcomesStatus.fromJson(
        await api.getJson('/api/customer/mind/v1/prospective-outcomes'));
}

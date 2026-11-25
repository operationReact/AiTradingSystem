import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:trading_client/src/auth/customer_identity.dart';
import 'package:trading_client/src/data/authenticated_api_client.dart';
import 'package:trading_client/src/data/operator_session.dart';
import 'package:trading_client/src/features/mintick_mind/data/mind_prospective_evidence_completeness_repository.dart';

class _TestIdentity extends UnavailableCustomerIdentity {
  const _TestIdentity();

  @override
  bool get isConfigured => true;

  @override
  Future<String?> accessToken() async => 'test-only-minttick-token';
}

Map<String, dynamic> report({
  String status = 'SIGNAL_RECEIPT_WINDOWS_INCOMPLETE',
}) {
  final unavailable = status == 'UNAVAILABLE';
  final pending = status == 'OBSERVATION_SESSIONS_IN_PROGRESS';
  final mature = !unavailable && !pending;
  final missing = status == 'SIGNAL_RECEIPT_WINDOWS_INCOMPLETE';
  final noCandidates = status == 'NO_PROSPECTIVE_CANDIDATES';
  final incompleteOutcomes = status == 'OUTCOME_RECONCILIATION_INCOMPLETE';
  final candidates = unavailable ? 0 : noCandidates ? 0 : 30;
  final reconciled = incompleteOutcomes ? 29 : candidates;
  return {
    'contractVersion': 'mind.prospective-evidence-completeness.v1',
    'generatedAt': '2026-11-27T10:40:00Z',
    'status': status,
    'targetSessions': 10,
    'completedSessions': unavailable ? 0 : pending ? 4 : 10,
    'memberCount': unavailable ? 0 : 20,
    'membersWithSufficientSourceCoverage':
      unavailable || pending ? 0 : status == 'SOURCE_COVERAGE_INCOMPLETE' ? 19 : 20,
    'expectedSignalWindowsAtMaturity': mature ? 11000 : null,
    'recordedSignalWindows': unavailable ? 0 : pending ? 4400 :
        missing ? 10999 : 11000,
    'missingSignalWindows': mature ? missing ? 1 : 0 : null,
    'precommittedCandidates': candidates,
    'reconciledOutcomes': reconciled,
    'pendingOutcomes': candidates - reconciled,
    'blockers': [
      if (!unavailable)
        ...MindProspectiveEvidenceCompleteness.evidenceBlockers,
      ...MindProspectiveEvidenceCompleteness.safetyBlockers,
      if (unavailable) 'RESEARCH_EVIDENCE_UNAVAILABLE',
    ],
    'decisionPosture': 'ABSTAIN',
    'outcomeValidated': false,
    'paperOrderSubmitted': false,
    'executionEnabled': false,
  };
}

void main() {
  test('authenticated GET reads the immutable accounting route, not orders',
      () async {
    final original = MintTickIdentity.current;
    MintTickIdentity.current = const _TestIdentity();
    addTearDown(() => MintTickIdentity.current = original);
    final session = OperatorSession()..markCustomerAuthenticated();
    final api = AuthenticatedApiClient(
      session: session,
      baseUri: Uri.parse('https://api.minttick.com'),
      client: MockClient((request) async {
        expect(request.method, 'GET');
        expect(request.url.path,
          '/api/customer/mind/v1/prospective-evidence-completeness');
        expect(request.headers['Authorization'],
          'Bearer test-only-minttick-token');
        expect(request.headers.containsKey('X-API-Key'), false);
        return http.Response(jsonEncode(report()), 200);
      }),
    );
    final result =
        await HttpMindProspectiveEvidenceCompletenessRepository(api).load();
    expect(result.status, 'SIGNAL_RECEIPT_WINDOWS_INCOMPLETE');
    expect(result.recordedSignalWindows, 10999);
    expect(result.missingSignalWindows, 1);
    expect(result.pendingOutcomes, 0);
    expect(result.blockers, contains('LIVE_EXECUTION_DISABLED'));
  });

  test('complete internally recorded evidence never claims execution',
      () {
    final result = MindProspectiveEvidenceCompleteness.fromJson(
      report(status: 'RESEARCH_ACCOUNTING_COMPLETE_NOT_VALIDATED'));
    expect(result.completedSessions, 10);
    expect(result.recordedSignalWindows, 11000);
    expect(result.missingSignalWindows, 0);
    expect(result.pendingOutcomes, 0);
    expect(result.status,
      'RESEARCH_ACCOUNTING_COMPLETE_NOT_VALIDATED');
  });

  test('observation remains unknown until all sessions finish', () {
    final result = MindProspectiveEvidenceCompleteness.fromJson(
      report(status: 'OBSERVATION_SESSIONS_IN_PROGRESS'));
    expect(result.completedSessions, 4);
    expect(result.expectedSignalWindowsAtMaturity, isNull);
    expect(result.missingSignalWindows, isNull);
  });

  test('unavailable evidence contains no fabricated counters', () {
    final result = MindProspectiveEvidenceCompleteness.fromJson(
      report(status: 'UNAVAILABLE'));
    expect(result.recordedSignalWindows, 0);
    expect(result.precommittedCandidates, 0);
    expect(result.membersWithSufficientSourceCoverage, 0);
  });

  test('rejects execution permissions and removed source caveats', () {
    for (final field in [
      'executionEnabled', 'outcomeValidated', 'paperOrderSubmitted'
    ]) {
      expect(() => MindProspectiveEvidenceCompleteness.fromJson(
        report()..[field] = true), throwsFormatException);
    }
    expect(() => MindProspectiveEvidenceCompleteness.fromJson(
      report()..['decisionPosture'] = 'BUY'), throwsFormatException);
    expect(() => MindProspectiveEvidenceCompleteness.fromJson(
      report()..['blockers'] = ['LIVE_EXECUTION_DISABLED']),
      throwsFormatException);
  });

  test('rejects impossible, contradictory, or future-derived totals', () {
    expect(() => MindProspectiveEvidenceCompleteness.fromJson(
      report()..['pendingOutcomes'] = 4), throwsFormatException);
    expect(() => MindProspectiveEvidenceCompleteness.fromJson(
      report()..['reconciledOutcomes'] = 31), throwsFormatException);
    expect(() => MindProspectiveEvidenceCompleteness.fromJson(
      report()..['status'] = 'LIVE_APPROVED'), throwsFormatException);
    expect(() => MindProspectiveEvidenceCompleteness.fromJson(
      report(status: 'OBSERVATION_SESSIONS_IN_PROGRESS')
        ..['expectedSignalWindowsAtMaturity'] = 11000),
      throwsFormatException);
    expect(() => MindProspectiveEvidenceCompleteness.fromJson(
      report(status: 'UNAVAILABLE')..['recordedSignalWindows'] = 5),
      throwsFormatException);
  });
}

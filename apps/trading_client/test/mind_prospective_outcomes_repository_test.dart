import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:trading_client/src/auth/customer_identity.dart';
import 'package:trading_client/src/data/authenticated_api_client.dart';
import 'package:trading_client/src/data/operator_session.dart';
import 'package:trading_client/src/features/mintick_mind/data/mind_prospective_outcomes_repository.dart';

class _TestIdentity extends UnavailableCustomerIdentity {
  const _TestIdentity();
  @override
  bool get isConfigured => true;
  @override
  Future<String?> accessToken() async => 'test-only-minttick-token';
}

Map<String, dynamic> report({
  String status = 'PARTIAL_RESEARCH_OUTCOMES',
}) => {
  'contractVersion': 'mind.prospective-outcomes.v1',
  'generatedAt': '2026-11-11T10:16:00Z',
  'status': status,
  'precommittedCandidates':
    status == 'UNREGISTERED' || status == 'UNAVAILABLE' ||
    status == 'AWAITING_CANDIDATES' ? 0 : 5,
  'reconciledOutcomes':
    status == 'PARTIAL_RESEARCH_OUTCOMES' ? 3 :
    status == 'RESEARCH_OUTCOMES_RECORDED' ? 5 : 0,
  'positiveNetObservations':
    status == 'PARTIAL_RESEARCH_OUTCOMES' ||
    status == 'RESEARCH_OUTCOMES_RECORDED' ? 1 : 0,
  'blockers': [
    'PROSPECTIVE_SAMPLE_NOT_VALIDATED',
    ...MindProspectiveOutcomesStatus.mandatoryGates,
  ],
  'decisionPosture': 'ABSTAIN',
  'outcomeValidated': false,
  'paperOrderSubmitted': false,
  'executionEnabled': false,
};

void main() {
  test('authenticated GET reads only safe counts without any mutation', () async {
    final before = MintTickIdentity.current;
    MintTickIdentity.current = const _TestIdentity();
    addTearDown(() => MintTickIdentity.current = before);
    final session = OperatorSession()..markCustomerAuthenticated();
    final api = AuthenticatedApiClient(
      session: session,
      baseUri: Uri.parse('https://api.minttick.com'),
      client: MockClient((request) async {
        expect(request.method, 'GET');
        expect(request.url.path, '/api/customer/mind/v1/prospective-outcomes');
        expect(request.headers['Authorization'], 'Bearer test-only-minttick-token');
        expect(request.headers.containsKey('X-API-Key'), isFalse);
        return http.Response(jsonEncode(report()), 200);
      }));
    final data = await HttpMindProspectiveOutcomesRepository(api).load();
    expect(data.status, 'PARTIAL_RESEARCH_OUTCOMES');
    expect(data.precommittedCandidates, 5);
    expect(data.reconciledOutcomes, 3);
    expect(data.positiveNetObservations, 1);
    expect(data.blockers, contains('LIVE_EXECUTION_DISABLED'));
  });

  test('unregistered cohort has no fabricated positive outcomes', () {
    final data = MindProspectiveOutcomesStatus.fromJson(
      report(status: 'UNREGISTERED'));
    expect(data.reconciledOutcomes, 0);
    expect(data.positiveNetObservations, 0);
    expect(data.status, 'UNREGISTERED');
  });

  test('execution, false performance validation and BUY posture are refused', () {
    for (final key in [
      'executionEnabled', 'paperOrderSubmitted', 'outcomeValidated',
    ]) {
      expect(() => MindProspectiveOutcomesStatus.fromJson(
        report()..[key] = true), throwsFormatException);
    }
    expect(() => MindProspectiveOutcomesStatus.fromJson(
      report()..['decisionPosture'] = 'BUY'), throwsFormatException);
    expect(() => MindProspectiveOutcomesStatus.fromJson(
      report()..['blockers'] = ['PROSPECTIVE_SAMPLE_NOT_VALIDATED']),
      throwsFormatException);
  });

  test('invented, impossible or contradictory count states fail closed', () {
    expect(() => MindProspectiveOutcomesStatus.fromJson(
      report()..['positiveNetObservations'] = 4), throwsFormatException);
    expect(() => MindProspectiveOutcomesStatus.fromJson(
      report()..['reconciledOutcomes'] = 6), throwsFormatException);
    expect(() => MindProspectiveOutcomesStatus.fromJson(
      report()..['status'] = 'RESEARCH_OUTCOMES_RECORDED'),
      throwsFormatException);
    expect(() => MindProspectiveOutcomesStatus.fromJson(
      report()..['status'] = 'APPROVED_FOR_LIVE'), throwsFormatException);
  });
}

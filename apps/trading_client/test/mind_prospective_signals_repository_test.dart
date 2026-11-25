import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:trading_client/src/auth/customer_identity.dart';
import 'package:trading_client/src/data/authenticated_api_client.dart';
import 'package:trading_client/src/data/operator_session.dart';
import 'package:trading_client/src/features/mintick_mind/data/mind_prospective_signals_repository.dart';

class _TestIdentity extends UnavailableCustomerIdentity {
  const _TestIdentity();
  @override
  bool get isConfigured => true;
  @override
  Future<String?> accessToken() async => 'test-only-minttick-token';
}

const _gates = [
  'PROSPECTIVE_OUTCOMES_NOT_RECONCILED',
  'ORIGINAL_PROVIDER_KNOWN_AT_NOT_INDEPENDENTLY_ATTESTED',
  'BROKER_STATUTORY_COST_MODEL_NOT_COMPLETE',
  'ACCOUNT_RISK_NOT_EVALUATED',
  'PAPER_SHADOW_ENROLLMENT_REQUIRED',
  'LIVE_EXECUTION_DISABLED',
];

Map<String, dynamic> report({bool registered = true}) => {
  'contractVersion': 'mind.prospective-signals.v1',
  'generatedAt': '2026-11-10T05:35:00Z',
  'status': registered ? 'RESEARCH_RECEIPTS_RECORDED' : 'UNREGISTERED',
  'registeredAt': registered ? '2026-11-09T16:00:00Z' : null,
  'observationStartsAt': registered ? '2026-11-10T03:45:00Z' : null,
  'memberCount': registered ? 20 : 0,
  'recordedWindows': registered ? 1 : 0,
  'researchCandidates': 0,
  'members': registered
      ? List.generate(20, (index) => {
          'slot': index + 1,
          'symbol': 'STOCK${index + 1}-EQ',
          'recordedWindows': index == 0 ? 1 : 0,
          'researchCandidates': 0,
        })
      : [],
  'blockers': _gates,
  'decisionPosture': 'ABSTAIN',
  'outcomeValidated': false,
  'paperOrderSubmitted': false,
  'executionEnabled': false,
};

void main() {
  test('customer-only Cognito GET cannot register a cohort or place orders', () async {
    final old = MintTickIdentity.current;
    MintTickIdentity.current = const _TestIdentity();
    addTearDown(() => MintTickIdentity.current = old);
    final session = OperatorSession()..markCustomerAuthenticated();
    final api = AuthenticatedApiClient(
      session: session,
      baseUri: Uri.parse('https://api.minttick.com'),
      client: MockClient((request) async {
        expect(request.method, 'GET');
        expect(request.url.path, '/api/customer/mind/v1/prospective-signals');
        expect(request.headers['Authorization'], 'Bearer test-only-minttick-token');
        expect(request.headers.containsKey('X-API-Key'), isFalse);
        return http.Response(jsonEncode(report()), 200);
      }));
    final result = await HttpMindProspectiveSignalsRepository(api).load();
    expect(result.status, 'RESEARCH_RECEIPTS_RECORDED');
    expect(result.memberCount, 20);
    expect(result.recordedWindows, 1);
    expect(result.researchCandidates, 0);
    expect(result.blockers, contains('LIVE_EXECUTION_DISABLED'));
  });

  test('missing cohort has zero fabricated research receipts', () {
    final result = MindProspectiveSignalsStatus.fromJson(report(registered: false));
    expect(result.status, 'UNREGISTERED');
    expect(result.registeredAt, isNull);
    expect(result.memberCount, 0);
    expect(result.recordedWindows, 0);
  });

  test('execution, independently validated outcomes, and invalid posture are refused', () {
    for (final key in ['executionEnabled', 'paperOrderSubmitted', 'outcomeValidated']) {
      final altered = report()..[key] = true;
      expect(() => MindProspectiveSignalsStatus.fromJson(altered),
        throwsFormatException);
    }
    expect(() => MindProspectiveSignalsStatus.fromJson(
      report()..['decisionPosture'] = 'BUY'), throwsFormatException);
    final missing = report()..['blockers'] =
      ['PROSPECTIVE_OUTCOMES_NOT_RECONCILED'];
    expect(() => MindProspectiveSignalsStatus.fromJson(missing),
      throwsFormatException);
  });

  test('revised or inflated receipt counts cannot pass validation', () {
    final inflated = report()..['researchCandidates'] = 1;
    expect(() => MindProspectiveSignalsStatus.fromJson(inflated),
      throwsFormatException);
    final fake = report();
    ((fake['members'] as List).first as Map<String, dynamic>)
      ['recordedWindows'] = -1;
    expect(() => MindProspectiveSignalsStatus.fromJson(fake),
      throwsFormatException);
  });
}

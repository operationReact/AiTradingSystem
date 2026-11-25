import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:trading_client/src/auth/customer_identity.dart';
import 'package:trading_client/src/data/authenticated_api_client.dart';
import 'package:trading_client/src/data/operator_session.dart';
import 'package:trading_client/src/features/mintick_mind/data/mind_prospective_cohort_repository.dart';

class _TestIdentity extends UnavailableCustomerIdentity {
  const _TestIdentity();

  @override
  bool get isConfigured => true;

  @override
  Future<String?> accessToken() async => 'prospective-test-token';
}

const blockers = [
  'PROSPECTIVE_RETURNS_NOT_EVALUATED',
  'LIQUIDITY_AND_SURVIVORSHIP_NOT_INDEPENDENTLY_VERIFIED',
  'SOURCE_KNOWN_AT_NOT_INDEPENDENTLY_ATTESTED',
  'BROKER_STATUTORY_COST_MODEL_NOT_COMPLETE',
  'ACCOUNT_RISK_NOT_EVALUATED',
  'PAPER_SHADOW_ENROLLMENT_REQUIRED',
  'LIVE_EXECUTION_DISABLED',
];

Map<String, dynamic> unregistered() => {
  'contractVersion': 'mind.prospective-cohort.v1',
  'generatedAt': '2026-11-09T16:00:00Z',
  'sourceStatus': 'UNREGISTERED',
  'cohortCode': null,
  'strategyCode': 'MA_RECLAIM_V1',
  'selectionMethod': 'DETERMINISTIC_CATALOGUE_HASH_NO_SIGNAL_RANKING',
  'catalogueVersion': null,
  'registeredAt': null,
  'observationStartsAt': null,
  'maturityAt': null,
  'targetSessions': 10,
  'completedSessions': 0,
  'memberCount': 0,
  'membersWithSufficientCoverage': 0,
  'members': [],
  'blockers': [...blockers, 'COHORT_NOT_REGISTERED'],
  'decisionPosture': 'ABSTAIN',
  'outcomeValidated': false,
  'paperOrderSubmitted': false,
  'executionEnabled': false,
};

Map<String, dynamic> registered() => {
  ...unregistered(),
  'sourceStatus': 'COLLECTING',
  'cohortCode': 'MA_RECLAIM_NSE_EQ_PROSPECTIVE_V1',
  'catalogueVersion': '64010a48-afcb-49ac-9fac-001a6c56d530',
  'registeredAt': '2026-11-09T16:00:00Z',
  'generatedAt': '2026-11-10T16:00:00Z',
  'observationStartsAt': '2026-11-10T03:45:00Z',
  'maturityAt': '2026-11-23T10:00:00Z',
  'memberCount': 20,
  'members': List.generate(20, (i) => {
    'slot': i + 1,
    'exchange': 'NSE',
    'symbol': 'SAMPLE${i + 1}-EQ',
    'validCandles': 0,
    'expectedCandles': 0,
    'coveragePercent': 0,
    'state': 'OBSERVATION_PENDING',
  }),
};

void main() {
  test('customer-only GET reads forward cohort without POST or broker credentials',
      () async {
    final old = MintTickIdentity.current;
    MintTickIdentity.current = const _TestIdentity();
    addTearDown(() => MintTickIdentity.current = old);
    final session = OperatorSession()..markCustomerAuthenticated();
    final api = AuthenticatedApiClient(
      session: session, baseUri: Uri.parse('https://api.minttick.com'),
      client: MockClient((request) async {
        expect(request.method, 'GET');
        expect(request.url.path, '/api/customer/mind/v1/prospective-cohort');
        expect(request.headers['Authorization'], 'Bearer prospective-test-token');
        expect(request.headers.containsKey('X-API-Key'), isFalse);
        return http.Response(jsonEncode(registered()), 200);
      }));
    final result = await HttpMindProspectiveCohortRepository(api).load();
    expect(result.memberCount, 20);
    expect(result.sourceStatus, 'COLLECTING');
    expect(result.readyMembers, 0);
    expect(result.blockers, contains('LIVE_EXECUTION_DISABLED'));
  });

  test('missing registration is not a fabricated prospective trade result', () {
    final result = MindProspectiveCohort.fromJson(unregistered());
    expect(result.sourceStatus, 'UNREGISTERED');
    expect(result.members, isEmpty);
    expect(result.registeredAt, isNull);
    expect(result.readyMembers, 0);
  });

  test('reject unsafe flags and an unsupported research strategy', () {
    for (final key in [
      'outcomeValidated', 'paperOrderSubmitted', 'executionEnabled',
    ]) {
      expect(() => MindProspectiveCohort.fromJson(unregistered()..[key] = true),
        throwsFormatException);
    }
    expect(() => MindProspectiveCohort.fromJson(
      unregistered()..['decisionPosture'] = 'BUY'), throwsFormatException);
    expect(() => MindProspectiveCohort.fromJson(
      unregistered()..['strategyCode'] = 'SMA_TREND_VOLUME'),
      throwsFormatException);
  });

  test('future enrollment and missing protection are refused', () {
    final unsafe = registered()..['registeredAt'] = '2026-12-01T16:00:00Z';
    expect(() => MindProspectiveCohort.fromJson(unsafe), throwsFormatException);
    final missing = registered();
    (missing['blockers'] as List).remove('PROSPECTIVE_RETURNS_NOT_EVALUATED');
    expect(() => MindProspectiveCohort.fromJson(missing), throwsFormatException);
  });

  test('invalid membership and overclaimed study maturity are refused', () {
    final incomplete = registered()..['memberCount'] = 19;
    expect(() => MindProspectiveCohort.fromJson(incomplete), throwsFormatException);
    final premature = registered()..['sourceStatus'] =
        'SOURCE_COVERAGE_READY_RESEARCH_ONLY';
    expect(() => MindProspectiveCohort.fromJson(premature), throwsFormatException);
  });
}

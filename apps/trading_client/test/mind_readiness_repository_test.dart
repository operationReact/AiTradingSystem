import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:trading_client/src/auth/customer_identity.dart';
import 'package:trading_client/src/data/authenticated_api_client.dart';
import 'package:trading_client/src/data/operator_session.dart';
import 'package:trading_client/src/features/mintick_mind/data/mind_readiness_repository.dart';

class _TestIdentity extends UnavailableCustomerIdentity {
  const _TestIdentity();
  @override
  bool get isConfigured => true;
  @override
  Future<String?> accessToken() async => 'fake-in-test-only';
}

Map<String, dynamic> validPayload() => {
  'contractVersion': 'mind.overview.v1',
  'generatedAt': '2026-11-09T09:00:00Z',
  'decisionPosture': 'ABSTAIN',
  'verifiedSignal': false,
  'executionEnabled': false,
  'market': {
    'status': 'STORED',
    'source': 'STORED_LAST_OVERVIEW',
    'asOf': '2026-11-09T08:55:00Z',
    'observations': 40,
    'reasonCode': 'STORED_NOT_LIVE',
  },
  'research': {
    'status': 'RECORDED',
    'source': 'PERSISTED_RESEARCH_SCAN',
    'asOf': '2026-11-09T08:50:00Z',
    'observations': 80,
    'reasonCode': 'RESEARCH_NOT_VALIDATED_FOR_EXECUTION',
  },
};

void main() {
  test('real Mind overview GET carries Cognito bearer, no broker/order mutation', () async {
    final identity = MintTickIdentity.current;
    MintTickIdentity.current = const _TestIdentity();
    addTearDown(() => MintTickIdentity.current = identity);
    final session = OperatorSession()..markCustomerAuthenticated();
    final client = MockClient((request) async {
      expect(request.method, 'GET');
      expect(request.url.toString(),
          'https://api.minttick.com/api/customer/mind/v1/overview');
      expect(request.headers['Authorization'], 'Bearer fake-in-test-only');
      expect(request.headers.containsKey('X-API-Key'), isFalse);
      return http.Response(jsonEncode(validPayload()), 200);
    });
    final response = await HttpMindReadinessRepository(
      AuthenticatedApiClient(
        session: session, client: client,
        baseUri: Uri.parse('https://api.minttick.com'),
      ),
    ).load();
    expect(response.market.status, 'STORED');
    expect(response.research.status, 'RECORDED');
    expect(response.research.observations, 80);
  });

  test('source, verified decision and execution claims fail closed', () {
    for (final field in ['executionEnabled', 'verifiedSignal']) {
      final raw = validPayload()..[field] = true;
      expect(() => MindReadiness.fromJson(raw), throwsFormatException);
    }
    final altered = validPayload();
    (altered['market'] as Map<String, dynamic>)['source'] = 'DEMO';
    expect(() => MindReadiness.fromJson(altered), throwsFormatException);

    final future = validPayload();
    (future['market'] as Map<String, dynamic>)['asOf'] = '2026-11-10T09:00:00Z';
    expect(() => MindReadiness.fromJson(future), throwsFormatException);
  });

  test('unavailable observations may not contain hidden prices or timestamps', () {
    final raw = validPayload();
    raw['market'] = {
      'status': 'UNAVAILABLE', 'source': null, 'asOf': null,
      'observations': 0, 'reasonCode': 'NO_VERIFIED_MARKET_SNAPSHOT',
    };
    final result = MindReadiness.fromJson(raw);
    expect(result.market.status, 'UNAVAILABLE');
    expect(result.market.asOf, isNull);

    (raw['market'] as Map<String, dynamic>)['observations'] = 10;
    expect(() => MindReadiness.fromJson(raw), throwsFormatException);
  });
}

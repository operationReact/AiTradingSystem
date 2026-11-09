import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:trading_client/src/auth/customer_identity.dart';
import 'package:trading_client/src/data/authenticated_api_client.dart';
import 'package:trading_client/src/data/operator_session.dart';
import 'package:trading_client/src/features/mintick_mind/data/mind_strategy_replay_repository.dart';

class _TestIdentity extends UnavailableCustomerIdentity {
  const _TestIdentity();
  @override
  bool get isConfigured => true;
  @override
  Future<String?> accessToken() async => 'test-only-token';
}

Map<String, dynamic> payload() => {
  'contractVersion': 'mind.strategy-replay.v1',
  'generatedAt': '2026-11-09T09:00:00Z',
  'scanRunId': 'd55c1b22-70d1-4e58-8ddb-1a18e3a5067f',
  'strategyCode': 'MA_RECLAIM_V1',
  'strategyVersion': '1',
  'instrument': 'NSE:INFY-EQ',
  'interval': 'FIVE_MINUTE',
  'signalAt': '2026-11-09T08:50:00Z',
  'dataCutoffAt': '2026-11-09T08:50:00Z',
  'candidateFingerprint': 'a' * 64,
  'replayStatus': 'REPRODUCED',
  'decisionPosture': 'WAIT',
  'blockingReasons': [
    'STRUCTURAL_REPLAY_ONLY',
    'SOURCE_KNOWN_AT_NOT_INDEPENDENTLY_PROVEN',
    'ACCOUNT_RISK_NOT_EVALUATED',
    'PAPER_SHADOW_ENROLLMENT_REQUIRED',
  ],
  'independentlyVerifiedSignal': false,
  'paperShadowStatus': 'NOT_ENROLLED',
  'paperOrderSubmitted': false,
  'executionEnabled': false,
};

void main() {
  test('signed-in replay is a single read-only Cognito GET', () async {
    final old = MintTickIdentity.current;
    MintTickIdentity.current = const _TestIdentity();
    addTearDown(() => MintTickIdentity.current = old);
    final session = OperatorSession()..markCustomerAuthenticated();
    final repo = HttpMindStrategyReplayRepository(AuthenticatedApiClient(
      session: session,
      baseUri: Uri.parse('https://api.minttick.com'),
      client: MockClient((request) async {
        expect(request.method, 'GET');
        expect(request.url.path, '/api/customer/mind/v1/strategy-replay');
        expect(request.headers['Authorization'], 'Bearer test-only-token');
        expect(request.headers.containsKey('X-API-Key'), isFalse);
        return http.Response(jsonEncode(payload()), 200);
      }),
    ));
    final response = await repo.load();
    expect(response.decisionPosture, 'WAIT');
    expect(response.paperShadowStatus, 'NOT_ENROLLED');
    expect(response.blockingReasons, contains('ACCOUNT_RISK_NOT_EVALUATED'));
  });

  test('unsafe decisions, execution and fake evidence are rejected', () {
    for (final flag in ['executionEnabled', 'paperOrderSubmitted',
      'independentlyVerifiedSignal']) {
      final p = payload()..[flag] = true;
      expect(() => MindStrategyReplay.fromJson(p), throwsFormatException);
    }
    final buy = payload()..['decisionPosture'] = 'BUY';
    expect(() => MindStrategyReplay.fromJson(buy), throwsFormatException);
    final staleBuy = payload()
      ..['replayStatus'] = 'STALE';
    expect(() => MindStrategyReplay.fromJson(staleBuy), throwsFormatException);
    final missingGate = payload()
      ..['blockingReasons'] = ['STRUCTURAL_REPLAY_ONLY'];
    expect(() => MindStrategyReplay.fromJson(missingGate), throwsFormatException);
  });

  test('future timestamps or no fingerprint cannot claim WAIT', () {
    final future = payload()
      ..['signalAt'] = '2026-11-10T09:00:00Z';
    expect(() => MindStrategyReplay.fromJson(future), throwsFormatException);
    final noFingerprint = payload()
      ..['candidateFingerprint'] = null;
    expect(() => MindStrategyReplay.fromJson(noFingerprint), throwsFormatException);
  });

  test('ABSTAIN with no persisted scan remains usable and non-executable', () {
    final p = payload()
      ..['scanRunId'] = null
      ..['instrument'] = null
      ..['interval'] = null
      ..['signalAt'] = null
      ..['dataCutoffAt'] = null
      ..['candidateFingerprint'] = null
      ..['replayStatus'] = 'UNAVAILABLE'
      ..['decisionPosture'] = 'ABSTAIN'
      ..['blockingReasons'] = [
        'NO_COMPLETED_RESEARCH_SCAN',
        'ACCOUNT_RISK_NOT_EVALUATED',
        'PAPER_SHADOW_ENROLLMENT_REQUIRED',
      ];
    final r = MindStrategyReplay.fromJson(p);
    expect(r.instrument, isNull);
    expect(r.decisionPosture, 'ABSTAIN');
  });
}

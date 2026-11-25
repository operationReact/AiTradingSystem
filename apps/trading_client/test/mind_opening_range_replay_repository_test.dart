import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:trading_client/src/auth/customer_identity.dart';
import 'package:trading_client/src/data/authenticated_api_client.dart';
import 'package:trading_client/src/data/operator_session.dart';
import 'package:trading_client/src/features/mintick_mind/data/mind_opening_range_replay_repository.dart';

class _TestIdentity extends UnavailableCustomerIdentity {
  const _TestIdentity();
  @override
  bool get isConfigured => true;
  @override
  Future<String?> accessToken() async => 'test-only-token';
}

Map<String, dynamic> payload() => {
  'contractVersion': 'mind.opening-range-replay.v1',
  'generatedAt': '2026-11-25T09:10:00Z',
  'scanRunId': 'd55c1b22-70d1-4e58-8ddb-1a18e3a5067f',
  'strategyCode': 'OPENING_RANGE_BREAKOUT_V1',
  'strategyVersion': '1',
  'instrument': 'NSE:INFY-EQ',
  'interval': 'FIVE_MINUTE',
  'signalAt': '2026-11-25T09:00:00Z',
  'dataCutoffAt': '2026-11-25T09:00:00Z',
  'candidateFingerprint': List.filled(64, 'a').join(),
  'replayStatus': 'REPRODUCED',
  'decisionPosture': 'WAIT',
  'blockingReasons': [
    'STRUCTURAL_REPLAY_ONLY',
    'CAPTURED_INPUT_DIGEST_REPRODUCED',
    'SOURCE_KNOWN_AT_NOT_INDEPENDENTLY_PROVEN',
    'AFTER_COST_WALK_FORWARD_NOT_VALIDATED',
    'ACCOUNT_RISK_NOT_EVALUATED',
    'PAPER_SHADOW_ENROLLMENT_REQUIRED',
  ],
  'independentlyVerifiedSignal': false,
  'paperShadowStatus': 'NOT_ENROLLED',
  'paperOrderSubmitted': false,
  'executionEnabled': false,
};

void main() {
  test('opening-range replay is a signed-in read-only GET', () async {
    final previous = MintTickIdentity.current;
    MintTickIdentity.current = const _TestIdentity();
    addTearDown(() => MintTickIdentity.current = previous);
    final session = OperatorSession()..markCustomerAuthenticated();
    final repository = HttpMindOpeningRangeReplayRepository(AuthenticatedApiClient(
      session: session,
      baseUri: Uri.parse('https://api.minttick.com'),
      client: MockClient((request) async {
        expect(request.method, 'GET');
        expect(request.url.path,
            '/api/customer/mind/v1/opening-range-replay');
        expect(request.headers['Authorization'], 'Bearer test-only-token');
        expect(request.headers.containsKey('X-API-Key'), isFalse);
        return http.Response(jsonEncode(payload()), 200);
      }),
    ));

    final replay = await repository.load();

    expect(replay.strategyVersion, '1');
    expect(replay.decisionPosture, 'WAIT');
    expect(replay.interval, 'FIVE_MINUTE');
    expect(replay.blockingReasons,
        contains('ACCOUNT_RISK_NOT_EVALUATED'));
  });

  test('execution flags, unsafe posture and invalid window fail closed', () {
    final executable = payload()..['executionEnabled'] = true;
    expect(() => MindOpeningRangeReplay.fromJson(executable),
        throwsFormatException);
    final buy = payload()..['decisionPosture'] = 'BUY';
    expect(() => MindOpeningRangeReplay.fromJson(buy), throwsFormatException);
    final wrongInterval = payload()..['interval'] = 'ONE_HOUR';
    expect(() => MindOpeningRangeReplay.fromJson(wrongInterval),
        throwsFormatException);
    final future = payload()..['signalAt'] = '2026-11-26T09:00:00Z';
    expect(() => MindOpeningRangeReplay.fromJson(future), throwsFormatException);
  });

  test('ABSTAIN without a captured candidate remains displayable', () {
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

    final replay = MindOpeningRangeReplay.fromJson(p);

    expect(replay.instrument, isNull);
    expect(replay.decisionPosture, 'ABSTAIN');
  });
}

import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:trading_client/src/data/authenticated_api_client.dart';
import 'package:trading_client/src/data/operator_session.dart';
import 'package:trading_client/src/data/scenario_evidence_repository.dart';

Map<String, Object?> _scenarioJson() => {
      'id': '11111111-1111-1111-1111-111111111111',
      'scanRunId': '22222222-2222-2222-2222-222222222222',
      'candidateId': 42,
      'instrumentId': 99,
      'exchange': 'NSE',
      'tradingSymbol': 'INFY-EQ',
      'interval': 'FIVE_MINUTE',
      'strategy': 'MOMENTUM',
      'strategyConfigVersion': 3,
      'entryPrice': 2500,
      'stopPrice': 2450,
      'targetPrice': 2600,
      'historyFrom': '2026-07-01T00:00:00Z',
      'historyTo': '2026-10-01T00:00:00Z',
      'dataCutoffAt': '2026-10-01T00:00:00Z',
      'simulationCount': 1000,
      'horizonBars': 24,
      'blockSize': 4,
      'seed': 42,
      'slippageBps': 5,
      'roundTripCostBps': 10,
      'requestFingerprint': 'fingerprint-1',
      'methodology': 'BLOCK_BOOTSTRAP_V1',
      'regime': 'TRENDING_UP',
      'historicalSamples': 800,
      'eligibleBlockCount': 120,
      'fallbackTier': 'EXACT_REGIME',
      'targetFirstCount': 520,
      'stopFirstCount': 310,
      'neitherCount': 170,
      'targetFirstProbability': 0.52,
      'stopFirstProbability': 0.31,
      'neitherProbability': 0.17,
      'expectedR': 0.42,
      'p05R': -1.0,
      'p50R': 0.30,
      'p95R': 2.0,
      'status': 'COMPLETED',
      'errorCode': null,
      'errorMessage': null,
      'startedAt': '2026-10-01T00:00:01Z',
      'completedAt': '2026-10-01T00:00:03Z',
      'createdAt': '2026-10-01T00:00:00Z',
    };

void main() {
  test('reads persisted scenario evidence by exact run id with authentication', () async {
    final session = OperatorSession()..setOperatorKey('operator-key');
    late http.Request captured;
    final repository = HttpScenarioEvidenceRepository(
      AuthenticatedApiClient(
        session: session,
        baseUri: Uri.parse('http://example.test'),
        client: MockClient((request) async {
          captured = request;
          return http.Response(jsonEncode(_scenarioJson()), 200);
        }),
      ),
    );

    final evidence = await repository.fetch('11111111-1111-1111-1111-111111111111');

    expect(captured.method, 'GET');
    expect(captured.url.path, '/api/research/simulations/11111111-1111-1111-1111-111111111111');
    expect(captured.headers['X-API-Key'], 'operator-key');
    expect(evidence.candidateId, 42);
    expect(evidence.regime, 'TRENDING_UP');
    expect(evidence.methodology, 'BLOCK_BOOTSTRAP_V1');
    expect(evidence.fallbackTier, 'EXACT_REGIME');
    expect(evidence.hasRequiredProvenance, isTrue);
  });

  test('fails closed when required scenario provenance is missing', () async {
    final session = OperatorSession()..setOperatorKey('operator-key');
    final json = _scenarioJson()..['regime'] = '';
    final repository = HttpScenarioEvidenceRepository(
      AuthenticatedApiClient(
        session: session,
        baseUri: Uri.parse('http://example.test'),
        client: MockClient((_) async => http.Response(jsonEncode(json), 200)),
      ),
    );

    await expectLater(
      repository.fetch('11111111-1111-1111-1111-111111111111'),
      throwsA(isA<ApiTransportFailure>().having((failure) => failure.code, 'code', 'INCOMPLETE_SCENARIO_PROVENANCE')),
    );
  });
}

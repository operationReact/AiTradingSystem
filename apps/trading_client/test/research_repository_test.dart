import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:trading_client/src/data/authenticated_api_client.dart';
import 'package:trading_client/src/data/operator_session.dart';
import 'package:trading_client/src/data/research_repository.dart';

void main() {
  test('runs research scan and reads ranked candidates from real contracts', () async {
    final session = OperatorSession()..setOperatorKey('operator-key');
    final client = MockClient((request) async {
      if (request.method == 'POST' && request.url.path == '/api/research/scans') {
        expect(request.url.queryParameters['interval'], 'FIVE_MINUTE');
        expect(request.url.queryParameters['limit'], '100');
        return http.Response(
          jsonEncode({
            'id': 'scan-1',
            'interval': 'FIVE_MINUTE',
            'status': 'SUCCEEDED',
            'requestedLimit': 100,
            'scannedCount': 80,
            'candidateCount': 1,
            'strategySet': 'baseline-v1',
            'startedAt': '2026-10-31T00:00:00Z',
            'completedAt': '2026-10-31T00:00:01Z',
            'errorCode': null,
            'errorMessage': null,
          }),
          200,
        );
      }
      expect(request.method, 'GET');
      expect(request.url.path, '/api/research/scans/scan-1/candidates');
      return http.Response(
        jsonEncode([
          {
            'scanRunId': 'scan-1',
            'instrumentId': 42,
            'tradingSymbol': 'INFY-EQ',
            'exchange': 'NSE',
            'interval': 'FIVE_MINUTE',
            'strategy': 'momentum',
            'score': 0.82,
            'referencePrice': 1515,
            'suggestedStop': 1495,
            'suggestedTarget': 1555,
            'candleTime': '2026-10-31T00:00:00Z',
            'reasons': ['TREND_CONFIRMED'],
            'candidateFingerprint': 'fp-1',
            'candidateId': 7,
          }
        ]),
        200,
      );
    });
    final repository = HttpResearchRepository(
      AuthenticatedApiClient(session: session, client: client, baseUri: Uri.parse('http://example.test')),
    );

    final scan = await repository.runScan();
    final candidates = await repository.candidates(scan.id);

    expect(scan.status, 'SUCCEEDED');
    expect(scan.candidateCount, 1);
    expect(candidates.single.candidateId, 7);
    expect(candidates.single.tradingSymbol, 'INFY-EQ');
  });

  test('submits candidate evaluation with bounded deterministic defaults', () async {
    final session = OperatorSession()..setOperatorKey('operator-key');
    late http.Request captured;
    final repository = HttpResearchRepository(
      AuthenticatedApiClient(
        session: session,
        client: MockClient((request) async {
          captured = request;
          return http.Response(
            jsonEncode({
              'evaluationId': 'eval-1',
              'candidateId': 7,
              'backtestRunId': null,
              'scenarioRunId': 'scenario-1',
              'decisionLabel': 'RESEARCH_SUPPORTED',
              'reasonCodes': ['SCENARIO_POSITIVE'],
              'scenarioReused': false,
            }),
            200,
          );
        }),
        baseUri: Uri.parse('http://example.test'),
      ),
    );

    final result = await repository.evaluate(
      candidateId: 7,
      historyFrom: DateTime.utc(2026, 7, 1),
      historyTo: DateTime.utc(2026, 10, 1),
    );

    final body = jsonDecode(captured.body) as Map<String, dynamic>;
    expect(captured.method, 'POST');
    expect(captured.url.path, '/api/research/candidates/7/evaluate');
    expect(body['simulations'], 1000);
    expect(body['horizonBars'], 24);
    expect(body['blockSize'], 4);
    expect(body['seed'], 42);
    expect(body['slippageBps'], 5);
    expect(body['roundTripCostBps'], 10);
    expect(result.decisionLabel, 'RESEARCH_SUPPORTED');
  });
}

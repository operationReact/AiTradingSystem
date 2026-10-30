import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:trading_client/src/data/authenticated_api_client.dart';
import 'package:trading_client/src/data/backtest_repository.dart';
import 'package:trading_client/src/data/operator_session.dart';

void main() {
  test('reads recent PAPER backtest history', () async {
    final session = OperatorSession()..setOperatorKey('operator-key');
    final repository = HttpBacktestRepository(
      AuthenticatedApiClient(
        session: session,
        client: MockClient((request) async {
          expect(request.method, 'GET');
          expect(request.url.path, '/api/backtests');
          expect(request.url.queryParameters['limit'], '20');
          return http.Response(
            jsonEncode([
              {
                'id': 'run-1',
                'instrumentId': 42,
                'exchange': 'NSE',
                'tradingSymbol': 'INFY-EQ',
                'interval': 'ONE_DAY',
                'fromDate': '2026-01-01',
                'toDate': '2026-06-30',
                'initialCapital': 100000,
                'tradeCount': 8,
                'winningTrades': 5,
                'losingTrades': 3,
                'winRatePercent': 62.5,
                'netPnl': 8500,
                'finalEquity': 108500,
                'totalReturnPercent': 8.5,
                'maxDrawdownPercent': 3.1,
                'profitFactor': 1.7,
                'createdAt': '2026-10-31T00:00:00Z',
              }
            ]),
            200,
          );
        }),
        baseUri: Uri.parse('http://example.test'),
      ),
    );

    final runs = await repository.recent();

    expect(runs.single.tradingSymbol, 'INFY-EQ');
    expect(runs.single.tradeCount, 8);
    expect(runs.single.netPnl, 8500);
  });

  test('submits exact PAPER backtest request payload', () async {
    final session = OperatorSession()..setOperatorKey('operator-key');
    late http.Request captured;
    final repository = HttpBacktestRepository(
      AuthenticatedApiClient(
        session: session,
        client: MockClient((request) async {
          captured = request;
          return http.Response(
            jsonEncode({
              'id': 'run-2',
              'instrumentId': 42,
              'exchange': 'NSE',
              'tradingSymbol': 'INFY-EQ',
              'interval': 'ONE_DAY',
              'fromDate': '2026-01-01',
              'toDate': '2026-06-30',
              'initialCapital': 100000,
              'tradeCount': 0,
              'winningTrades': 0,
              'losingTrades': 0,
              'winRatePercent': 0,
              'netPnl': 0,
              'finalEquity': 100000,
              'totalReturnPercent': 0,
              'maxDrawdownPercent': 0,
              'profitFactor': 0,
              'createdAt': '2026-10-31T00:00:00Z',
            }),
            200,
          );
        }),
        baseUri: Uri.parse('http://example.test'),
      ),
    );

    await repository.run(
      instrumentId: 42,
      interval: 'ONE_DAY',
      fromDate: DateTime(2026, 1, 1),
      toDate: DateTime(2026, 6, 30),
      initialCapital: 100000,
    );

    final body = jsonDecode(captured.body) as Map<String, dynamic>;
    expect(captured.method, 'POST');
    expect(captured.url.path, '/api/backtests');
    expect(body, {
      'instrumentId': 42,
      'interval': 'ONE_DAY',
      'fromDate': '2026-01-01',
      'toDate': '2026-06-30',
      'initialCapital': 100000,
    });
  });
}

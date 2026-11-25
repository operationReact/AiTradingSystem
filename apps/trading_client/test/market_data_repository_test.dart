import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:trading_client/src/data/authenticated_api_client.dart';
import 'package:trading_client/src/data/market_data_repository.dart';
import 'package:trading_client/src/data/operator_session.dart';

void main() {
  test('queries authoritative candle endpoint with exact backend parameters', () async {
    final session = OperatorSession()..setOperatorKey('operator-key');
    late http.Request captured;
    final repository = HttpMarketDataRepository(
      AuthenticatedApiClient(
        session: session,
        client: MockClient((request) async {
          captured = request;
          return http.Response(
            jsonEncode({
              'items': [
                {
                  'id': 1,
                  'instrumentId': 42,
                  'exchange': 'NSE',
                  'tradingSymbol': 'INFY-EQ',
                  'symbolToken': '1594',
                  'catalogueVersion': 'v1',
                  'interval': 'ONE_DAY',
                  'candleTime': '2026-10-30T03:45:00Z',
                  'open': 1500.0,
                  'high': 1520.0,
                  'low': 1490.0,
                  'close': 1515.0,
                  'volume': 12345,
                  'source': 'ANGEL_ONE',
                  'fetchedAt': '2026-10-30T04:00:00Z',
                },
              ],
              'page': 0,
              'size': 100,
              'totalItems': 1,
              'totalPages': 1,
              'hasNext': false,
            }),
            200,
          );
        }),
        baseUri: Uri.parse('http://example.test'),
      ),
    );

    final page = await repository.candles(
      instrumentId: 42,
      interval: 'ONE_DAY',
      fromDate: DateTime(2026, 10, 1),
      toDate: DateTime(2026, 10, 31),
    );

    expect(captured.url.path, '/api/market-data/candles');
    expect(captured.url.queryParameters['instrumentId'], '42');
    expect(captured.url.queryParameters['interval'], 'ONE_DAY');
    expect(captured.url.queryParameters['fromDate'], '2026-10-01');
    expect(captured.url.queryParameters['toDate'], '2026-10-31');
    expect(page.items.single.tradingSymbol, 'INFY-EQ');
    expect(page.items.single.close, 1515.0);
    expect(page.items.single.source, 'ANGEL_ONE');
  });

  test('catalogue sync uses backend-only explicit POST action', () async {
    final session = OperatorSession()..setOperatorKey('operator-key');
    late http.Request captured;
    final repository = HttpMarketDataRepository(
      AuthenticatedApiClient(
        session: session,
        client: MockClient((request) async {
          captured = request;
          return http.Response(jsonEncode({'status': 'SUCCEEDED'}), 200);
        }),
        baseUri: Uri.parse('http://example.test'),
      ),
    );

    await repository.syncInstruments();

    expect(captured.method, 'POST');
    expect(captured.url.path, '/api/market-data/instruments/sync');
  });
}

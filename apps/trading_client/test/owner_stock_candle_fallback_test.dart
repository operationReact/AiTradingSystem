import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:trading_client/src/data/authenticated_api_client.dart';
import 'package:trading_client/src/data/operator_session.dart';
import 'package:trading_client/src/features/trading_rooms/data/customer_market_repository.dart';

Map<String, dynamic> candlePage({required List<Map<String, dynamic>> rows}) => {
  'instrumentId': 42,
  'interval': 'ONE_DAY',
  'items': rows,
  'hasNext': false,
};

Map<String, dynamic> bar(String day, int close) => {
  'candleTime': '${day}T10:00:00Z',
  'open': 100,
  'high': close + 10,
  'low': 90,
  'close': close,
  'volume': 10000,
  'source': 'ANGEL_ONE_OWNER_PRIVATE_ARCHIVE',
  'fetchedAt': '2026-11-18T11:30:00Z',
};

void main() {
  test('owner daily stock chart loads saved archive when common store is empty', () async {
    final paths = <String>[];
    final session = OperatorSession()..setOperatorKey('test-operator-key');
    final repo = HttpCustomerMarketRepository(AuthenticatedApiClient(
      session: session,
      client: MockClient((request) async {
        paths.add(request.url.path);
        if (request.url.path == '/api/customer/market-data/candles') {
          return http.Response(jsonEncode(candlePage(rows: [])), 200);
        }
        if (request.url.path == '/api/customer/owner-history/v1/candles') {
          return http.Response(jsonEncode(candlePage(rows: [
            bar('2026-11-09', 105),
            bar('2026-11-10', 108),
          ])), 200);
        }
        return http.Response('Not found', 404);
      }),
      baseUri: Uri.parse('https://example.test'),
    ));

    final page = await repo.candles(
      instrumentId: 42,
      interval: 'ONE_DAY',
      fromDate: '2026-11-01',
      toDate: '2026-11-18',
    );
    expect(page.items, hasLength(2));
    expect(page.items.last.close, 108);
    expect(page.items.last.source, 'ANGEL_ONE_OWNER_PRIVATE_ARCHIVE');
    expect(paths, ['/api/customer/market-data/candles',
      '/api/customer/owner-history/v1/candles']);
  });

  test('5Y and MAX request only real monthly owner aggregates, never daily fallback', () async {
    final paths = <String>[];
    final session = OperatorSession()..setOperatorKey('test-operator-key');
    final repo = HttpCustomerMarketRepository(AuthenticatedApiClient(
      session: session,
      client: MockClient((request) async {
        paths.add(request.url.path);
        if (request.url.path == '/api/customer/owner-history/v1/monthly-candles') {
          expect(request.url.queryParameters['fromDate'], '2021-11-20');
          return http.Response(jsonEncode({
            ...candlePage(rows: [bar('2022-01-03', 108), bar('2026-11-02', 120)]),
            'interval': 'ONE_MONTH',
          }), 200);
        }
        return http.Response('Do not use common daily candle API', 400);
      }), baseUri: Uri.parse('https://example.test'),
    ));
    final page = await repo.candles(
      instrumentId: 42, interval: 'ONE_MONTH',
      fromDate: '2021-11-20', toDate: '2026-11-20');
    expect(page.items, hasLength(2));
    expect(page.interval, 'ONE_MONTH');
    expect(paths, ['/api/customer/owner-history/v1/monthly-candles']);
  });

  test('empty shared FIVE_MINUTE store falls back only to owner-private intraday', () async {
    final paths = <String>[];
    final repo = HttpCustomerMarketRepository(AuthenticatedApiClient(
      session: OperatorSession()..setOperatorKey('test-operator-key'),
      client: MockClient((request) async {
        paths.add(request.url.path);
        if (request.url.path == '/api/customer/market-data/candles') {
          return http.Response(jsonEncode({...candlePage(rows: []),
            'interval': 'FIVE_MINUTE'}), 200);
        }
        expect(request.url.path,
            '/api/customer/owner-history/v1/intraday-candles');
        expect(request.url.queryParameters['interval'], 'FIVE_MINUTE');
        return http.Response(jsonEncode({...candlePage(rows: [
          {...bar('2026-11-19', 105),
            'source': 'ANGEL_ONE_OWNER_PRIVATE'}
        ]), 'interval': 'FIVE_MINUTE'}), 200);
      }), baseUri: Uri.parse('https://example.test'),
    ));
    final page = await repo.candles(instrumentId: 42,
      interval: 'FIVE_MINUTE', fromDate: '2026-11-15', toDate: '2026-11-19');
    expect(page.items, hasLength(1));
    expect(page.items.single.source, 'ANGEL_ONE_OWNER_PRIVATE');
    expect(paths, ['/api/customer/market-data/candles',
      '/api/customer/owner-history/v1/intraday-candles']);
  });

  test('owner-private intraday 403 never reveals owner data to other customers', () async {
    final repo = HttpCustomerMarketRepository(AuthenticatedApiClient(
      session: OperatorSession()..setOperatorKey('test-operator-key'),
      client: MockClient((request) async {
        if (request.url.path == '/api/customer/market-data/candles') {
          return http.Response(jsonEncode({...candlePage(rows: []),
            'interval': 'ONE_HOUR'}), 200);
        }
        return http.Response('Forbidden', 403);
      }), baseUri: Uri.parse('https://example.test'),
    ));
    final page = await repo.candles(instrumentId: 42,
      interval: 'ONE_HOUR', fromDate: '2026-11-10', toDate: '2026-11-19');
    expect(page.items, isEmpty);
  });

  test('one stored daily candle recovers usable chart series from owner archive', () async {
    final paths = <String>[];
    final session = OperatorSession()..setOperatorKey('test-operator-key');
    final repo = HttpCustomerMarketRepository(AuthenticatedApiClient(
      session: session,
      client: MockClient((request) async {
        paths.add(request.url.path);
        if (request.url.path == '/api/customer/market-data/candles') {
          return http.Response(jsonEncode(candlePage(rows: [bar('2026-11-10', 108)])), 200);
        }
        return http.Response(jsonEncode(candlePage(rows: [
          bar('2026-11-09', 105), bar('2026-11-10', 108),
        ])), 200);
      }), baseUri: Uri.parse('https://example.test'),
    ));
    final page = await repo.candles(instrumentId: 42, interval: 'ONE_DAY',
      fromDate: '2026-11-01', toDate: '2026-11-18');
    expect(page.items, hasLength(2));
    expect(paths.last, '/api/customer/owner-history/v1/candles');
  });

  test('private archive access denied does not expose candles to other customers', () async {
    final session = OperatorSession()..setOperatorKey('test-operator-key');
    final repo = HttpCustomerMarketRepository(AuthenticatedApiClient(
      session: session,
      client: MockClient((request) async {
        if (request.url.path == '/api/customer/market-data/candles') {
          return http.Response(jsonEncode(candlePage(rows: [])), 200);
        }
        return http.Response('Forbidden', 403);
      }),
      baseUri: Uri.parse('https://example.test'),
    ));
    final page = await repo.candles(
      instrumentId: 42,
      interval: 'ONE_DAY',
      fromDate: '2026-11-01',
      toDate: '2026-11-18',
    );
    expect(page.items, isEmpty);
  });

  test('existing database candles do not use the private owner archive', () async {
    final paths = <String>[];
    final session = OperatorSession()..setOperatorKey('test-operator-key');
    final repo = HttpCustomerMarketRepository(AuthenticatedApiClient(
      session: session,
      client: MockClient((request) async {
        paths.add(request.url.path);
        return http.Response(jsonEncode(candlePage(rows: [
          bar('2026-11-09', 105), bar('2026-11-10', 108),
        ])), 200);
      }),
      baseUri: Uri.parse('https://example.test'),
    ));
    final page = await repo.candles(
      instrumentId: 42,
      interval: 'ONE_DAY',
      fromDate: '2026-11-01',
      toDate: '2026-11-18',
    );
    expect(page.items, hasLength(2));
    expect(paths, ['/api/customer/market-data/candles']);
  });
}

import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:trading_client/src/data/authenticated_api_client.dart';
import 'package:trading_client/src/data/home_quote_repository.dart';
import 'package:trading_client/src/data/operator_session.dart';

void main() {
  test('Home resolves canonical NSE equity IDs, fetches quotes, and never sends tokens', () async {
    final session = OperatorSession()..setOperatorKey('test-operator');
    final requests = <http.Request>[];
    final client = MockClient((request) async {
      requests.add(request);
      if (request.url.path.endsWith('/instruments')) {
        final symbol = request.url.queryParameters['query']!;
        final id = {'RELIANCE': 11, 'TCS': 22, 'INFY': 33}[symbol]!;
        return http.Response(jsonEncode({
          'content': [{
            'id': id, 'exchange': 'NSE', 'exchangeSegment': 'NSE_EQ',
            'symbolToken': 'never-leak-this', 'tradingSymbol': '$symbol-EQ',
            'name': symbol, 'instrumentType': 'EQ', 'lotSize': 1,
          }],
          'page': 0, 'size': 10, 'totalElements': 1,
          'totalPages': 1, 'hasNext': false,
        }), 200);
      }
      final id = request.url.queryParameters['instrumentId']!;
      final symbol = {'11': 'RELIANCE', '22': 'TCS', '33': 'INFY'}[id]!;
      return http.Response(jsonEncode({
        'instrumentId': int.parse(id), 'exchange': 'NSE',
        'tradingSymbol': '$symbol-EQ', 'ltp': 200.50,
        'fetchedAt': '2026-11-06T10:00:00Z',
        'source': 'ANGEL_ONE_SMARTAPI',
      }), 200);
    });
    final repo = HttpHomeQuoteRepository(AuthenticatedApiClient(
      session: session, client: client, baseUri: Uri.parse('https://example.test'),
    ));

    final snapshot = await repo.fetch();
    expect(snapshot.quotes.length, 3);
    expect(snapshot.unavailable, 0);
    expect(snapshot.quotes.first.ltp, 200.50);
    expect(snapshot.quotes.first.tradingSymbol, 'RELIANCE-EQ');
    expect(requests.length, 6);
    for (final request in requests) {
      expect(request.url.toString(), isNot(contains('never-leak-this')));
      expect(request.url.queryParameters.containsKey('symbolToken'), isFalse);
      expect(request.headers['X-API-Key'], 'test-operator');
    }
  });

  test('Home never treats an invalid or mismatched quote as a price', () async {
    final session = OperatorSession()..setOperatorKey('test-operator');
    final client = MockClient((request) async {
      if (request.url.path.endsWith('/instruments')) {
        final symbol = request.url.queryParameters['query']!;
        return http.Response(jsonEncode({
          'content': [{'id': 42, 'exchange': 'NSE', 'tradingSymbol': '$symbol-EQ',
          'instrumentType': 'EQ'}],
        }), 200);
      }
      return http.Response(jsonEncode({
        'instrumentId': 999, 'exchange': 'NSE',
        'tradingSymbol': 'WRONG-EQ', 'ltp': -12,
        'fetchedAt': '2026-11-06T10:00:00Z', 'source': 'ANGEL_ONE_SMARTAPI',
      }), 200);
    });
    final repo = HttpHomeQuoteRepository(AuthenticatedApiClient(
      session: session, client: client, baseUri: Uri.parse('https://example.test'),
    ));
    final result = await repo.fetch();
    expect(result.quotes, isEmpty);
    expect(result.unavailable, 3);
  });

  test('Home requires login before any network call', () async {
    var requests = 0;
    final client = MockClient((request) async {
      requests++;
      return http.Response('{}', 200);
    });
    final repo = HttpHomeQuoteRepository(AuthenticatedApiClient(
      session: OperatorSession(), client: client,
      baseUri: Uri.parse('https://example.test'),
    ));
    await expectLater(repo.fetch(), throwsA(isA<ApiAuthenticationRequired>()));
    expect(requests, 0);
  });
}

import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:trading_client/src/data/authenticated_api_client.dart';
import 'package:trading_client/src/data/instrument_repository.dart';
import 'package:trading_client/src/data/operator_session.dart';

void main() {
  test('parses authoritative catalogue status and search page', () async {
    final session = OperatorSession()..setOperatorKey('operator-key');
    final client = MockClient((request) async {
      if (request.url.path.endsWith('/status')) {
        return http.Response(
          jsonEncode({
            'version': 'catalogue-v1',
            'activatedAt': '2026-10-31T00:00:00Z',
            'activeCount': 1,
          }),
          200,
        );
      }
      return http.Response(
        jsonEncode({
          'content': [
            {
              'id': 42,
              'exchangeSegment': 'nse_cm',
              'exchange': 'NSE',
              'symbolToken': '1594',
              'tradingSymbol': 'INFY-EQ',
              'name': 'INFOSYS LIMITED',
              'instrumentType': 'EQ',
              'expiry': null,
              'strike': null,
              'lotSize': 1,
              'tickSize': 0.05,
              'catalogueVersion': 'catalogue-v1',
            },
          ],
          'page': 0,
          'size': 20,
          'totalElements': 1,
          'totalPages': 1,
          'hasNext': false,
          'catalogueVersion': 'catalogue-v1',
          'activatedAt': '2026-10-31T00:00:00Z',
        }),
        200,
      );
    });
    final repository = HttpInstrumentRepository(
      AuthenticatedApiClient(
        session: session,
        client: client,
        baseUri: Uri.parse('http://example.test'),
      ),
    );

    final status = await repository.status();
    final page = await repository.search(query: 'INFY', exchange: 'NSE');

    expect(status.activeCount, 1);
    expect(status.version, 'catalogue-v1');
    expect(page.totalElements, 1);
    expect(page.content.single.id, 42);
    expect(page.content.single.tradingSymbol, 'INFY-EQ');
    expect(page.content.single.exchange, 'NSE');
  });

  test('importLatest uses explicit operator-triggered POST endpoint', () async {
    final session = OperatorSession()..setOperatorKey('operator-key');
    late http.Request captured;
    final repository = HttpInstrumentRepository(
      AuthenticatedApiClient(
        session: session,
        client: MockClient((request) async {
          captured = request;
          return http.Response(jsonEncode({'status': 'SUCCEEDED'}), 200);
        }),
        baseUri: Uri.parse('http://example.test'),
      ),
    );

    await repository.importLatest();

    expect(captured.method, 'POST');
    expect(captured.url.path, '/api/instruments/import');
  });
}

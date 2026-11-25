import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:trading_client/src/data/authenticated_api_client.dart';
import 'package:trading_client/src/data/operator_session.dart';

void main() {
  test('rejects requests before operator authentication', () async {
    final api = AuthenticatedApiClient(
      session: OperatorSession(),
      client: MockClient((_) async => http.Response('{}', 200)),
      baseUri: Uri.parse('http://example.test'),
    );

    expect(api.getJson('/api/instruments/status'), throwsA(isA<ApiAuthenticationRequired>()));
  });

  test('sends X-API-Key and preserves query parameters', () async {
    final session = OperatorSession()..setOperatorKey('operator-key');
    late http.Request captured;
    final api = AuthenticatedApiClient(
      session: session,
      client: MockClient((request) async {
        captured = request;
        return http.Response(jsonEncode({'ok': true}), 200, headers: {'x-correlation-id': 'corr-1'});
      }),
      baseUri: Uri.parse('http://example.test'),
    );

    final result = await api.getJson(
      '/api/instruments/search',
      query: {'q': 'INFY', 'page': '0', 'unused': null},
    );

    expect(captured.headers['X-API-Key'], 'operator-key');
    expect(captured.url.path, '/api/instruments/search');
    expect(captured.url.queryParameters['q'], 'INFY');
    expect(captured.url.queryParameters.containsKey('unused'), isFalse);
    expect(result, {'ok': true});
  });

  test('maps backend authorization rejection to stable failure', () async {
    final session = OperatorSession()..setOperatorKey('wrong');
    final api = AuthenticatedApiClient(
      session: session,
      client: MockClient((_) async => http.Response('', 401, headers: {'x-correlation-id': 'corr-auth'})),
      baseUri: Uri.parse('http://example.test'),
    );

    expect(
      api.getJson('/api/instruments/status'),
      throwsA(
        isA<ApiHttpFailure>()
            .having((failure) => failure.code, 'code', 'AUTHENTICATION_REJECTED')
            .having((failure) => failure.statusCode, 'statusCode', 401)
            .having((failure) => failure.correlationId, 'correlationId', 'corr-auth'),
      ),
    );
  });
}

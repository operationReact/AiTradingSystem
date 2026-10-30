import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:trading_client/src/data/authenticated_api_client.dart';
import 'package:trading_client/src/data/operator_session.dart';

void main() {
  test('PUT preserves authenticated JSON contract', () async {
    final session = OperatorSession()..setOperatorKey('authoritative-key');
    late http.Request captured;
    final api = AuthenticatedApiClient(
      session: session,
      baseUri: Uri.parse('http://example.test'),
      client: MockClient((request) async {
        captured = request;
        return http.Response('{"ok":true}', 200);
      }),
    );

    final decoded = await api.putJson(
      '/api/risk/emergency-stop',
      body: const {'halted': true, 'reason': 'operator safety halt'},
    );

    expect(captured.method, 'PUT');
    expect(captured.url.path, '/api/risk/emergency-stop');
    expect(captured.headers['X-API-Key'], 'authoritative-key');
    expect(captured.headers['Accept'], 'application/json');
    expect(captured.headers['Content-Type'], startsWith('application/json'));
    expect(jsonDecode(captured.body), {
      'halted': true,
      'reason': 'operator safety halt',
    });
    expect(decoded, {'ok': true});
  });
}

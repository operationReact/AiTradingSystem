import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:trading_client/src/data/authenticated_api_client.dart';
import 'package:trading_client/src/data/operator_session.dart';

void main() {
  test('feature headers cannot override the authenticated X-API-Key', () async {
    final session = OperatorSession()..setOperatorKey('authoritative-key');
    late http.Request captured;
    final api = AuthenticatedApiClient(
      session: session,
      baseUri: Uri.parse('http://example.test'),
      client: MockClient((request) async {
        captured = request;
        return http.Response('{}', 200);
      }),
    );

    await api.postJson(
      '/api/paper-orders',
      body: const {'symbol': 'INFY-EQ'},
      headers: const {
        'Idempotency-Key': 'paper-test-1234',
        'X-API-Key': 'attempted-override',
        'Accept': 'text/plain',
      },
    );

    expect(captured.headers['X-API-Key'], 'authoritative-key');
    expect(captured.headers['Idempotency-Key'], 'paper-test-1234');
    expect(captured.headers['Accept'], 'application/json');
    expect(captured.headers['Content-Type'], startsWith('application/json'));
  });
}

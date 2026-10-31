import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:trading_client/src/core/session/operator_session.dart';
import 'package:trading_client/src/features/operations_health/data/health_repository.dart';

void main() {
  test('uses the backend X-API-Key contract and only the health GET surface', () async {
    final session = OperatorSession()..setOperatorKey('contract-key');
    late http.Request captured;
    final client = MockClient((request) async {
      captured = request;
      return http.Response(
        '{"state":"DEGRADED","executionMode":"PAPER","liveAvailable":false,"components":[]}',
        200,
        headers: {'content-type': 'application/json'},
      );
    });

    final repository = HttpHealthRepository(
      session: session,
      client: client,
      baseUri: Uri.parse('http://example.test:8080'),
    );
    await repository.fetch();

    expect(captured.method, 'GET');
    expect(captured.url.path, '/api/operations/health');
    expect(captured.headers['X-API-Key'], 'contract-key');
    expect(captured.headers.containsKey('X-Operator-Key'), isFalse);
  });
}

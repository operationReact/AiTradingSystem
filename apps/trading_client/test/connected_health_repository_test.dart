import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/core/session/operator_session.dart';
import 'package:trading_client/src/features/operations_health/data/health_repository.dart';
import 'package:trading_client/src/features/operations_health/domain/health_snapshot.dart';

const connectedApiBaseUrl = String.fromEnvironment('CONNECTED_API_BASE_URL');
const connectedApiKey = String.fromEnvironment('CONNECTED_API_KEY');
const connectedGateConfigured = connectedApiBaseUrl != '' && connectedApiKey != '';

void main() {
  test(
    'real Spring/PostgreSQL health endpoint accepts valid auth and returns fail-safe evidence',
    () async {
      final session = OperatorSession()..setOperatorKey(connectedApiKey);
      final repository = HttpHealthRepository(session: session, baseUri: Uri.parse(connectedApiBaseUrl));
      final snapshot = await repository.fetch();

      expect(snapshot.executionMode, 'PAPER');
      expect(snapshot.liveAvailable, isFalse);
      expect(snapshot.components, isNotEmpty);
      expect(snapshot.components.any((component) => component.component == 'database' && component.state == HealthState.healthy), isTrue);
      expect(snapshot.state, isNot(HealthState.healthy), reason: 'A fresh CI database with no provider session/freshness evidence must not be represented as fully healthy');
    },
    skip: connectedGateConfigured ? false : 'connected-runtime dart-defines are not configured',
  );

  test(
    'real backend rejects an incorrect operator key',
    () async {
      final session = OperatorSession()..setOperatorKey('${connectedApiKey}wrong');
      final repository = HttpHealthRepository(session: session, baseUri: Uri.parse(connectedApiBaseUrl));
      await expectLater(
        repository.fetch(),
        throwsA(isA<HealthHttpFailure>().having((failure) => failure.statusCode, 'statusCode', 401)),
      );
    },
    skip: connectedGateConfigured ? false : 'connected-runtime dart-defines are not configured',
  );
}

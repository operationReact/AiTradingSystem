import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/app.dart';
import 'package:trading_client/src/data/health_repository.dart';
import 'package:trading_client/src/data/operator_session.dart';
import 'package:trading_client/src/domain/health_snapshot.dart';

class SmokeHealthRepository implements HealthRepository {
  @override
  Future<HealthSnapshot> fetch() async => HealthSnapshot.fromJson({
        'checkedAt': '2026-10-30T10:00:00Z',
        'state': 'HEALTHY',
        'executionMode': 'PAPER',
        'liveAvailable': false,
        'emergencyStopActive': false,
        'components': const [],
      });
}

void main() {
  testWidgets('boots into the operations-health route without implicit network activity', (tester) async {
    final session = OperatorSession();
    await tester.pumpWidget(
      TradingApp(repositoryFactory: SmokeHealthRepository.new, session: session),
    );
    expect(find.text('Operations health'), findsOneWidget);
    expect(find.text('Connect to load authoritative operational health evidence.'), findsOneWidget);
  });
}

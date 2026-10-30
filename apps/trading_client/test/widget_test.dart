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
  testWidgets('boots into the product overview instead of operations health', (tester) async {
    final session = OperatorSession();
    await tester.pumpWidget(
      TradingApp(repositoryFactory: SmokeHealthRepository.new, session: session),
    );

    expect(find.text('AiTradingSystem'), findsOneWidget);
    expect(find.text('Research. Validate. Operate with control.'), findsOneWidget);
    expect(find.text('Operations health'), findsOneWidget);
    expect(find.text('Development operator session'), findsNothing);
  });
}

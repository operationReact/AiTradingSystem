import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/app.dart';
import 'package:trading_client/src/data/health_repository.dart';
import 'package:trading_client/src/data/operator_session.dart';
import 'package:trading_client/src/domain/health_snapshot.dart';
import 'package:trading_client/src/theme/app_theme.dart';

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

void _phoneViewport(WidgetTester tester) {
  tester.view.devicePixelRatio = 1;
  tester.view.physicalSize = const Size(390, 844);
  addTearDown(() {
    tester.view.resetPhysicalSize();
    tester.view.resetDevicePixelRatio();
  });
}

void main() {
  testWidgets('boots into the mobile product shell without operator chrome', (tester) async {
    _phoneViewport(tester);
    await tester.pumpWidget(
      TradingApp(
        repositoryFactory: SmokeHealthRepository.new,
        session: OperatorSession(),
      ),
    );
    await tester.pump();

    expect(find.byKey(const ValueKey('product-mobile-navigation')), findsOneWidget);
    expect(find.text('Home'), findsOneWidget);
    expect(find.text('Markets'), findsOneWidget);
    expect(find.text('Research'), findsOneWidget);
    expect(find.text('Portfolio'), findsOneWidget);
    expect(find.text('More'), findsOneWidget);

    expect(find.text('Development operator session'), findsNothing);
    expect(find.text('Operator key'), findsNothing);
    expect(find.text('Backend execution-mode authority'), findsNothing);
    expect(find.byKey(const ValueKey('global-design-style-control')), findsNothing);
    expect(find.byKey(const ValueKey('global-workspace-navigation')), findsNothing);
  });

  testWidgets('appearance is a product setting and profile is separate from color mode', (tester) async {
    _phoneViewport(tester);
    await tester.pumpWidget(
      TradingApp(
        repositoryFactory: SmokeHealthRepository.new,
        session: OperatorSession(),
        initialRoute: '/settings',
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('Appearance'), findsOneWidget);
    expect(find.text('Neon Futuristic AI'), findsOneWidget);
    expect(find.text('Clean Modern'), findsOneWidget);
    expect(find.text('Color mode'), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('profile-Clean Modern')));
    await tester.pumpAndSettle();

    final themeControl = tester.widget<SegmentedButton<ThemeMode>>(
      find.byKey(const ValueKey('settings-theme-mode-control')),
    );
    expect(themeControl.selected, {ThemeMode.system});
    expect(find.byKey(const ValueKey('product-mobile-navigation')), findsOneWidget);
  });

  testWidgets('system health never exposes development credentials or raw auth codes', (tester) async {
    _phoneViewport(tester);
    await tester.pumpWidget(
      TradingApp(
        repositoryFactory: SmokeHealthRepository.new,
        session: OperatorSession(),
        initialRoute: '/operations/health',
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('System health'), findsOneWidget);
    expect(find.byKey(const ValueKey('health-sign-in-required')), findsOneWidget);
    expect(find.text('Sign in to view system health'), findsOneWidget);
    expect(find.text('Development operator session'), findsNothing);
    expect(find.byKey(const ValueKey('operator-key-field')), findsNothing);
    expect(find.text('AUTHENTICATION_REQUIRED'), findsNothing);
  });

  testWidgets('authenticated system health renders PAPER safety evidence', (tester) async {
    _phoneViewport(tester);
    final session = OperatorSession()..setOperatorKey('test-session');
    await tester.pumpWidget(
      TradingApp(
        repositoryFactory: SmokeHealthRepository.new,
        session: session,
        initialRoute: '/operations/health',
      ),
    );
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('health-summary')), findsOneWidget);
    expect(find.text('PAPER'), findsOneWidget);
    expect(find.text('Live trading'), findsOneWidget);
    expect(find.text('LOCKED'), findsOneWidget);
  });
}

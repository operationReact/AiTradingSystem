import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/app.dart';
import 'package:trading_client/src/data/health_repository.dart';
import 'package:trading_client/src/data/operator_session.dart';
import 'package:trading_client/src/domain/health_snapshot.dart';
import 'package:trading_client/src/presentation/sign_in_page.dart';

class _SignInHealthRepository implements HealthRepository {
  @override
  Future<HealthSnapshot> fetch() async => HealthSnapshot.fromJson({
        'checkedAt': '2026-11-01T10:00:00Z',
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
  testWidgets('secure sign-in follows the approved customer-first hierarchy', (tester) async {
    _phoneViewport(tester);

    await tester.pumpWidget(
      const MaterialApp(
        debugShowCheckedModeBanner: false,
        home: SignInPage(),
      ),
    );
    await tester.pump();

    expect(find.text('AI Trading'), findsOneWidget);
    expect(find.text('Trade Smarter\nwith AI'), findsOneWidget);
    expect(
      find.text('AI research  •  Risk-first trading  •\nReal market insights'),
      findsOneWidget,
    );

    expect(find.byKey(const ValueKey('secure-sign-in-google')), findsOneWidget);
    expect(find.byKey(const ValueKey('secure-sign-in-apple')), findsOneWidget);
    expect(find.byKey(const ValueKey('secure-sign-in-email')), findsOneWidget);
    expect(find.byKey(const ValueKey('secure-sign-in-create-account')), findsOneWidget);

    expect(find.textContaining('Angel One'), findsNothing);
    expect(find.textContaining('Operator key'), findsNothing);
    expect(find.textContaining('Development operator'), findsNothing);
    expect(tester.takeException(), isNull);
  });

  testWidgets('Home secure sign-in opens a full-screen auth journey', (tester) async {
    _phoneViewport(tester);

    await tester.pumpWidget(
      TradingApp(
        repositoryFactory: _SignInHealthRepository.new,
        session: OperatorSession(),
      ),
    );
    await tester.pump();

    expect(find.byKey(const ValueKey('product-mobile-navigation')), findsOneWidget);
    final signIn = find.byKey(const ValueKey('dashboard-secure-sign-in'));
    expect(signIn, findsOneWidget);
    await tester.ensureVisible(signIn);
    await tester.tap(signIn);
    await tester.pumpAndSettle();

    expect(find.text('Trade Smarter\nwith AI'), findsOneWidget);
    expect(find.byKey(const ValueKey('product-mobile-navigation')), findsNothing);
    expect(find.text('Home'), findsNothing);
    expect(find.text('Markets'), findsNothing);
    expect(find.text('Portfolio'), findsNothing);
    expect(tester.takeException(), isNull);
  });

  testWidgets('direct sign-in route is full screen without product navigation', (tester) async {
    _phoneViewport(tester);

    await tester.pumpWidget(
      TradingApp(
        repositoryFactory: _SignInHealthRepository.new,
        session: OperatorSession(),
        initialRoute: '/sign-in',
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('Trade Smarter\nwith AI'), findsOneWidget);
    expect(find.byKey(const ValueKey('product-mobile-navigation')), findsNothing);
    expect(tester.takeException(), isNull);
  });
}

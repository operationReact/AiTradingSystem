import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:trading_client/src/app.dart';
import 'package:trading_client/src/brand/minttick_brand.dart';
import 'package:trading_client/src/data/health_repository.dart';
import 'package:trading_client/src/data/operator_session.dart';
import 'package:trading_client/src/domain/health_snapshot.dart';
import 'package:trading_client/src/presentation/welcome_page.dart';

class _NoHealth implements HealthRepository {
  @override
  Future<HealthSnapshot> fetch() =>
      throw StateError('Guest opening screen must not call backend.');
}

void main() {
  setUp(() => SharedPreferences.setMockInitialValues({}));

  Future<void> pumpWelcome(
    WidgetTester tester, {
    double width = 390,
    double height = 844,
    bool disableAnimations = false,
  }) async {
    tester.view.devicePixelRatio = 1;
    tester.view.physicalSize = Size(width, height);
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(MaterialApp(
      home: const MintTickWelcomePage(),
      builder: (context, child) => MediaQuery(
        data: MediaQuery.of(context).copyWith(disableAnimations: disableAnimations),
        child: child!,
      ),
      routes: {
        '/sign-in': (_) => const Scaffold(body: Text('Sign in destination')),
        '/sign-up': (_) => const Scaffold(body: Text('Create account destination')),
      },
    ));
    await tester.pumpAndSettle();
  }

  for (final width in [320.0, 390.0, 430.0]) {
    testWidgets('approved brand-only welcome at ${width.toInt()}dp', (tester) async {
      await pumpWelcome(tester, width: width);
      expect(find.byType(MintTickLockup), findsOneWidget);
      expect(find.byType(MintTickMark), findsOneWidget);
      expect(find.text('Sign in'), findsOneWidget);
      expect(find.text('Create account'), findsOneWidget);
      expect(find.text('Sound off'), findsOneWidget);
      expect(find.text('Replay intro'), findsOneWidget);
      expect(find.text('Trade with clarity.'), findsNothing);
      expect(find.textContaining('market tools'), findsNothing);
      expect(tester.takeException(), isNull);
    });
  }


  testWidgets('real TradingApp first launch shows sign in and create account, not Done',
      (tester) async {
    tester.view.devicePixelRatio = 1;
    tester.view.physicalSize = const Size(390, 844);
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    // Mirrors main.dart: MaterialApp.initialRoute '/welcome' can create
    // a '/' route underneath it, making Navigator.canPop() true.
    await tester.pumpWidget(TradingApp(
      repositoryFactory: _NoHealth.new,
      session: OperatorSession(),
      initialRoute: '/welcome',
    ));
    await tester.pumpAndSettle();

    expect(find.byType(MintTickMark), findsOneWidget);
    expect(find.byKey(const ValueKey('minttick-welcome-sign-in')), findsOneWidget);
    expect(find.byKey(const ValueKey('minttick-welcome-create-account')),
        findsOneWidget);
    expect(find.byKey(const ValueKey('minttick-welcome-done')), findsNothing);
    expect(tester.takeException(), isNull);
  });

  testWidgets('Settings replay explicitly shows Done and returns to Settings',
      (tester) async {
    await tester.pumpWidget(MaterialApp(
      home: Builder(
        builder: (context) => Scaffold(
          body: TextButton(
            onPressed: () => Navigator.of(context).pushNamed('/welcome-replay'),
            child: const Text('Open brand replay'),
          ),
        ),
      ),
      routes: {
        '/welcome-replay': (_) =>
            const MintTickWelcomePage(replayFromSettings: true),
      },
    ));
    await tester.tap(find.text('Open brand replay'));
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('minttick-welcome-done')), findsOneWidget);
    expect(find.byKey(const ValueKey('minttick-welcome-sign-in')), findsNothing);
    expect(find.byKey(const ValueKey('minttick-welcome-create-account')),
        findsNothing);

    await tester.tap(find.byKey(const ValueKey('minttick-welcome-done')));
    await tester.pumpAndSettle();
    expect(find.text('Open brand replay'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('sign in navigates without waiting for the reveal or audio', (tester) async {
    await pumpWelcome(tester);
    await tester.tap(find.byKey(const ValueKey('minttick-welcome-sign-in')));
    await tester.pumpAndSettle();
    expect(find.text('Sign in destination'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('create account navigates to distinct registration route', (tester) async {
    await pumpWelcome(tester);
    await tester.tap(find.byKey(const ValueKey('minttick-welcome-create-account')));
    await tester.pumpAndSettle();
    expect(find.text('Create account destination'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('reduced motion renders approved logo and both actions', (tester) async {
    await pumpWelcome(tester, disableAnimations: true);
    expect(find.byType(MintTickMark), findsOneWidget);
    expect(find.byKey(const ValueKey('minttick-welcome-sign-in')), findsOneWidget);
    expect(find.byKey(const ValueKey('minttick-welcome-create-account')), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
}

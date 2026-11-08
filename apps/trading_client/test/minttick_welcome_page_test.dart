import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:trading_client/src/app.dart';
import 'package:trading_client/src/auth/customer_identity.dart';
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

class _ControlledCognito extends UnavailableCustomerIdentity {
  _ControlledCognito(this.onSignIn);

  final Future<LoginStep> Function() onSignIn;
  int signInCalls = 0;

  @override
  Future<LoginStep> signIn({required String username, required String password}) {
    signInCalls++;
    return onSignIn();
  }
}

void main() {
  setUp(() => SharedPreferences.setMockInitialValues({}));
  tearDown(() => MintTickIdentity.current = const UnavailableCustomerIdentity());

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
      // Regression: previously the outer 390px box was centered while
      // the visible mark + wordmark remained left-aligned within it.
      expect(tester.getCenter(find.byType(MintTickLockup)).dx,
          closeTo(width / 2, 1.5));
      expect(find.text('Sign in'), findsOneWidget);
      expect(find.text('Create account'), findsOneWidget);
      expect(find.byKey(const ValueKey('minttick-inline-username')), findsOneWidget);
      expect(find.byKey(const ValueKey('minttick-inline-password')), findsOneWidget);
      expect(find.text('Sound off'), findsNothing);
      expect(find.text('Replay intro'), findsNothing);
      expect(find.text('Trade with clarity.'), findsNothing);
      expect(find.textContaining('market tools'), findsNothing);
      expect(tester.takeException(), isNull);
    });
  }


  testWidgets('logo has a visible finite entrance without shifting sign-in fields',
      (tester) async {
    tester.view.devicePixelRatio = 1;
    tester.view.physicalSize = const Size(390, 844);
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(const MaterialApp(home: MintTickWelcomePage()));

    MintTickLockup logo = tester.widget(find.byType(MintTickLockup));
    final initialUsernameY = tester.getTopLeft(
      find.byKey(const ValueKey('minttick-inline-username')),
    ).dy;
    expect(logo.markScale, lessThan(0.85));
    expect(logo.wordmarkOpacity, 0);

    await tester.pump(const Duration(milliseconds: 850));
    logo = tester.widget(find.byType(MintTickLockup));
    expect(logo.markScale, greaterThan(0.95));
    expect(logo.wordmarkOpacity, greaterThan(0.0));
    expect(logo.wordmarkOpacity, lessThan(1.0));

    await tester.pumpAndSettle();
    logo = tester.widget(find.byType(MintTickLockup));
    expect(logo.markScale, closeTo(1.0, 0.001));
    expect(logo.markOpacity, 1.0);
    expect(logo.wordmarkOpacity, 1.0);
    expect(logo.wordmarkOffset, 0);
    expect(tester.getCenter(find.byType(MintTickLockup)).dx,
        closeTo(195.0, 1.5));
    expect(tester.getTopLeft(
      find.byKey(const ValueKey('minttick-inline-username')),
    ).dy, closeTo(initialUsernameY, 1.0));
    expect(tester.takeException(), isNull);
  });

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
    final staticLogo = tester.widget<MintTickLockup>(
        find.byType(MintTickLockup));
    expect(staticLogo.markScale, closeTo(1.0, 0.001));
    expect(staticLogo.wordmarkOpacity, 1.0);
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

  testWidgets('inline sign-in validates without navigating or sending credentials', (tester) async {
    await pumpWelcome(tester);
    await tester.tap(find.byKey(const ValueKey('minttick-welcome-sign-in')));
    await tester.pumpAndSettle();
    expect(find.text('Enter your email or username'), findsOneWidget);
    expect(find.text('Enter your password'), findsOneWidget);
    await tester.enterText(find.byKey(const ValueKey('minttick-inline-username')), 'sample-user');
    await tester.enterText(find.byKey(const ValueKey('minttick-inline-password')), 'not-a-real-password');
    await tester.tap(find.byKey(const ValueKey('minttick-welcome-sign-in')));
    await tester.pump();
    expect(find.textContaining('not configured yet'), findsOneWidget);
    expect(find.text('Sign in destination'), findsNothing);
    expect(tester.takeException(), isNull);
  });

  testWidgets('stalled Cognito sign-in times out and enables retry',
      (tester) async {
    final pending = Completer<LoginStep>();
    final identity = _ControlledCognito(() => pending.future);
    MintTickIdentity.current = identity;
    await pumpWelcome(tester);
    await tester.enterText(
      find.byKey(const ValueKey('minttick-inline-username')), 'example-user');
    await tester.enterText(
      find.byKey(const ValueKey('minttick-inline-password')), 'example-password');
    final button = find.byKey(const ValueKey('minttick-welcome-sign-in'));
    await tester.tap(button);
    await tester.pump();
    expect(find.byType(CircularProgressIndicator), findsOneWidget);
    expect(identity.signInCalls, 1);

    await tester.pump(const Duration(seconds: 26));
    await tester.pump();
    expect(find.textContaining('Cognito sign-in timed out'), findsOneWidget);
    expect(find.byType(CircularProgressIndicator), findsNothing);
    await tester.tap(button);
    await tester.pump();
    expect(identity.signInCalls, 2);
  });

  testWidgets('backend verification timeout identifies stage and clears spinner',
      (tester) async {
    MintTickIdentity.current = _ControlledCognito(
        () async => LoginStep.complete);
    final provision = Completer<String>();
    await tester.pumpWidget(MaterialApp(
      home: MintTickWelcomePage(
        provisionAccount: () => provision.future,
        onAuthenticated: () {},
      ),
    ));
    await tester.pumpAndSettle();
    await tester.enterText(
      find.byKey(const ValueKey('minttick-inline-username')), 'example-user');
    await tester.enterText(
      find.byKey(const ValueKey('minttick-inline-password')), 'example-password');
    await tester.tap(find.byKey(const ValueKey('minttick-welcome-sign-in')));
    await tester.pump();
    expect(find.byType(CircularProgressIndicator), findsOneWidget);

    await tester.pump(const Duration(seconds: 21));
    await tester.pump();
    expect(find.textContaining('MintTick account verification timed out'),
        findsOneWidget);
    expect(find.byType(CircularProgressIndicator), findsNothing);
  });

  testWidgets('create account navigates to distinct registration route', (tester) async {
    await pumpWelcome(tester);
    await tester.tap(find.byKey(const ValueKey('minttick-welcome-create-account')));
    await tester.pumpAndSettle();
    expect(find.text('Create account destination'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('password visibility toggles and never reveals plaintext by default', (tester) async {
    await pumpWelcome(tester);
    final field = find.byKey(const ValueKey('minttick-inline-password'));
    expect(tester.widget<EditableText>(find.descendant(of: field, matching: find.byType(EditableText))).obscureText, isTrue);
    await tester.tap(find.byKey(const ValueKey('minttick-password-visibility')));
    await tester.pump();
    expect(tester.widget<EditableText>(find.descendant(of: field, matching: find.byType(EditableText))).obscureText, isFalse);
  });

  testWidgets('reduced motion renders approved logo and both actions', (tester) async {
    await pumpWelcome(tester, disableAnimations: true);
    expect(find.byType(MintTickMark), findsOneWidget);
    expect(find.byKey(const ValueKey('minttick-welcome-sign-in')), findsOneWidget);
    expect(find.byKey(const ValueKey('minttick-welcome-create-account')), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
}

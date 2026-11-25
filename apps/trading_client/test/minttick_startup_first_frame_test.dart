import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/bootstrap.dart';
import 'package:trading_client/src/auth/customer_identity.dart';
import 'package:trading_client/src/core/session/operator_session.dart';
import 'package:trading_client/src/presentation/welcome_page.dart';

class _StartupCountingIdentity extends UnavailableCustomerIdentity {
  const _StartupCountingIdentity(this.counter);

  final List<String> counter;

  @override
  bool get isConfigured => true;

  @override
  Future<bool> hasSession() async {
    counter.add('hasSession');
    return true;
  }

  @override
  Future<void> signOut() async {
    counter.add('signOut');
  }
}

void main() {
  test('local web debug avoids the Amplify cached-session admission queue',
      () async {
    final calls = <String>[];
    final session = OperatorSession();
    final route = await restoreMintTickSessionAfterInit(
      session,
      identity: _StartupCountingIdentity(calls),
      skipCachedSession: true,
      readRememberMe: () async {
        calls.add('preferences');
        return true;
      },
      provision: () async => calls.add('provision'),
    );
    expect(route, '/welcome');
    expect(calls, isEmpty, reason: 'No Amplify Auth call before signIn');
    expect(session.isCustomerAuthenticated, isFalse);
  });

  test('normal startup still signs out when remember-me is false',
      () async {
    final calls = <String>[];
    final session = OperatorSession();
    final route = await restoreMintTickSessionAfterInit(
      session,
      identity: _StartupCountingIdentity(calls),
      skipCachedSession: false,
      readRememberMe: () async => false,
      provision: () async => calls.add('provision'),
    );
    expect(route, '/welcome');
    expect(calls, ['hasSession', 'signOut']);
    expect(session.isCustomerAuthenticated, isFalse);
  });

  test('normal restoration requires real backend provisioning',
      () async {
    final calls = <String>[];
    final session = OperatorSession();
    final route = await restoreMintTickSessionAfterInit(
      session,
      identity: _StartupCountingIdentity(calls),
      skipCachedSession: false,
      readRememberMe: () async => true,
      provision: () async => calls.add('provision'),
    );
    expect(route, '/');
    expect(calls, ['hasSession', 'provision']);
    expect(session.isCustomerAuthenticated, isTrue);
  });

  test('failed backend provisioning never grants customer access',
      () async {
    final calls = <String>[];
    final session = OperatorSession();
    final route = await restoreMintTickSessionAfterInit(
      session,
      identity: _StartupCountingIdentity(calls),
      skipCachedSession: false,
      readRememberMe: () async => true,
      provision: () async => throw StateError('network blocked'),
    );
    expect(route, '/welcome');
    expect(session.isCustomerAuthenticated, isFalse);
  });

  testWidgets('welcome renders at first frame without waiting for Cognito',
      (tester) async {
    final pending = Completer<String>();
    await tester.pumpWidget(MintTickStartupApp(
      restoreSession: (_) => pending.future,
      readyBuilder: (_, route) =>
          MaterialApp(home: Scaffold(body: Text('Ready: $route'))),
    ));

    // The UI is already painted even while AWS auth bootstrap is unfinished.
    expect(find.byType(MintTickWelcomePage), findsOneWidget);
    expect(find.byKey(const ValueKey('minttick-inline-username')),
        findsOneWidget);
    expect(find.byKey(const ValueKey('minttick-inline-password')),
        findsOneWidget);
    expect(find.text('Preparing secure sign-in…'), findsOneWidget);
    expect(find.byKey(const ValueKey('minttick-startup-welcome')),
        findsOneWidget);
    expect(
        tester.widget<IgnorePointer>(
          find.ancestor(
            of: find.byType(MintTickWelcomePage),
            matching: find.byType(IgnorePointer),
          ).first,
        ).ignoring,
        isTrue,
    );
    expect(find.textContaining('Ready:'), findsNothing);

    pending.complete('/welcome');
    await tester.pump();
    await tester.pumpAndSettle();
    expect(find.text('Ready: /welcome'), findsOneWidget);
    expect(find.text('Preparing secure sign-in…'), findsNothing);
    expect(tester.takeException(), isNull);
  });

  testWidgets('verified restored route is not exposed until startup finishes',
      (tester) async {
    final pending = Completer<String>();
    await tester.pumpWidget(MintTickStartupApp(
      restoreSession: (_) => pending.future,
      readyBuilder: (_, route) =>
          MaterialApp(home: Scaffold(body: Text('Authorized route: $route'))),
    ));
    expect(find.textContaining('Authorized route:'), findsNothing);
    pending.complete('/');
    await tester.pump();
    await tester.pumpAndSettle();
    expect(find.text('Authorized route: /'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('startup failure opens editable unauthenticated sign-in',
      (tester) async {
    OperatorSession? observed;
    await tester.pumpWidget(MintTickStartupApp(
      restoreSession: (session) async {
        // Even partial restoration must be discarded before showing sign-in.
        observed = session;
        session.markCustomerAuthenticated();
        throw StateError('private');
      },
    ));
    await tester.pump();
    await tester.pumpAndSettle();
    expect(find.byType(MintTickWelcomePage), findsOneWidget);
    expect(find.byKey(const ValueKey('minttick-inline-username')),
        findsOneWidget);
    expect(find.byKey(const ValueKey('minttick-inline-password')),
        findsOneWidget);
    expect(find.textContaining('private'), findsNothing);
    expect(find.byKey(const ValueKey('minttick-startup-retry')), findsNothing);
    await tester.enterText(
        find.byKey(const ValueKey('minttick-inline-username')),
        'test@example.com');
    await tester.enterText(
        find.byKey(const ValueKey('minttick-inline-password')),
        'test-password');
    expect(find.text('test@example.com'), findsOneWidget);
    expect(observed?.isCustomerAuthenticated, isFalse);
    expect(tester.takeException(), isNull);
  });

}

import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/bootstrap.dart';
import 'package:trading_client/src/presentation/welcome_page.dart';

void main() {
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

  testWidgets('bootstrap failure stays on disabled welcome and allows retry',
      (tester) async {
    var calls = 0;
    final retry = Completer<String>();
    await tester.pumpWidget(MintTickStartupApp(
      restoreSession: (_) {
        calls++;
        if (calls == 1) return Future<String>.error(StateError('private'));
        return retry.future;
      },
      readyBuilder: (_, route) =>
          MaterialApp(home: Scaffold(body: Text('Ready: $route'))),
    ));
    await tester.pump();
    expect(find.textContaining('Secure sign-in initialization did not finish'),
        findsOneWidget);
    expect(find.byType(MintTickWelcomePage), findsOneWidget);
    expect(find.textContaining('private'), findsNothing);
    await tester.tap(find.byKey(const ValueKey('minttick-startup-retry')));
    await tester.pump();
    expect(calls, 2);
    expect(find.text('Preparing secure sign-in…'), findsOneWidget);
    retry.complete('/welcome');
    await tester.pump();
    await tester.pumpAndSettle();
    expect(find.text('Ready: /welcome'), findsOneWidget);
  });
}

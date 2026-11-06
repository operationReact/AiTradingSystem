import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/brand/minttick_brand.dart';
import 'package:trading_client/src/presentation/welcome_page.dart';

void main() {
  testWidgets('approved logo and exact spelling appear without unsolicited audio',
      (tester) async {
    await tester.pumpWidget(MaterialApp(
      home: const MintTickWelcomePage(),
      routes: {
        '/sign-in': (_) => const Scaffold(body: Text('Sign in destination')),
      },
    ));
    await tester.pumpAndSettle();

    expect(find.byType(MintTickLockup), findsOneWidget);
    expect(find.byType(MintTickMark), findsOneWidget);
    expect(find.text('Continue to sign in'), findsOneWidget);
    expect(find.text('Sound off'), findsOneWidget);
    expect(find.byKey(const ValueKey('minttick-welcome-sound-toggle')),
        findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('first-run flow does not gate navigation on audio or animation',
      (tester) async {
    await tester.pumpWidget(MaterialApp(
      home: const MintTickWelcomePage(),
      routes: {
        '/sign-in': (_) => const Scaffold(body: Text('Sign in destination')),
      },
    ));
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey('minttick-welcome-continue')));
    await tester.pumpAndSettle();
    expect(find.text('Sign in destination'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
}

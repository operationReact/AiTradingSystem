import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/presentation/customer_account_page.dart';

void main() {
  testWidgets('verified customer sees permanent app ID but no broker credentials',
      (tester) async {
    bool signedOut = false;
    tester.view.devicePixelRatio = 1;
    tester.view.physicalSize = const Size(390, 844);
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(MaterialApp(
      home: CustomerAccountPage(
        loadAccount: () async => {
          'userId': 'f3b5d41b-554e-4fca-ae89-ef42da2e4a18',
          'email': 'verified@example.com',
          'role': 'CUSTOMER',
        },
        onSignOut: () async => signedOut = true,
      ),
    ));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('minttick-account-verified-card')), findsOneWidget);
    expect(find.text('verified@example.com'), findsOneWidget);
    expect(find.textContaining('f3b5d41b'), findsOneWidget);
    expect(find.textContaining('Broker connections'), findsOneWidget);
    expect(tester.takeException(), isNull);
    await tester.tap(find.byKey(const ValueKey('minttick-customer-sign-out')));
    await tester.pump();
    expect(signedOut, isTrue);
  });

  testWidgets('unverified backend profile never shows account verification',
      (tester) async {
    await tester.pumpWidget(MaterialApp(
      home: CustomerAccountPage(
        loadAccount: () async => {
          'userId': 'f3b5d41b-554e-4fca-ae89-ef42da2e4a18',
          'email': 'user@example.com',
          'role': 'ADMIN',
        },
        onSignOut: () async {},
      ),
    ));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('minttick-account-verified-card')), findsNothing);
    expect(find.byKey(const ValueKey('minttick-retry-account')), findsOneWidget);
  });
}

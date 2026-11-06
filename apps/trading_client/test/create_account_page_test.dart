import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/auth/customer_identity.dart';
import 'package:trading_client/src/presentation/create_account_page.dart';

class _FakeIdentity implements CustomerIdentity {
  bool registered = false;
  bool confirmed = false;
  bool resent = false;
  @override
  bool get isConfigured => true;
  @override
  Future<RegistrationStep> register({
    required String username,
    required String email,
    required String password,
  }) async {
    expect(username, 'test_user');
    expect(email, 'test@example.com');
    expect(password, 'correct-horse-battery-staple');
    registered = true;
    return RegistrationStep.emailCodeRequired;
  }
  @override
  Future<void> confirmEmail({required String username, required String code}) async {
    expect(username, 'test_user');
    expect(code, '123456');
    confirmed = true;
  }
  @override
  Future<void> resendEmailCode(String username) async {
    expect(username, 'test_user');
    resent = true;
  }
  @override
  Future<LoginStep> signIn({required String username, required String password}) async =>
      LoginStep.complete;
  @override
  Future<bool> hasSession() async => false;
  @override
  Future<String?> accessToken() async => null;
  @override
  Future<void> signOut() async {}
}

void main() {
  Future<void> pumpSignup(WidgetTester tester, _FakeIdentity fake, double width) async {
    tester.view.devicePixelRatio = 1;
    tester.view.physicalSize = Size(width, 844);
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(MaterialApp(
      routes: {'/welcome': (_) => const Scaffold(body: Text('Returned to login'))},
      home: CreateAccountPage(identity: fake),
    ));
    await tester.pumpAndSettle();
  }

  for (final width in [320.0, 390.0, 430.0]) {
    testWidgets('signup form is phone safe at ${width.toInt()}dp', (tester) async {
      await pumpSignup(tester, _FakeIdentity(), width);
      expect(find.byKey(const ValueKey('minttick-create-account-screen')), findsOneWidget);
      expect(find.byKey(const ValueKey('minttick-register-username')), findsOneWidget);
      expect(find.byKey(const ValueKey('minttick-register-email')), findsOneWidget);
      expect(find.byKey(const ValueKey('minttick-register-password')), findsOneWidget);
      expect(find.byKey(const ValueKey('minttick-register-confirm')), findsOneWidget);
      expect(find.textContaining('broker connection'), findsOneWidget);
      expect(tester.takeException(), isNull);
    });
  }

  testWidgets('requires matching 15+ char password before calling Cognito', (tester) async {
    final fake = _FakeIdentity();
    await pumpSignup(tester, fake, 390);
    await tester.ensureVisible(find.byKey(const ValueKey('minttick-register-submit')));
    await tester.tap(find.byKey(const ValueKey('minttick-register-submit')));
    await tester.pump();
    expect(fake.registered, isFalse);
    expect(find.textContaining('15'), findsWidgets);
    expect(tester.takeException(), isNull);
  });

  testWidgets('registration moves to OTP verification and resend; never brokers', (tester) async {
    final fake = _FakeIdentity();
    await pumpSignup(tester, fake, 390);
    await tester.enterText(find.byKey(const ValueKey('minttick-register-username')), 'Test_User');
    await tester.enterText(find.byKey(const ValueKey('minttick-register-email')), 'Test@Example.Com');
    await tester.enterText(find.byKey(const ValueKey('minttick-register-password')),
        'correct-horse-battery-staple');
    await tester.enterText(find.byKey(const ValueKey('minttick-register-confirm')),
        'correct-horse-battery-staple');
    await tester.ensureVisible(find.byKey(const ValueKey('minttick-register-submit')));
    await tester.tap(find.byKey(const ValueKey('minttick-register-submit')));
    await tester.pumpAndSettle();
    expect(fake.registered, isTrue);
    expect(find.byKey(const ValueKey('minttick-verify-email-screen')), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('minttick-resend-email-otp')));
    await tester.pumpAndSettle();
    expect(fake.resent, isTrue);
    await tester.enterText(find.byKey(const ValueKey('minttick-email-otp')), '123456');
    await tester.tap(find.byKey(const ValueKey('minttick-confirm-email')));
    await tester.pumpAndSettle();
    expect(fake.confirmed, isTrue);
    expect(find.text('Returned to login'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('unconfigured Cognito never creates a fake user', (tester) async {
    tester.view.devicePixelRatio = 1;
    tester.view.physicalSize = const Size(390, 844);
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(const MaterialApp(home: CreateAccountPage(
      identity: UnavailableCustomerIdentity(),
    )));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const ValueKey('minttick-register-username')), 'test_user');
    await tester.enterText(find.byKey(const ValueKey('minttick-register-email')), 'test@example.com');
    await tester.enterText(find.byKey(const ValueKey('minttick-register-password')),
        'correct-horse-battery-staple');
    await tester.enterText(find.byKey(const ValueKey('minttick-register-confirm')),
        'correct-horse-battery-staple');
    await tester.ensureVisible(find.byKey(const ValueKey('minttick-register-submit')));
    await tester.tap(find.byKey(const ValueKey('minttick-register-submit')));
    await tester.pump();
    expect(find.textContaining('Secure Cognito setup is required'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
}

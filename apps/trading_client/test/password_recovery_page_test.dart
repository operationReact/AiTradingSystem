import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/auth/customer_identity.dart';
import 'package:trading_client/src/presentation/password_recovery_page.dart';

class _RecoveryIdentity implements CustomerIdentity {
  String? requested;
  String? confirmed;
  String? newPassword;

  @override
  bool get isConfigured => true;
  @override
  Future<RegistrationStep> register({
    required String username,
    required String email,
    required String password,
  }) async => RegistrationStep.emailCodeRequired;
  @override
  Future<void> confirmEmail({required String username, required String code}) async {}
  @override
  Future<void> resendEmailCode(String username) async {}

  @override
  Future<void> requestPasswordReset(String usernameOrEmail) async {
    requested = usernameOrEmail;
  }

  @override
  Future<void> confirmPasswordReset({
    required String usernameOrEmail,
    required String code,
    required String newPassword,
  }) async {
    confirmed = '$usernameOrEmail:$code';
    this.newPassword = newPassword;
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
  testWidgets('recover account with Cognito email code; preserve account identity',
      (tester) async {
    final identity = _RecoveryIdentity();
    tester.view.devicePixelRatio = 1;
    tester.view.physicalSize = const Size(390, 844);
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    await tester.pumpWidget(MaterialApp(
      home: PasswordRecoveryPage(identity: identity),
      routes: {
        '/welcome': (_) => const Scaffold(body: Text('Sign in again')),
      },
    ));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('minttick-forgot-password-screen')), findsOneWidget);

    await tester.enterText(
        find.byKey(const ValueKey('minttick-reset-identifier')), 'Test@Example.Com');
    await tester.tap(find.byKey(const ValueKey('minttick-request-reset')));
    await tester.pumpAndSettle();
    expect(identity.requested, 'test@example.com');
    expect(find.byKey(const ValueKey('minttick-complete-password-reset-screen')),
        findsOneWidget);

    await tester.enterText(find.byKey(const ValueKey('minttick-reset-code')), '123456');
    await tester.enterText(
        find.byKey(const ValueKey('minttick-new-password')), 'my-long-test-passphrase-2026');
    await tester.enterText(
        find.byKey(const ValueKey('minttick-confirm-new-password')),
        'my-long-test-passphrase-2026');
    await tester.ensureVisible(find.byKey(const ValueKey('minttick-confirm-reset')));
    await tester.tap(find.byKey(const ValueKey('minttick-confirm-reset')));
    await tester.pumpAndSettle();
    expect(identity.confirmed, 'test@example.com:123456');
    expect(identity.newPassword, 'my-long-test-passphrase-2026');
    expect(find.text('Sign in again'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
}

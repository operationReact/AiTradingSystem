import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/app.dart';
import 'package:trading_client/src/data/health_repository.dart';
import 'package:trading_client/src/data/operator_session.dart';
import 'package:trading_client/src/domain/health_snapshot.dart';
import 'package:trading_client/src/presentation/auth/biometric_auth_service.dart';
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

class _FakeBiometricAuthService extends BiometricAuthService {
  _FakeBiometricAuthService({this.available = true, this.enabled = false, this.authenticateResult = true});

  bool available;
  bool enabled;
  bool authenticateResult;

  @override
  Future<bool> isFingerprintAvailable() async => available;

  @override
  Future<bool> isFingerprintEnabledForApp() async => enabled;

  @override
  Future<void> setFingerprintEnabledForApp(bool value) async {
    enabled = value;
  }

  @override
  Future<bool> authenticateFingerprint({required String reason}) async => authenticateResult;
}

void _phoneViewport(WidgetTester tester) {
  tester.view.devicePixelRatio = 1;
  tester.view.physicalSize = const Size(390, 844);
  addTearDown(() {
    tester.view.resetPhysicalSize();
    tester.view.resetDevicePixelRatio();
  });
}

Future<void> _enterOtp(WidgetTester tester) async {
  for (var index = 0; index < 6; index++) {
    await tester.enterText(find.byKey(ValueKey('auth-otp-digit-$index')), '${index + 1}');
  }
}

void main() {
  testWidgets('screen 1 uses email or phone plus password with no social sign-in', (tester) async {
    _phoneViewport(tester);

    await tester.pumpWidget(
      MaterialApp(
        debugShowCheckedModeBanner: false,
        home: SignInPage(biometricService: _FakeBiometricAuthService(available: false)),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('AiTradingSystem'), findsOneWidget);
    expect(find.text('Trade Smarter\nwith AI'), findsOneWidget);
    expect(find.byKey(const ValueKey('auth-sign-in-identifier')), findsOneWidget);
    expect(find.byKey(const ValueKey('auth-sign-in-password')), findsOneWidget);
    expect(find.byKey(const ValueKey('auth-sign-in-submit')), findsOneWidget);
    expect(find.byKey(const ValueKey('auth-open-sign-up')), findsOneWidget);
    expect(find.textContaining('Google'), findsNothing);
    expect(find.textContaining('Apple'), findsNothing);
    expect(find.textContaining('Angel One'), findsNothing);
    expect(tester.takeException(), isNull);
  });

  testWidgets('registration follows sign up, phone OTP, email OTP, fingerprint offer and success', (tester) async {
    _phoneViewport(tester);
    var completed = false;
    final biometrics = _FakeBiometricAuthService(available: false);

    await tester.pumpWidget(
      MaterialApp(
        debugShowCheckedModeBanner: false,
        home: SignInPage(
          biometricService: biometrics,
          onAuthenticated: () => completed = true,
        ),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const ValueKey('auth-open-sign-up')));
    await tester.pumpAndSettle();
    expect(find.text('Join and start your AI trading journey'), findsOneWidget);

    await tester.enterText(find.byKey(const ValueKey('auth-sign-up-name')), 'Satya Varma');
    await tester.enterText(find.byKey(const ValueKey('auth-sign-up-email')), 'satya@example.com');
    await tester.enterText(find.byKey(const ValueKey('auth-sign-up-phone')), '9876543210');
    await tester.enterText(find.byKey(const ValueKey('auth-sign-up-password')), 'Strong1!');
    await tester.enterText(find.byKey(const ValueKey('auth-sign-up-confirm-password')), 'Strong1!');
    await tester.tap(find.byKey(const ValueKey('auth-create-account')));
    await tester.pumpAndSettle();

    expect(find.text('Verify Your Number'), findsOneWidget);
    await _enterOtp(tester);
    await tester.tap(find.byKey(const ValueKey('auth-verify-phone-otp')));
    await tester.pumpAndSettle();

    expect(find.text('Verify Your Email'), findsOneWidget);
    await _enterOtp(tester);
    await tester.tap(find.byKey(const ValueKey('auth-verify-email-otp')));
    await tester.pumpAndSettle();

    expect(find.text('Enable Fingerprint'), findsOneWidget);
    expect(find.textContaining('No enrolled fingerprint'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('auth-skip-fingerprint')));
    await tester.pump();

    expect(find.text("You're signed in!"), findsOneWidget);
    await tester.pump(const Duration(milliseconds: 1400));
    expect(completed, isTrue);
    expect(tester.takeException(), isNull);
  });

  testWidgets('returning enrolled user sees fingerprint sign-in and password fallback', (tester) async {
    _phoneViewport(tester);
    final biometrics = _FakeBiometricAuthService(
      available: true,
      enabled: true,
      authenticateResult: false,
    );

    await tester.pumpWidget(
      MaterialApp(
        debugShowCheckedModeBanner: false,
        home: SignInPage(biometricService: biometrics),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('auth-fingerprint-sign-in')), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('auth-fingerprint-sign-in')));
    await tester.pumpAndSettle();

    expect(find.text('Sign in with Fingerprint'), findsOneWidget);
    expect(find.byKey(const ValueKey('auth-use-password-instead')), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('auth-use-password-instead')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('auth-sign-in-password')), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('sign-in route stays full screen without product navigation', (tester) async {
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

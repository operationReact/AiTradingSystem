import 'package:flutter/material.dart';

import 'auth/auth_success_page.dart';
import 'auth/biometric_auth_service.dart';
import 'auth/enable_fingerprint_page.dart';
import 'auth/fingerprint_sign_in_page.dart';
import 'auth/otp_verification_page.dart';
import 'auth/password_sign_in_page.dart';
import 'auth/sign_up_page.dart';

enum _AuthStage {
  signIn,
  signUp,
  verifyPhone,
  verifyEmail,
  enableFingerprint,
  fingerprintSignIn,
  success,
}

class SignInPage extends StatefulWidget {
  const SignInPage({this.onAuthenticated, this.biometricService, super.key});

  /// Production customer authentication can supply this callback once the
  /// server session has been established. During UI review it may remain null.
  final VoidCallback? onAuthenticated;
  final BiometricAuthService? biometricService;

  @override
  State<SignInPage> createState() => _SignInPageState();
}

class _SignInPageState extends State<SignInPage> {
  late final BiometricAuthService _biometricService = widget.biometricService ?? BiometricAuthService();

  _AuthStage _stage = _AuthStage.signIn;
  bool _fingerprintReady = false;
  String _fullName = '';
  String _email = '';
  String _phone = '';

  @override
  void initState() {
    super.initState();
    _loadFingerprintState();
  }

  Future<void> _loadFingerprintState() async {
    final enabled = await _biometricService.isFingerprintEnabledForApp();
    final available = await _biometricService.isFingerprintAvailable();
    if (!mounted) return;
    setState(() => _fingerprintReady = enabled && available);
  }

  void _go(_AuthStage stage) => setState(() => _stage = stage);

  @override
  Widget build(BuildContext context) {
    final page = switch (_stage) {
      _AuthStage.signIn => PasswordSignInPage(
          biometricEnrolled: _fingerprintReady,
          onSignUp: () => _go(_AuthStage.signUp),
          onFingerprintSignIn: () => _go(_AuthStage.fingerprintSignIn),
          onPasswordSignIn: (_, __) => _go(_AuthStage.success),
        ),
      _AuthStage.signUp => SignUpPage(
          onBack: () => _go(_AuthStage.signIn),
          onCreateAccount: ({required fullName, required email, required phone}) {
            _fullName = fullName;
            _email = email;
            _phone = phone;
            _go(_AuthStage.verifyPhone);
          },
        ),
      _AuthStage.verifyPhone => OtpVerificationPage(
          type: OtpVerificationType.phone,
          destination: _displayPhone(_phone),
          onBack: () => _go(_AuthStage.signUp),
          onVerified: () => _go(_AuthStage.verifyEmail),
        ),
      _AuthStage.verifyEmail => OtpVerificationPage(
          type: OtpVerificationType.email,
          destination: _email,
          onBack: () => _go(_AuthStage.verifyPhone),
          onVerified: () => _go(_AuthStage.enableFingerprint),
        ),
      _AuthStage.enableFingerprint => EnableFingerprintPage(
          service: _biometricService,
          onBack: () => _go(_AuthStage.verifyEmail),
          onEnabled: () {
            setState(() {
              _fingerprintReady = true;
              _stage = _AuthStage.success;
            });
          },
          onSkip: () => _go(_AuthStage.success),
        ),
      _AuthStage.fingerprintSignIn => FingerprintSignInPage(
          service: _biometricService,
          onUsePassword: () => _go(_AuthStage.signIn),
          onAuthenticated: () => _go(_AuthStage.success),
        ),
      _AuthStage.success => AuthSuccessPage(onContinue: _finishAuthenticationUi),
    };

    return AnimatedSwitcher(
      duration: const Duration(milliseconds: 220),
      switchInCurve: Curves.easeOut,
      switchOutCurve: Curves.easeIn,
      child: KeyedSubtree(key: ValueKey(_stage), child: page),
    );
  }

  void _finishAuthenticationUi() {
    // Do not fabricate a trading session. The UI flow is complete, but the
    // production backend must supply the authenticated customer session before
    // Home is entered as a signed-in user.
    widget.onAuthenticated?.call();
  }

  String _displayPhone(String raw) {
    final trimmed = raw.trim();
    if (trimmed.isEmpty) return 'your phone number';
    final digits = trimmed.replaceAll(RegExp(r'\D'), '');
    if (digits.length <= 4) return trimmed;
    final suffix = digits.substring(digits.length - 4);
    return '••••••$suffix';
  }
}

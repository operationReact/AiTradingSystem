import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../auth/customer_identity.dart';
import '../brand/minttick_brand.dart';
import '../theme/app_theme.dart';

/// Brand-first, single-screen customer login. Cognito validates credentials;
/// MintTick backend must independently provision a verified permanent user ID.
class MintTickWelcomePage extends StatefulWidget {
  const MintTickWelcomePage({
    super.key,
    this.replayFromSettings = false,
    this.onAuthenticated,
  });

  final bool replayFromSettings;
  final VoidCallback? onAuthenticated;

  @override
  State<MintTickWelcomePage> createState() => _MintTickWelcomePageState();
}

class _MintTickWelcomePageState extends State<MintTickWelcomePage>
    with SingleTickerProviderStateMixin {
  static const seenKey = 'minttick.welcome.seen.v1';
  final _formKey = GlobalKey<FormState>();
  final _username = TextEditingController();
  final _password = TextEditingController();
  late final AnimationController _reveal = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 1450),
  )..forward();

  bool _passwordVisible = false;
  bool _rememberMe = false;
  bool _busy = false;

  @override
  void dispose() {
    _reveal.dispose();
    _username.dispose();
    _password.dispose();
    super.dispose();
  }

  Future<void> _markWelcomeSeen() async {
    try {
      await SharedPreferencesAsync().setBool(seenKey, true);
    } catch (_) {
      // Local preferences must never block access to the account flow.
    }
  }

  Future<void> _signIn() async {
    if (_busy || !(_formKey.currentState?.validate() ?? false)) return;
    setState(() => _busy = true);
    try {
      final login = await MintTickIdentity.current.signIn(
        username: _username.text.trim(),
        password: _password.text,
      );
      if (!mounted) return;
      switch (login) {
        case LoginStep.complete:
          // Cognito success does not grant MintTick access: the backend must
          // validate the JWT, verify email, and provision the persistent UUID.
          await MintTickCustomerAccountApi().provision();
          // A user preference only, never store passwords or tokens here.
          // The Cognito SDK persists credentials securely on supported devices.
          await SharedPreferencesAsync().setBool(
            'minttick.auth.remember_me.v1', _rememberMe);
          if (!mounted) return;
          _password.clear();
          if (widget.onAuthenticated != null) {
            widget.onAuthenticated!();
          } else {
            Navigator.of(context).pushNamedAndRemoveUntil('/', (_) => false);
          }
        case LoginStep.emailConfirmationRequired:
          _showMessage('Verify your email before signing in.');
        case LoginStep.additionalChallenge:
          _showMessage('Additional account verification is required. Sign-in is not complete.');
      }
    } on CustomerAuthenticationUnavailable {
      if (mounted) _showMessage('Secure sign-in is not configured yet.');
    } catch (_) {
      if (mounted) _showMessage('Unable to sign in. Check your details or account verification.');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  void _showMessage(String message) {
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(content: Text(message)));
  }

  void _forgotPassword() {
    Navigator.of(context).pushNamed('/forgot-password');
  }

  Future<void> _createAccount() async {
    await _markWelcomeSeen();
    if (!mounted) return;
    Navigator.of(context).pushNamed('/sign-up');
  }

  Widget _brandLogo(double maxWidth, bool reduceMotion) {
    final width = math.min(maxWidth, 330.0);
    return AnimatedBuilder(
      animation: _reveal,
      child: SizedBox(
        width: width,
        child: FittedBox(
          fit: BoxFit.scaleDown,
          child: SizedBox(
            width: 390,
            child: Theme(
              data: buildTheme(Brightness.dark),
              child: const MintTickLockup(markWidth: 82, fontSize: 32),
            ),
          ),
        ),
      ),
      builder: (context, child) {
        final p = reduceMotion ? 1.0 : Curves.easeOutCubic.transform(_reveal.value);
        return Opacity(
          opacity: p.clamp(0.0, 1.0),
          child: Transform.translate(
            offset: Offset(0, (1 - p) * 12),
            child: Transform.scale(scale: 0.98 + 0.02 * p, child: child),
          ),
        );
      },
    );
  }

  InputDecoration _fieldDecoration(String hint, IconData icon, {Widget? suffix}) {
    return InputDecoration(
      hintText: hint,
      prefixIcon: Icon(icon, size: 21, color: const Color(0xFFA9B9C2)),
      suffixIcon: suffix,
      filled: true,
      fillColor: const Color(0xFF14232D),
      contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 17),
      enabledBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(14),
        borderSide: const BorderSide(color: Color(0xFF34525D)),
      ),
      focusedBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(14),
        borderSide: const BorderSide(color: MintTickPalette.teal, width: 1.4),
      ),
      errorBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(14),
        borderSide: const BorderSide(color: Color(0xFFFF8A80)),
      ),
      focusedErrorBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(14),
        borderSide: const BorderSide(color: Color(0xFFFF8A80)),
      ),
    );
  }

  Widget _loginForm() {
    return Form(
      key: _formKey,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          TextFormField(
            key: const ValueKey('minttick-inline-username'),
            controller: _username,
            textInputAction: TextInputAction.next,
            autofillHints: const [AutofillHints.username],
            decoration: _fieldDecoration('Email or username', Icons.person_outline_rounded),
            validator: (value) => (value?.trim().isEmpty ?? true) ? 'Enter your email or username' : null,
          ),
          const SizedBox(height: 14),
          TextFormField(
            key: const ValueKey('minttick-inline-password'),
            controller: _password,
            obscureText: !_passwordVisible,
            enableSuggestions: false,
            autocorrect: false,
            autofillHints: const [AutofillHints.password],
            textInputAction: TextInputAction.done,
            onFieldSubmitted: (_) { _signIn(); },
            decoration: _fieldDecoration(
              'Password',
              Icons.lock_outline_rounded,
              suffix: IconButton(
                key: const ValueKey('minttick-password-visibility'),
                tooltip: _passwordVisible ? 'Hide password' : 'Show password',
                onPressed: () => setState(() => _passwordVisible = !_passwordVisible),
                icon: Icon(
                  _passwordVisible ? Icons.visibility_outlined : Icons.visibility_off_outlined,
                  color: const Color(0xFFAFBFC8),
                ),
              ),
            ),
            validator: (value) => (value?.isEmpty ?? true) ? 'Enter your password' : null,
          ),
          const SizedBox(height: 8),
          Wrap(
            alignment: WrapAlignment.spaceBetween,
            crossAxisAlignment: WrapCrossAlignment.center,
            spacing: 8,
            runSpacing: 0,
            children: [
              Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  SizedBox(
                    width: 40,
                    height: 44,
                    child: Checkbox(
                      key: const ValueKey('minttick-remember-me'),
                      value: _rememberMe,
                      activeColor: MintTickPalette.teal,
                      onChanged: (value) => setState(() => _rememberMe = value ?? false),
                    ),
                  ),
                  const Text('Remember me', style: TextStyle(fontSize: 13)),
                ],
              ),
              TextButton(
                key: const ValueKey('minttick-forgot-password'),
                onPressed: _busy ? null : _forgotPassword,
                child: const Text('Forgot password?', style: TextStyle(fontSize: 13)),
              ),
            ],
          ),
          const SizedBox(height: 15),
          SizedBox(
            height: 54,
            child: FilledButton(
              key: const ValueKey('minttick-welcome-sign-in'),
              onPressed: _busy ? null : _signIn,
              style: FilledButton.styleFrom(
                backgroundColor: MintTickPalette.teal,
                foregroundColor: MintTickPalette.navy,
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
                textStyle: const TextStyle(fontWeight: FontWeight.w800, fontSize: 16),
              ),
              child: _busy
                  ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2))
                  : const Text('Sign in'),
            ),
          ),
          const SizedBox(height: 20),
          Row(
            children: [
              const Expanded(child: Divider(color: Color(0xFF34434C))),
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 16),
                child: Text('or', style: TextStyle(color: Colors.white.withValues(alpha: 0.58))),
              ),
              const Expanded(child: Divider(color: Color(0xFF34434C))),
            ],
          ),
          const SizedBox(height: 20),
          SizedBox(
            height: 54,
            child: OutlinedButton(
              key: const ValueKey('minttick-welcome-create-account'),
              onPressed: _busy ? null : _createAccount,
              style: OutlinedButton.styleFrom(
                foregroundColor: MintTickPalette.ice,
                backgroundColor: const Color(0xFF14232D),
                side: const BorderSide(color: Color(0xFF4E8989)),
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
                textStyle: const TextStyle(fontWeight: FontWeight.w700, fontSize: 16),
              ),
              child: const Text('Create account'),
            ),
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final reduceMotion = MediaQuery.of(context).disableAnimations;
    return AnnotatedRegion<SystemUiOverlayStyle>(
      value: SystemUiOverlayStyle.light,
      child: Scaffold(
        key: const ValueKey('minttick-welcome-screen'),
        backgroundColor: MintTickPalette.navy,
        body: SafeArea(
          child: LayoutBuilder(
            builder: (context, bounds) {
              final side = bounds.maxWidth < 350 ? 20.0 : 26.0;
              final usableWidth = math.min(440.0, bounds.maxWidth - side * 2);
              return SingleChildScrollView(
                keyboardDismissBehavior: ScrollViewKeyboardDismissBehavior.onDrag,
                child: Center(
                  child: ConstrainedBox(
                    constraints: BoxConstraints(
                      maxWidth: 440,
                      minHeight: bounds.maxHeight,
                    ),
                    child: Padding(
                      padding: EdgeInsets.fromLTRB(side, 24, side, 28),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        children: [
                          // The whole approved mark + wordmark is centered as
                          // one lockup, never separately aligned.
                          SizedBox(
                            height: math.max(150, math.min(245, bounds.maxHeight * 0.32)),
                            child: Center(child: _brandLogo(usableWidth, reduceMotion)),
                          ),
                          if (widget.replayFromSettings) ...[
                            const SizedBox(height: 64),
                            SizedBox(
                              height: 54,
                              child: FilledButton(
                                key: const ValueKey('minttick-welcome-done'),
                                onPressed: () => Navigator.of(context).pop(),
                                child: const Text('Done'),
                              ),
                            ),
                          ] else ...[
                            const SizedBox(height: 12),
                            _loginForm(),
                          ],
                        ],
                      ),
                    ),
                  ),
                ),
              );
            },
          ),
        ),
      ),
    );
  }
}

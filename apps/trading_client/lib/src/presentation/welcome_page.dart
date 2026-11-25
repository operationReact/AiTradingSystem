import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../auth/customer_identity.dart';
import '../auth/cognito_reachability.dart';
import '../brand/minttick_brand.dart';
import '../theme/app_theme.dart';

/// Brand-first, single-screen customer login. Cognito validates credentials;
/// MintTick backend must independently provision a verified permanent user ID.
class MintTickWelcomePage extends StatefulWidget {
  const MintTickWelcomePage({
    super.key,
    this.replayFromSettings = false,
    this.onAuthenticated,
    this.provisionAccount,
    this.cognitoConnectivityCheck,
  });

  final bool replayFromSettings;
  final VoidCallback? onAuthenticated;

  // Injectable only for deterministic UI tests; production always uses
  // Cognito JWT-backed MintTickCustomerAccountApi.
  final Future<String> Function()? provisionAccount;

  // Optional test seam for the public-key endpoint probe, never credentials.
  final Future<bool> Function()? cognitoConnectivityCheck;

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
    duration: const Duration(milliseconds: 1950),
  )..forward();

  bool _passwordVisible = false;
  // Keep a verified Cognito session across app restarts by default.
  // Users can opt out and explicit sign-out still revokes local access.
  bool _rememberMe = true;
  bool _busy = false;
  String _busyStage = 'Signing in…';

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
    setState(() {
      _busy = true;
      _busyStage = 'Connecting to Cognito…';
    });
    var cognitoCompleted = false;
    try {
      // Without this bound, a stalled Amplify/Cognito web request can keep
      // the sign-in button disabled and spinning for the entire page session.
      final login = await MintTickIdentity.current.signIn(
        username: _username.text.trim(),
        password: _password.text,
      ).timeout(const Duration(seconds: 25));
      if (!mounted) return;
      switch (login) {
        case LoginStep.complete:
          cognitoCompleted = true;
          if (mounted) {
            setState(() => _busyStage = 'Verifying MintTick account…');
          }
          // This release-only test mode allows the user to verify a real AWS
          // Cognito session while the public backend endpoint is unavailable.
          // It NEVER marks a customer authenticated or opens trading routes.
          if (const bool.fromEnvironment('MINTTICK_AUTH_ONLY_TEST',
              defaultValue: false)) {
            await _saveRememberMe();
            _password.clear();
            if (!mounted) return;
            Navigator.of(context)
                .pushNamedAndRemoveUntil('/auth-test', (_) => false);
            return;
          }
          // Cognito success does not grant MintTick access: the backend must
          // validate the JWT, verify email, and provision the persistent UUID.
          await (widget.provisionAccount ??
                  () => MintTickCustomerAccountApi().provision())()
              .timeout(const Duration(seconds: 20));
          // Remember-me is optional and must not freeze login once the
          // backend has authenticated the user's actual Cognito identity.
          await _saveRememberMe();
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
    } on TimeoutException {
      if (cognitoCompleted) {
        if (mounted) {
          _showMessage(
              'Cognito sign-in completed, but MintTick account verification '
              'timed out. Inspect the api.minttick.com request in Chrome Network.');
        }
      } else {
        // A timed-out Amplify Future does not cancel an internal Cognito
        // request. Avoid suggesting blind retries against a stuck SDK.
        final reachable = await (widget.cognitoConnectivityCheck ??
                MintTickCognitoReachability.check)()
            .timeout(const Duration(seconds: 7),
                onTimeout: () => false);
        if (mounted) {
          _showMessage(reachable
              ? 'Cognito sign-in timed out, but its public endpoint is '
                  'reachable. Restart the Flutter Chrome debug session before '
                  'retrying; inspect cognito-idp requests in Chrome Network.'
              : 'Cognito sign-in timed out and its public endpoint could not '
                  'be verified from Chrome. Inspect blocked or pending '
                  'cognito-idp requests in Chrome Network.');
        }
      }
    } on CustomerAuthenticationFailed {
      if (mounted) {
        _showMessage(cognitoCompleted
            ? 'Cognito sign-in succeeded, but MintTick backend account verification '
                'failed. Check the API URL and browser Network tab.'
            : 'Unable to sign in. Check your credentials and email verification.');
      }
    } catch (_) {
      if (mounted) {
        _showMessage(cognitoCompleted
            ? 'MintTick backend account verification failed. Check browser Network.'
            : 'Unable to sign in. Check your connection and account status.');
      }
    } finally {
      // A failed or timed-out request must always re-enable both actions.
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _saveRememberMe() async {
    try {
      await SharedPreferencesAsync()
          .setBool('minttick.auth.remember_me.v1', _rememberMe)
          .timeout(const Duration(seconds: 4));
    } catch (_) {
      // Optional storage must not prevent a successful authenticated login.
    }
  }

  void _showMessage(String message) {
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(
        content: Text(message),
        duration: const Duration(seconds: 8),
      ));
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
    // Center the *actual* mark and wordmark, not a padded 390px layout
    // canvas. A fixed-width FittedBox previously made the visible lockup
    // look left-aligned even though its outer box was centered.
    final width = math.min(maxWidth, 330.0);
    return AnimatedBuilder(
      animation: _reveal,
      builder: (context, _) {
        final progress = reduceMotion ? 1.0 : _reveal.value;
        // Distinct sequential movements are visible without a distracting,
        // endlessly repeating animation on a screen used for passwords.
        final mark = Curves.easeOutBack.transform(
          const Interval(0.0, 0.52).transform(progress),
        );
        final markAlpha = const Interval(
          0.0, 0.28, curve: Curves.easeOut,
        ).transform(progress);
        final words = const Interval(
          0.22, 0.84, curve: Curves.easeOutCubic,
        ).transform(progress);

        return SizedBox(
          width: width,
          child: Center(
            key: const ValueKey('minttick-centered-lockup'),
            child: Theme(
              data: buildTheme(Brightness.dark),
              child: MintTickLockup(
                markWidth: math.min(82.0, width * 0.26),
                fontSize: math.min(32.0, width * 0.105),
                markScale: 0.76 + 0.24 * mark,
                markOpacity: markAlpha,
                wordmarkOpacity: words,
                wordmarkOffset: (1.0 - words) * 20,
              ),
            ),
          ),
        );
      },
    );
  }

  InputDecoration _fieldDecoration(String hint, IconData icon, {Widget? suffix}) {
    return InputDecoration(
      hintText: hint,
      prefixIcon: Icon(icon, size: 21, color: const Color(0xFFA5ABA6)),
      suffixIcon: suffix,
      filled: true,
      fillColor: const Color(0xFF151916),
      contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 17),
      enabledBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(14),
        borderSide: const BorderSide(color: Color(0xFF292F2A)),
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
                  color: const Color(0xFFA5ABA6),
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
                  ? Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        const SizedBox(
                            width: 18, height: 18,
                            child: CircularProgressIndicator(strokeWidth: 2)),
                        const SizedBox(width: 12),
                        Flexible(
                          child: Text(
                            _busyStage,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                          ),
                        ),
                      ],
                    )
                  : const Text('Sign in'),
            ),
          ),
          const SizedBox(height: 20),
          Row(
            children: [
              const Expanded(child: Divider(color: Color(0xFF292F2A))),
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 16),
                child: Text('or', style: TextStyle(color: Colors.white.withValues(alpha: 0.58))),
              ),
              const Expanded(child: Divider(color: Color(0xFF292F2A))),
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
                backgroundColor: const Color(0xFF151916),
                side: const BorderSide(color: Color(0xFF414B43)),
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

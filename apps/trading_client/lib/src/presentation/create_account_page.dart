import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../auth/customer_identity.dart';
import '../brand/minttick_brand.dart';
import '../theme/app_theme.dart';

/// MintTick customer account only. Brokerage onboarding is a separate route.
class CreateAccountPage extends StatefulWidget {
  const CreateAccountPage({super.key, this.identity});

  final CustomerIdentity? identity;

  @override
  State<CreateAccountPage> createState() => _CreateAccountPageState();
}

class _CreateAccountPageState extends State<CreateAccountPage> {
  final _formKey = GlobalKey<FormState>();
  final _username = TextEditingController();
  final _email = TextEditingController();
  final _password = TextEditingController();
  final _confirmPassword = TextEditingController();
  bool _busy = false;
  bool _passwordVisible = false;
  bool _termsAccepted = false;

  CustomerIdentity get _identity => widget.identity ?? MintTickIdentity.current;

  @override
  void dispose() {
    _username.dispose();
    _email.dispose();
    _password.dispose();
    _confirmPassword.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (_busy || !(_formKey.currentState?.validate() ?? false)) return;
    if (!_termsAccepted) {
      _message('Review and accept the Terms and Privacy Policy to continue.');
      return;
    }
    setState(() => _busy = true);
    try {
      final username = _username.text.trim().toLowerCase();
      final step = await _identity.register(
        username: username,
        email: _email.text.trim().toLowerCase(),
        password: _password.text,
      );
      if (!mounted) return;
      if (step == RegistrationStep.emailCodeRequired) {
        await Navigator.of(context).push(MaterialPageRoute<void>(
          builder: (_) => VerifyAccountEmailPage(
            identity: _identity,
            username: username,
            email: _email.text.trim(),
          ),
        ));
      } else {
        ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
          content: Text('Account created. You can now sign in.'),
        ));
        Navigator.of(context).pushNamedAndRemoveUntil('/welcome', (_) => false);
      }
    } on CustomerAuthenticationUnavailable {
      if (!mounted) return;
      _message('Registration is not available yet. Secure Cognito setup is required.');
    } catch (_) {
      if (!mounted) return;
      _message('Unable to create account. Please check your details and try again.');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  void _message(String message) {
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(content: Text(message)));
  }

  InputDecoration _decoration(String label, IconData icon, {Widget? suffix}) => InputDecoration(
        labelText: label,
        prefixIcon: Icon(icon, size: 21),
        suffixIcon: suffix,
        filled: true,
        fillColor: const Color(0xFF15232D),
        contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 16),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(14),
          borderSide: const BorderSide(color: Color(0xFF344F5A)),
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(14),
          borderSide: const BorderSide(color: MintTickPalette.teal, width: 1.4),
        ),
        border: OutlineInputBorder(borderRadius: BorderRadius.circular(14)),
      );

  @override
  Widget build(BuildContext context) {
    return Theme(
      data: buildTheme(Brightness.dark),
      child: Scaffold(
        key: const ValueKey('minttick-create-account-screen'),
        backgroundColor: MintTickPalette.navy,
        appBar: AppBar(
          title: const Text('Create account'),
          backgroundColor: MintTickPalette.navy,
        ),
        body: SafeArea(
          child: LayoutBuilder(
            builder: (context, bounds) => SingleChildScrollView(
              keyboardDismissBehavior: ScrollViewKeyboardDismissBehavior.onDrag,
              padding: EdgeInsets.fromLTRB(
                bounds.maxWidth < 350 ? 20 : 26,
                12,
                bounds.maxWidth < 350 ? 20 : 26,
                30,
              ),
              child: Center(
                child: ConstrainedBox(
                  constraints: const BoxConstraints(maxWidth: 420),
                  child: Form(
                    key: _formKey,
                    child: AutofillGroup(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        children: [
                          const Center(
                            child: FittedBox(
                              fit: BoxFit.scaleDown,
                              child: MintTickLockup(markWidth: 62, fontSize: 27),
                            ),
                          ),
                          const SizedBox(height: 28),
                          const Text('Your MintTick account',
                              style: TextStyle(fontSize: 25, fontWeight: FontWeight.w800)),
                          const SizedBox(height: 7),
                          const Text(
                            'Create secure access to markets, insights and your watchlists.',
                            style: TextStyle(color: Color(0xFFB0C0CA), fontSize: 14),
                          ),
                          const SizedBox(height: 26),
                          TextFormField(
                            key: const ValueKey('minttick-register-username'),
                            controller: _username,
                            enabled: !_busy,
                            textInputAction: TextInputAction.next,
                            textCapitalization: TextCapitalization.none,
                            autofillHints: const [AutofillHints.newUsername],
                            inputFormatters: [LengthLimitingTextInputFormatter(24)],
                            decoration: _decoration('Username', Icons.person_outline_rounded),
                            validator: (value) {
                              if (!RegExp(r'^[a-z][a-z0-9_]{2,23}$')
                                  .hasMatch((value ?? '').trim().toLowerCase())) {
                                return 'Use 3–24 letters, numbers or underscores; start with a letter';
                              }
                              return null;
                            },
                          ),
                          const SizedBox(height: 14),
                          TextFormField(
                            key: const ValueKey('minttick-register-email'),
                            controller: _email,
                            enabled: !_busy,
                            keyboardType: TextInputType.emailAddress,
                            textInputAction: TextInputAction.next,
                            autofillHints: const [AutofillHints.email],
                            decoration: _decoration('Email address', Icons.mail_outline_rounded),
                            validator: (value) => RegExp(r'^[^@\s]+@[^@\s]+\.[^@\s]+$')
                                    .hasMatch((value ?? '').trim())
                                ? null
                                : 'Enter a valid email address',
                          ),
                          const SizedBox(height: 14),
                          TextFormField(
                            key: const ValueKey('minttick-register-password'),
                            controller: _password,
                            enabled: !_busy,
                            obscureText: !_passwordVisible,
                            enableSuggestions: false,
                            autocorrect: false,
                            textInputAction: TextInputAction.next,
                            autofillHints: const [AutofillHints.newPassword],
                            decoration: _decoration(
                              'Password',
                              Icons.lock_outline_rounded,
                              suffix: IconButton(
                                key: const ValueKey('minttick-register-show-password'),
                                onPressed: () => setState(() => _passwordVisible = !_passwordVisible),
                                tooltip: _passwordVisible ? 'Hide password' : 'Show password',
                                icon: Icon(_passwordVisible
                                    ? Icons.visibility_outlined
                                    : Icons.visibility_off_outlined),
                              ),
                            ),
                            validator: (value) {
                              final n = value?.runes.length ?? 0;
                              return n >= 15 && n <= 99
                                  ? null
                                  : 'Use a password or passphrase of 15–99 characters';
                            },
                          ),
                          const SizedBox(height: 8),
                          const Text(
                            '15+ characters recommended. A long passphrase is fine.',
                            style: TextStyle(color: Color(0xFF9EAEB9), fontSize: 12),
                          ),
                          const SizedBox(height: 14),
                          TextFormField(
                            key: const ValueKey('minttick-register-confirm'),
                            controller: _confirmPassword,
                            enabled: !_busy,
                            obscureText: !_passwordVisible,
                            enableSuggestions: false,
                            autocorrect: false,
                            textInputAction: TextInputAction.done,
                            onFieldSubmitted: (_) => _submit(),
                            autofillHints: const [AutofillHints.newPassword],
                            decoration: _decoration(
                              'Confirm password', Icons.verified_user_outlined),
                            validator: (value) => value != _password.text
                                ? 'Passwords do not match'
                                : null,
                          ),
                          const SizedBox(height: 18),
                          Row(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              SizedBox(
                                height: 42,
                                width: 42,
                                child: Checkbox(
                                  key: const ValueKey('minttick-accept-terms'),
                                  value: _termsAccepted,
                                  activeColor: MintTickPalette.teal,
                                  onChanged: _busy
                                      ? null
                                      : (v) => setState(() => _termsAccepted = v ?? false),
                                ),
                              ),
                              const SizedBox(width: 6),
                              const Expanded(
                                child: Padding(
                                  padding: EdgeInsets.only(top: 5),
                                  child: Text(
                                    'I agree to the MintTick Terms of Service and Privacy Policy.',
                                    style: TextStyle(
                                        color: Color(0xFFAEBEC9), fontSize: 12),
                                  ),
                                ),
                              ),
                            ],
                          ),
                          const SizedBox(height: 18),
                          SizedBox(
                            height: 54,
                            child: FilledButton(
                              key: const ValueKey('minttick-register-submit'),
                              onPressed: _busy ? null : _submit,
                              style: FilledButton.styleFrom(
                                backgroundColor: MintTickPalette.teal,
                                foregroundColor: MintTickPalette.navy,
                                shape: RoundedRectangleBorder(
                                    borderRadius: BorderRadius.circular(14)),
                                textStyle: const TextStyle(
                                    fontWeight: FontWeight.w800, fontSize: 16),
                              ),
                              child: _busy
                                  ? const SizedBox(
                                      width: 20,
                                      height: 20,
                                      child: CircularProgressIndicator(strokeWidth: 2),
                                    )
                                  : const Text('Create account'),
                            ),
                          ),
                          const SizedBox(height: 15),
                          const Center(
                            child: Text(
                              'We’ll email you a verification code. No broker connection is required.',
                              textAlign: TextAlign.center,
                              style: TextStyle(color: Color(0xFF98AAB7), fontSize: 12),
                            ),
                          ),
                          const SizedBox(height: 15),
                          TextButton(
                            key: const ValueKey('minttick-register-to-login'),
                            onPressed: () => Navigator.of(context)
                                .pushNamedAndRemoveUntil('/welcome', (_) => false),
                            child: const Text('Already have an account? Sign in'),
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

/// No access is granted until the code has been confirmed through Cognito.
class VerifyAccountEmailPage extends StatefulWidget {
  const VerifyAccountEmailPage({
    required this.username,
    required this.email,
    required this.identity,
    super.key,
  });

  final String username;
  final String email;
  final CustomerIdentity identity;

  @override
  State<VerifyAccountEmailPage> createState() => _VerifyAccountEmailPageState();
}

class _VerifyAccountEmailPageState extends State<VerifyAccountEmailPage> {
  final _code = TextEditingController();
  bool _busy = false;

  @override
  void dispose() {
    _code.dispose();
    super.dispose();
  }

  Future<void> _confirm() async {
    if (_busy || _code.text.trim().length < 6) return;
    setState(() => _busy = true);
    try {
      await widget.identity.confirmEmail(
        username: widget.username,
        code: _code.text.trim(),
      );
      if (!mounted) return;
      Navigator.of(context).pushReplacement(MaterialPageRoute<void>(
        builder: (_) => const MintTickEmailVerifiedPage(),
      ));
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
          content: Text('Verification failed. Check the code or request a new one.'),
        ));
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _resend() async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      await widget.identity.resendEmailCode(widget.username);
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
        content: Text('If the account is eligible, a fresh code has been sent.'),
      ));
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
          content: Text('Unable to resend the code. Please try again later.'),
        ));
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Theme(
      data: buildTheme(Brightness.dark),
      child: Scaffold(
        key: const ValueKey('minttick-verify-email-screen'),
        backgroundColor: MintTickPalette.navy,
        appBar: AppBar(title: const Text('Verify email'), backgroundColor: MintTickPalette.navy),
        body: SafeArea(
          child: SingleChildScrollView(
            padding: const EdgeInsets.symmetric(horizontal: 26, vertical: 34),
            child: Center(
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 420),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    const Center(
                      child: Icon(Icons.mark_email_read_outlined,
                          size: 58, color: MintTickPalette.teal),
                    ),
                    const SizedBox(height: 24),
                    const Text('Check your inbox',
                        textAlign: TextAlign.center,
                        style: TextStyle(fontSize: 26, fontWeight: FontWeight.w800)),
                    const SizedBox(height: 10),
                    Text(
                      'Enter the code sent to ${widget.email}',
                      textAlign: TextAlign.center,
                      style: const TextStyle(color: Color(0xFFAFBFCA)),
                    ),
                    const SizedBox(height: 28),
                    Semantics(
                      label: 'Six-digit email verification code',
                      child: SizedBox(
                        height: 58,
                        child: Stack(
                          children: [
                            Row(
                              children: List.generate(6, (i) {
                                final digits = _code.text;
                                return Expanded(
                                  child: Container(
                                    key: ValueKey('minttick-otp-box-$i'),
                                    margin: const EdgeInsets.symmetric(horizontal: 3),
                                    alignment: Alignment.center,
                                    decoration: BoxDecoration(
                                      color: const Color(0xFF15232D),
                                      border: Border.all(color: const Color(0xFF34525D)),
                                      borderRadius: BorderRadius.circular(8),
                                    ),
                                    child: Text(
                                      i < digits.length ? digits[i] : '',
                                      style: const TextStyle(
                                          fontSize: 24, fontWeight: FontWeight.w700),
                                    ),
                                  ),
                                );
                              }),
                            ),
                            Positioned.fill(
                              child: Opacity(
                                opacity: 0.0,
                                alwaysIncludeSemantics: true,
                                child: TextField(
                                  key: const ValueKey('minttick-email-otp'),
                                  controller: _code,
                                  enabled: !_busy,
                                  autofocus: true,
                                  keyboardType: TextInputType.number,
                                  textInputAction: TextInputAction.done,
                                  autofillHints: const [AutofillHints.oneTimeCode],
                                  onChanged: (_) => setState(() {}),
                                  onSubmitted: (_) => _confirm(),
                                  inputFormatters: [
                                    FilteringTextInputFormatter.digitsOnly,
                                    LengthLimitingTextInputFormatter(6),
                                  ],
                                  decoration: const InputDecoration(
                                    labelText: 'Verification code',
                                  ),
                                ),
                              ),
                            ),
                          ],
                        ),
                      ),
                    ),
                    const SizedBox(height: 22),
                    SizedBox(
                      height: 54,
                      child: FilledButton(
                        key: const ValueKey('minttick-confirm-email'),
                        onPressed: _busy ? null : _confirm,
                        style: FilledButton.styleFrom(
                          backgroundColor: MintTickPalette.teal,
                          foregroundColor: MintTickPalette.navy,
                        ),
                        child: const Text('Verify email'),
                      ),
                    ),
                    TextButton(
                      key: const ValueKey('minttick-resend-email-otp'),
                      onPressed: _busy ? null : _resend,
                      child: const Text('Resend code'),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

/// Post-confirmation state: no trading or broker access is granted here.
class MintTickEmailVerifiedPage extends StatelessWidget {
  const MintTickEmailVerifiedPage({super.key});

  @override
  Widget build(BuildContext context) {
    return Theme(
      data: buildTheme(Brightness.dark),
      child: Scaffold(
        key: const ValueKey('minttick-email-verified-screen'),
        backgroundColor: MintTickPalette.navy,
        body: SafeArea(
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 26, vertical: 30),
            child: Center(
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 420),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    const SizedBox(height: 64),
                    const Center(
                      child: MintTickLockup(markWidth: 72, fontSize: 28),
                    ),
                    const Spacer(),
                    const Center(
                      child: Icon(Icons.check_circle_outline_rounded,
                          color: MintTickPalette.teal, size: 80),
                    ),
                    const SizedBox(height: 18),
                    const Text('Email verified',
                        textAlign: TextAlign.center,
                        style: TextStyle(fontSize: 25, fontWeight: FontWeight.w800)),
                    const SizedBox(height: 9),
                    const Text(
                      'Your MintTick account is ready. You can now sign in securely.',
                      textAlign: TextAlign.center,
                      style: TextStyle(color: Color(0xFFAFBFCA), fontSize: 14),
                    ),
                    const Spacer(),
                    SizedBox(
                      height: 54,
                      child: FilledButton(
                        key: const ValueKey('minttick-verified-to-login'),
                        onPressed: () => Navigator.of(context)
                            .pushNamedAndRemoveUntil('/welcome', (_) => false),
                        style: FilledButton.styleFrom(
                          backgroundColor: MintTickPalette.teal,
                          foregroundColor: MintTickPalette.navy,
                        ),
                        child: const Text('Continue to sign in'),
                      ),
                    ),
                    const SizedBox(height: 20),
                    const Text(
                      'No brokerage account is required to use MintTick.',
                      textAlign: TextAlign.center,
                      style: TextStyle(color: Color(0xFF98AAB7), fontSize: 11),
                    ),
                    const SizedBox(height: 20),
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

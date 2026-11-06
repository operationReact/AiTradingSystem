import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../auth/customer_identity.dart';
import '../theme/app_theme.dart';
import '../brand/minttick_brand.dart';

/// Password recovery is an identity operation, not brokerage onboarding.
/// Cognito emails the code; no passwords are persisted by MintTick.
class PasswordRecoveryPage extends StatefulWidget {
  const PasswordRecoveryPage({super.key, this.identity});
  final CustomerIdentity? identity;

  @override
  State<PasswordRecoveryPage> createState() => _PasswordRecoveryPageState();
}

class _PasswordRecoveryPageState extends State<PasswordRecoveryPage> {
  final _identifier = TextEditingController();
  bool _busy = false;

  CustomerIdentity get _identity => widget.identity ?? MintTickIdentity.current;

  @override
  void dispose() {
    _identifier.dispose();
    super.dispose();
  }

  Future<void> _send() async {
    final id = _identifier.text.trim().toLowerCase();
    if (_busy || id.isEmpty || id.length > 320) {
      if (id.isEmpty) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Enter your email or username.')),
        );
      }
      return;
    }
    setState(() => _busy = true);
    var unavailable = false;
    try {
      await _identity.requestPasswordReset(id);
    } on CustomerAuthenticationUnavailable {
      unavailable = true;
    } catch (_) {
      // Do not disclose whether this account exists or is eligible for reset.
    } finally {
      if (mounted) setState(() => _busy = false);
    }
    if (!mounted) return;
    if (unavailable) {
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
        content: Text('Password recovery is not available yet.'),
      ));
      return;
    }
    await Navigator.of(context).push(MaterialPageRoute<void>(
      builder: (_) => CompletePasswordResetPage(
        usernameOrEmail: id,
        identity: _identity,
      ),
    ));
  }

  @override
  Widget build(BuildContext context) {
    return Theme(
      data: buildTheme(Brightness.dark),
      child: Scaffold(
        key: const ValueKey('minttick-forgot-password-screen'),
        backgroundColor: MintTickPalette.navy,
        appBar: AppBar(title: const Text('Forgot password'), backgroundColor: MintTickPalette.navy),
        body: SafeArea(
          child: SingleChildScrollView(
            padding: const EdgeInsets.symmetric(horizontal: 26, vertical: 32),
            child: Center(
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 420),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    const Center(child: Icon(Icons.lock_reset_outlined,
                        size: 62, color: MintTickPalette.teal)),
                    const SizedBox(height: 24),
                    const Text('Reset your password',
                        style: TextStyle(fontWeight: FontWeight.w800, fontSize: 25)),
                    const SizedBox(height: 8),
                    const Text(
                      'Enter your MintTick email address or username. We’ll send a verification code to your verified email.',
                      style: TextStyle(color: Color(0xFFB0C0CA), fontSize: 14),
                    ),
                    const SizedBox(height: 28),
                    TextField(
                      key: const ValueKey('minttick-reset-identifier'),
                      controller: _identifier,
                      keyboardType: TextInputType.emailAddress,
                      autofillHints: const [AutofillHints.username, AutofillHints.email],
                      textInputAction: TextInputAction.done,
                      onSubmitted: (_) => _send(),
                      enabled: !_busy,
                      decoration: InputDecoration(
                        labelText: 'Email or username',
                        prefixIcon: const Icon(Icons.mail_outline_rounded),
                        filled: true,
                        fillColor: const Color(0xFF15232D),
                        border: OutlineInputBorder(
                            borderRadius: BorderRadius.circular(14)),
                      ),
                    ),
                    const SizedBox(height: 24),
                    SizedBox(
                      height: 54,
                      child: FilledButton(
                        key: const ValueKey('minttick-request-reset'),
                        onPressed: _busy ? null : _send,
                        style: FilledButton.styleFrom(
                          backgroundColor: MintTickPalette.teal,
                          foregroundColor: MintTickPalette.navy,
                        ),
                        child: _busy
                            ? const SizedBox(
                                height: 20, width: 20,
                                child: CircularProgressIndicator(strokeWidth: 2),
                              )
                            : const Text('Send reset code'),
                      ),
                    ),
                    const SizedBox(height: 12),
                    const Text(
                      'If this account is eligible, a code will be sent. You can request a new code if needed.',
                      style: TextStyle(color: Color(0xFF98AAB7), fontSize: 12),
                      textAlign: TextAlign.center,
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

/// The verification code and new password are submitted directly to Cognito.
class CompletePasswordResetPage extends StatefulWidget {
  const CompletePasswordResetPage({
    required this.usernameOrEmail,
    required this.identity,
    super.key,
  });
  final String usernameOrEmail;
  final CustomerIdentity identity;

  @override
  State<CompletePasswordResetPage> createState() => _CompletePasswordResetPageState();
}

class _CompletePasswordResetPageState extends State<CompletePasswordResetPage> {
  final _form = GlobalKey<FormState>();
  final _code = TextEditingController();
  final _password = TextEditingController();
  final _confirmation = TextEditingController();
  bool _busy = false;
  bool _showPassword = false;

  @override
  void dispose() {
    _code.dispose();
    _password.dispose();
    _confirmation.dispose();
    super.dispose();
  }

  Future<void> _confirm() async {
    if (_busy || !(_form.currentState?.validate() ?? false)) return;
    setState(() => _busy = true);
    try {
      await widget.identity.confirmPasswordReset(
        usernameOrEmail: widget.usernameOrEmail,
        code: _code.text.trim(),
        newPassword: _password.text,
      );
      _password.clear();
      _confirmation.clear();
      if (!mounted) return;
      Navigator.of(context).pushNamedAndRemoveUntil('/welcome', (_) => false);
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
        content: Text('Password updated. Sign in using your new password.'),
      ));
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
          content: Text('Unable to reset password. Check your code and try again.'),
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
        key: const ValueKey('minttick-complete-password-reset-screen'),
        backgroundColor: MintTickPalette.navy,
        appBar: AppBar(title: const Text('Set new password'), backgroundColor: MintTickPalette.navy),
        body: SafeArea(
          child: SingleChildScrollView(
            padding: const EdgeInsets.symmetric(horizontal: 26, vertical: 30),
            child: Center(
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 420),
                child: Form(
                  key: _form,
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      const Center(child: Icon(Icons.verified_user_outlined,
                          color: MintTickPalette.teal, size: 58)),
                      const SizedBox(height: 22),
                      const Text('Set a new password',
                          style: TextStyle(fontSize: 25, fontWeight: FontWeight.w800)),
                      const SizedBox(height: 8),
                      const Text('Enter the code emailed to you, then choose a secure passphrase.',
                          style: TextStyle(color: Color(0xFFB0C0CA), fontSize: 14)),
                      const SizedBox(height: 24),
                      TextFormField(
                        key: const ValueKey('minttick-reset-code'),
                        controller: _code,
                        enabled: !_busy,
                        keyboardType: TextInputType.number,
                        autofillHints: const [AutofillHints.oneTimeCode],
                        inputFormatters: [
                          FilteringTextInputFormatter.digitsOnly,
                          LengthLimitingTextInputFormatter(6),
                        ],
                        decoration: InputDecoration(
                          labelText: '6-digit verification code',
                          filled: true,
                          fillColor: const Color(0xFF15232D),
                          border: OutlineInputBorder(borderRadius: BorderRadius.circular(14)),
                        ),
                        validator: (v) => v?.length == 6 ? null : 'Enter the six-digit code',
                      ),
                      const SizedBox(height: 14),
                      TextFormField(
                        key: const ValueKey('minttick-new-password'),
                        controller: _password,
                        enabled: !_busy,
                        obscureText: !_showPassword,
                        enableSuggestions: false,
                        autocorrect: false,
                        autofillHints: const [AutofillHints.newPassword],
                        decoration: InputDecoration(
                          labelText: 'New password',
                          filled: true,
                          fillColor: const Color(0xFF15232D),
                          suffixIcon: IconButton(
                            tooltip: _showPassword ? 'Hide password' : 'Show password',
                            onPressed: () => setState(() => _showPassword = !_showPassword),
                            icon: Icon(_showPassword
                                ? Icons.visibility_outlined : Icons.visibility_off_outlined),
                          ),
                          border: OutlineInputBorder(borderRadius: BorderRadius.circular(14)),
                        ),
                        validator: (v) => (v?.runes.length ?? 0) >= 15
                            ? null : 'Use at least 15 characters',
                      ),
                      const SizedBox(height: 14),
                      TextFormField(
                        key: const ValueKey('minttick-confirm-new-password'),
                        controller: _confirmation,
                        enabled: !_busy,
                        obscureText: !_showPassword,
                        enableSuggestions: false,
                        autocorrect: false,
                        autofillHints: const [AutofillHints.newPassword],
                        decoration: InputDecoration(
                          labelText: 'Confirm new password',
                          filled: true,
                          fillColor: const Color(0xFF15232D),
                          border: OutlineInputBorder(borderRadius: BorderRadius.circular(14)),
                        ),
                        validator: (v) => v == _password.text
                            ? null : 'Passwords do not match',
                      ),
                      const SizedBox(height: 24),
                      SizedBox(
                        height: 54,
                        child: FilledButton(
                          key: const ValueKey('minttick-confirm-reset'),
                          onPressed: _busy ? null : _confirm,
                          style: FilledButton.styleFrom(
                            backgroundColor: MintTickPalette.teal,
                            foregroundColor: MintTickPalette.navy,
                          ),
                          child: const Text('Reset password'),
                        ),
                      ),
                    ],
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

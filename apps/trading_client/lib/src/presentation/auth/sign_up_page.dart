import 'package:flutter/material.dart';

import 'auth_components.dart';

class SignUpPage extends StatefulWidget {
  const SignUpPage({required this.onBack, required this.onCreateAccount, super.key});

  final VoidCallback onBack;
  final void Function({required String fullName, required String email, required String phone}) onCreateAccount;

  @override
  State<SignUpPage> createState() => _SignUpPageState();
}

class _SignUpPageState extends State<SignUpPage> {
  final _formKey = GlobalKey<FormState>();
  final _fullName = TextEditingController();
  final _email = TextEditingController();
  final _phone = TextEditingController();
  final _password = TextEditingController();
  final _confirmPassword = TextEditingController();

  bool _obscurePassword = true;
  bool _obscureConfirmPassword = true;

  @override
  void dispose() {
    _fullName.dispose();
    _email.dispose();
    _phone.dispose();
    _password.dispose();
    _confirmPassword.dispose();
    super.dispose();
  }

  bool get _hasLength => _password.text.length >= 8;
  bool get _hasUpper => RegExp(r'[A-Z]').hasMatch(_password.text);
  bool get _hasNumber => RegExp(r'[0-9]').hasMatch(_password.text);
  bool get _hasSpecial => RegExp(r'[^A-Za-z0-9]').hasMatch(_password.text);

  @override
  Widget build(BuildContext context) {
    return AuthShell(
      padding: const EdgeInsets.fromLTRB(20, 10, 20, 20),
      child: Form(
        key: _formKey,
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            AuthBackButton(onPressed: widget.onBack),
            const TradingBrand(compact: true),
            const SizedBox(height: 12),
            const Text(
              'Join and start your AI trading journey',
              textAlign: TextAlign.center,
              style: TextStyle(color: AuthPalette.muted, fontSize: 12, fontWeight: FontWeight.w600),
            ),
            const SizedBox(height: 18),
            AuthTextField(
              key: const ValueKey('auth-sign-up-name'),
              controller: _fullName,
              hintText: 'Full Name',
              prefixIcon: Icons.person_outline_rounded,
              textInputAction: TextInputAction.next,
              validator: (value) => value == null || value.trim().length < 2 ? 'Enter your full name' : null,
            ),
            const SizedBox(height: 10),
            AuthTextField(
              key: const ValueKey('auth-sign-up-email'),
              controller: _email,
              hintText: 'Email Address',
              prefixIcon: Icons.mail_outline_rounded,
              keyboardType: TextInputType.emailAddress,
              textInputAction: TextInputAction.next,
              validator: (value) {
                final input = value?.trim() ?? '';
                if (!RegExp(r'^[^@\s]+@[^@\s]+\.[^@\s]+$').hasMatch(input)) return 'Enter a valid email address';
                return null;
              },
            ),
            const SizedBox(height: 10),
            AuthTextField(
              key: const ValueKey('auth-sign-up-phone'),
              controller: _phone,
              hintText: 'Phone Number',
              prefixIcon: Icons.phone_outlined,
              keyboardType: TextInputType.phone,
              textInputAction: TextInputAction.next,
              validator: (value) {
                final digits = (value ?? '').replaceAll(RegExp(r'\D'), '');
                return digits.length < 10 ? 'Enter a valid phone number' : null;
              },
            ),
            const SizedBox(height: 10),
            AuthTextField(
              key: const ValueKey('auth-sign-up-password'),
              controller: _password,
              hintText: 'Password',
              prefixIcon: Icons.lock_outline_rounded,
              obscureText: _obscurePassword,
              textInputAction: TextInputAction.next,
              suffixIcon: IconButton(
                onPressed: () => setState(() => _obscurePassword = !_obscurePassword),
                icon: Icon(_obscurePassword ? Icons.visibility_off_outlined : Icons.visibility_outlined, color: AuthPalette.muted, size: 19),
              ),
              validator: (_) => _hasLength && _hasUpper && _hasNumber && _hasSpecial ? null : 'Use a stronger password',
            ),
            const SizedBox(height: 10),
            AuthTextField(
              key: const ValueKey('auth-sign-up-confirm-password'),
              controller: _confirmPassword,
              hintText: 'Confirm Password',
              prefixIcon: Icons.lock_outline_rounded,
              obscureText: _obscureConfirmPassword,
              suffixIcon: IconButton(
                onPressed: () => setState(() => _obscureConfirmPassword = !_obscureConfirmPassword),
                icon: Icon(_obscureConfirmPassword ? Icons.visibility_off_outlined : Icons.visibility_outlined, color: AuthPalette.muted, size: 19),
              ),
              validator: (value) => value != _password.text ? 'Passwords do not match' : null,
            ),
            const SizedBox(height: 10),
            AnimatedBuilder(
              animation: _password,
              builder: (context, _) => Container(
                width: double.infinity,
                padding: const EdgeInsets.fromLTRB(13, 10, 13, 10),
                decoration: BoxDecoration(
                  color: AuthPalette.cyan.withValues(alpha: .06),
                  border: Border.all(color: AuthPalette.cyan.withValues(alpha: .32)),
                  borderRadius: BorderRadius.circular(12),
                ),
                child: Column(
                  children: [
                    _Requirement(ok: _hasLength, label: 'At least 8 characters'),
                    _Requirement(ok: _hasUpper, label: 'One uppercase letter'),
                    _Requirement(ok: _hasNumber, label: 'One number'),
                    _Requirement(ok: _hasSpecial, label: 'One special character'),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 14),
            GradientAuthButton(
              key: const ValueKey('auth-create-account'),
              label: 'Create Account',
              onPressed: _submit,
            ),
            const SizedBox(height: 16),
            Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                const Text('Already have an account? ', style: TextStyle(color: AuthPalette.muted, fontSize: 11.5, fontWeight: FontWeight.w600)),
                TextButton(
                  key: const ValueKey('auth-sign-up-back-to-sign-in'),
                  onPressed: widget.onBack,
                  style: TextButton.styleFrom(
                    foregroundColor: AuthPalette.cyan,
                    minimumSize: Size.zero,
                    padding: EdgeInsets.zero,
                    tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                  ),
                  child: const Text('Sign In', style: TextStyle(fontSize: 11.5, fontWeight: FontWeight.w900)),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  void _submit() {
    if (!(_formKey.currentState?.validate() ?? false)) return;
    widget.onCreateAccount(
      fullName: _fullName.text.trim(),
      email: _email.text.trim(),
      phone: _phone.text.trim(),
    );
  }
}

class _Requirement extends StatelessWidget {
  const _Requirement({required this.ok, required this.label});
  final bool ok;
  final String label;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 2.5),
      child: Row(
        children: [
          Icon(ok ? Icons.check_rounded : Icons.circle_outlined, color: ok ? AuthPalette.cyan : const Color(0xFF667D9D), size: 16),
          const SizedBox(width: 7),
          Text(label, style: TextStyle(color: ok ? Colors.white : AuthPalette.muted, fontSize: 11.5, fontWeight: FontWeight.w600)),
        ],
      ),
    );
  }
}

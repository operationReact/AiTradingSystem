import 'package:flutter/material.dart';

import 'auth_components.dart';

class PasswordSignInPage extends StatefulWidget {
  const PasswordSignInPage({
    required this.biometricEnrolled,
    required this.onSignUp,
    required this.onFingerprintSignIn,
    required this.onPasswordSignIn,
    super.key,
  });

  final bool biometricEnrolled;
  final VoidCallback onSignUp;
  final VoidCallback onFingerprintSignIn;
  final void Function(String identifier, String password) onPasswordSignIn;

  @override
  State<PasswordSignInPage> createState() => _PasswordSignInPageState();
}

class _PasswordSignInPageState extends State<PasswordSignInPage> {
  final _formKey = GlobalKey<FormState>();
  final _identifierController = TextEditingController();
  final _passwordController = TextEditingController();
  bool _rememberMe = true;
  bool _obscurePassword = true;

  @override
  void dispose() {
    _identifierController.dispose();
    _passwordController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AuthShell(
      child: Form(
        key: _formKey,
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            const TradingBrand(),
            const SizedBox(height: 24),
            const Text(
              'Trade Smarter\nwith AI',
              textAlign: TextAlign.center,
              style: TextStyle(color: Colors.white, fontSize: 29, height: 1.05, fontWeight: FontWeight.w900, letterSpacing: -0.7),
            ),
            const SizedBox(height: 10),
            const Text(
              'AI research  •  Paper trading  •\nRisk controls  •  Real trading when ready',
              textAlign: TextAlign.center,
              style: TextStyle(color: AuthPalette.muted, fontSize: 11.5, height: 1.45, fontWeight: FontWeight.w600),
            ),
            const SizedBox(height: 12),
            const MarketWave(height: 126),
            const SizedBox(height: 14),
            AuthTextField(
              key: const ValueKey('auth-sign-in-identifier'),
              controller: _identifierController,
              hintText: 'Email or Phone Number',
              keyboardType: TextInputType.emailAddress,
              prefixIcon: Icons.mail_outline_rounded,
              textInputAction: TextInputAction.next,
              validator: (value) => value == null || value.trim().isEmpty ? 'Enter your email or phone number' : null,
            ),
            const SizedBox(height: 11),
            AuthTextField(
              key: const ValueKey('auth-sign-in-password'),
              controller: _passwordController,
              hintText: 'Password',
              prefixIcon: Icons.lock_outline_rounded,
              obscureText: _obscurePassword,
              suffixIcon: IconButton(
                tooltip: _obscurePassword ? 'Show password' : 'Hide password',
                onPressed: () => setState(() => _obscurePassword = !_obscurePassword),
                icon: Icon(_obscurePassword ? Icons.visibility_off_outlined : Icons.visibility_outlined, color: AuthPalette.muted, size: 19),
              ),
              validator: (value) => value == null || value.isEmpty ? 'Enter your password' : null,
            ),
            const SizedBox(height: 8),
            Row(
              children: [
                SizedBox(
                  width: 22,
                  height: 22,
                  child: Checkbox(
                    value: _rememberMe,
                    onChanged: (value) => setState(() => _rememberMe = value ?? true),
                    activeColor: AuthPalette.cyan,
                    checkColor: const Color(0xFF06111F),
                    side: const BorderSide(color: Color(0xFF4A6284)),
                    visualDensity: VisualDensity.compact,
                  ),
                ),
                const SizedBox(width: 5),
                const Text('Remember me', style: TextStyle(color: Colors.white, fontSize: 11, fontWeight: FontWeight.w600)),
                const Spacer(),
                TextButton(
                  key: const ValueKey('auth-forgot-password'),
                  onPressed: () {
                    ScaffoldMessenger.of(context)
                      ..hideCurrentSnackBar()
                      ..showSnackBar(const SnackBar(behavior: SnackBarBehavior.floating, content: Text('Password recovery will be connected to the customer auth API.')));
                  },
                  style: TextButton.styleFrom(foregroundColor: AuthPalette.cyan, padding: EdgeInsets.zero, minimumSize: Size.zero, tapTargetSize: MaterialTapTargetSize.shrinkWrap),
                  child: const Text('Forgot password?', style: TextStyle(fontSize: 11, fontWeight: FontWeight.w700)),
                ),
              ],
            ),
            const SizedBox(height: 13),
            GradientAuthButton(
              key: const ValueKey('auth-sign-in-submit'),
              label: 'Sign In',
              icon: Icons.arrow_forward_rounded,
              onPressed: _submit,
            ),
            if (widget.biometricEnrolled) ...[
              const SizedBox(height: 11),
              FingerprintOutlineButton(key: const ValueKey('auth-fingerprint-sign-in'), onPressed: widget.onFingerprintSignIn),
            ],
            const SizedBox(height: 20),
            Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                const Text("Don't have an account? ", style: TextStyle(color: AuthPalette.muted, fontSize: 11.5, fontWeight: FontWeight.w600)),
                TextButton(
                  key: const ValueKey('auth-open-sign-up'),
                  onPressed: widget.onSignUp,
                  style: TextButton.styleFrom(foregroundColor: AuthPalette.cyan, padding: EdgeInsets.zero, minimumSize: Size.zero, tapTargetSize: MaterialTapTargetSize.shrinkWrap),
                  child: const Text('Sign Up', style: TextStyle(fontSize: 11.5, fontWeight: FontWeight.w900)),
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
    widget.onPasswordSignIn(_identifierController.text.trim(), _passwordController.text);
  }
}

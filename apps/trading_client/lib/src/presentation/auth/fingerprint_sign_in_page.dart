import 'package:flutter/material.dart';

import 'auth_components.dart';
import 'biometric_auth_service.dart';

class FingerprintSignInPage extends StatefulWidget {
  const FingerprintSignInPage({
    required this.onUsePassword,
    required this.onAuthenticated,
    this.service,
    super.key,
  });

  final VoidCallback onUsePassword;
  final VoidCallback onAuthenticated;
  final BiometricAuthService? service;

  @override
  State<FingerprintSignInPage> createState() => _FingerprintSignInPageState();
}

class _FingerprintSignInPageState extends State<FingerprintSignInPage> {
  late final BiometricAuthService _service = widget.service ?? BiometricAuthService();
  bool _authenticating = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) => _authenticate());
  }

  Future<void> _authenticate() async {
    if (_authenticating) return;
    setState(() {
      _authenticating = true;
      _error = null;
    });

    final success = await _service.authenticateFingerprint(
      reason: 'Use your fingerprint to sign in to AiTradingSystem',
    );
    if (!mounted) return;

    if (success) {
      widget.onAuthenticated();
    } else {
      setState(() {
        _authenticating = false;
        _error = 'Fingerprint was not verified. Try again or use your password.';
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return AuthShell(
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          const TradingBrand(compact: true),
          const SizedBox(height: 54),
          GestureDetector(
            key: const ValueKey('auth-fingerprint-retry'),
            onTap: _authenticate,
            child: AnimatedContainer(
              duration: const Duration(milliseconds: 220),
              width: 126,
              height: 126,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                gradient: const RadialGradient(
                  colors: [Color(0x2228F4FF), Color(0x221D78FF), Color(0x1129146A)],
                ),
                border: Border.all(color: AuthPalette.cyan, width: 2.4),
                boxShadow: [
                  BoxShadow(
                    color: (_authenticating ? AuthPalette.cyan : AuthPalette.violet).withValues(alpha: .34),
                    blurRadius: 36,
                    spreadRadius: 4,
                  ),
                ],
              ),
              child: Icon(
                Icons.fingerprint_rounded,
                color: _authenticating ? AuthPalette.cyan : const Color(0xFF69D7FF),
                size: 79,
              ),
            ),
          ),
          const SizedBox(height: 28),
          const Text(
            'Sign in with Fingerprint',
            textAlign: TextAlign.center,
            style: TextStyle(color: Colors.white, fontSize: 22, fontWeight: FontWeight.w900, letterSpacing: -0.45),
          ),
          const SizedBox(height: 9),
          Text(
            _authenticating ? 'Touch the fingerprint sensor to continue' : 'Tap the fingerprint to try again',
            textAlign: TextAlign.center,
            style: const TextStyle(color: AuthPalette.muted, fontSize: 12.5, fontWeight: FontWeight.w600),
          ),
          if (_error != null) ...[
            const SizedBox(height: 16),
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
              decoration: BoxDecoration(
                color: const Color(0xFF241625),
                border: Border.all(color: const Color(0xFF7D3C65)),
                borderRadius: BorderRadius.circular(12),
              ),
              child: Text(
                _error!,
                textAlign: TextAlign.center,
                style: const TextStyle(color: Color(0xFFFFB8D3), fontSize: 11.5, height: 1.35, fontWeight: FontWeight.w600),
              ),
            ),
          ],
          const SizedBox(height: 70),
          TextButton(
            key: const ValueKey('auth-use-password-instead'),
            onPressed: widget.onUsePassword,
            style: TextButton.styleFrom(foregroundColor: AuthPalette.cyan),
            child: const Text('Use Password Instead', style: TextStyle(fontSize: 12.5, fontWeight: FontWeight.w900)),
          ),
        ],
      ),
    );
  }
}

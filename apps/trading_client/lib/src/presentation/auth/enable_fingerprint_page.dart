import 'package:flutter/material.dart';

import 'auth_components.dart';
import 'biometric_auth_service.dart';

class EnableFingerprintPage extends StatefulWidget {
  const EnableFingerprintPage({
    required this.onBack,
    required this.onEnabled,
    required this.onSkip,
    this.service,
    super.key,
  });

  final VoidCallback onBack;
  final VoidCallback onEnabled;
  final VoidCallback onSkip;
  final BiometricAuthService? service;

  @override
  State<EnableFingerprintPage> createState() => _EnableFingerprintPageState();
}

class _EnableFingerprintPageState extends State<EnableFingerprintPage> {
  late final BiometricAuthService _service = widget.service ?? BiometricAuthService();
  bool _checking = true;
  bool _available = false;
  bool _enabling = false;

  @override
  void initState() {
    super.initState();
    _checkAvailability();
  }

  Future<void> _checkAvailability() async {
    final available = await _service.isFingerprintAvailable();
    if (!mounted) return;
    setState(() {
      _available = available;
      _checking = false;
    });
  }

  Future<void> _enable() async {
    if (!_available || _enabling) return;
    setState(() => _enabling = true);
    final authenticated = await _service.authenticateFingerprint(
      reason: 'Confirm your fingerprint to enable faster AiTradingSystem sign in',
    );
    if (!mounted) return;
    if (authenticated) {
      await _service.setFingerprintEnabledForApp(true);
      if (!mounted) return;
      widget.onEnabled();
    } else {
      setState(() => _enabling = false);
      ScaffoldMessenger.of(context)
        ..hideCurrentSnackBar()
        ..showSnackBar(const SnackBar(
          behavior: SnackBarBehavior.floating,
          content: Text('Fingerprint verification was not completed.'),
        ));
    }
  }

  @override
  Widget build(BuildContext context) {
    return AuthShell(
      padding: const EdgeInsets.fromLTRB(20, 10, 20, 20),
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          AuthBackButton(onPressed: widget.onBack),
          const SizedBox(height: 4),
          const Align(
            alignment: Alignment.centerLeft,
            child: Text(
              'Enable Fingerprint',
              style: TextStyle(color: Colors.white, fontSize: 22, fontWeight: FontWeight.w900),
            ),
          ),
          const SizedBox(height: 26),
          Container(
            width: 116,
            height: 116,
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(30),
              gradient: const LinearGradient(
                begin: Alignment.topLeft,
                end: Alignment.bottomRight,
                colors: [Color(0x2212E7F4), Color(0x226B46FF)],
              ),
              border: Border.all(color: AuthPalette.cyan.withValues(alpha: .72), width: 1.4),
              boxShadow: const [BoxShadow(color: Color(0x3331DEFF), blurRadius: 32)],
            ),
            child: const Icon(Icons.fingerprint_rounded, color: AuthPalette.cyan, size: 76),
          ),
          const SizedBox(height: 24),
          const Text(
            'Use Fingerprint for\nfaster sign in',
            textAlign: TextAlign.center,
            style: TextStyle(color: Colors.white, fontSize: 22, height: 1.08, fontWeight: FontWeight.w900, letterSpacing: -0.4),
          ),
          const SizedBox(height: 18),
          const Column(
            children: [
              AuthInfoRow(icon: Icons.bolt_rounded, title: 'Quick & secure access'),
              SizedBox(height: 8),
              AuthInfoRow(icon: Icons.shield_outlined, title: 'Your biometric data stays on this device'),
              SizedBox(height: 8),
              AuthInfoRow(icon: Icons.phonelink_lock_outlined, title: 'Works only after device fingerprint enrollment'),
            ],
          ),
          const SizedBox(height: 24),
          if (!_checking && !_available)
            Container(
              width: double.infinity,
              margin: const EdgeInsets.only(bottom: 12),
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: const Color(0xFF241625),
                borderRadius: BorderRadius.circular(12),
                border: Border.all(color: const Color(0xFF7D3C65)),
              ),
              child: const Text(
                'No enrolled fingerprint is available on this device. Add one in your device settings first.',
                textAlign: TextAlign.center,
                style: TextStyle(color: Color(0xFFFFB8D3), fontSize: 11.5, height: 1.35, fontWeight: FontWeight.w600),
              ),
            ),
          GradientAuthButton(
            key: const ValueKey('auth-enable-fingerprint'),
            label: _checking
                ? 'Checking device…'
                : _enabling
                    ? 'Confirming…'
                    : 'Enable Fingerprint',
            enabled: !_checking && _available && !_enabling,
            onPressed: _enable,
          ),
          const SizedBox(height: 14),
          TextButton(
            key: const ValueKey('auth-skip-fingerprint'),
            onPressed: widget.onSkip,
            style: TextButton.styleFrom(foregroundColor: Colors.white),
            child: const Text('Maybe Later', style: TextStyle(fontSize: 12.5, fontWeight: FontWeight.w800)),
          ),
        ],
      ),
    );
  }
}

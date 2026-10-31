import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';

import '../data/auth_repository.dart';
import '../theme/app_theme.dart';

class SecureSignInPage extends StatefulWidget {
  const SecureSignInPage({
    required this.repository,
    required this.apiBaseUri,
    super.key,
  });

  final AuthRepository repository;
  final Uri apiBaseUri;

  @override
  State<SecureSignInPage> createState() => _SecureSignInPageState();
}

class _SecureSignInPageState extends State<SecureSignInPage> {
  AuthProviderConfig? _config;
  bool _loading = true;
  bool _launching = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final config = await widget.repository.fetchProviderConfig();
      if (!mounted) return;
      setState(() => _config = config);
    } on AuthConfigFailure {
      if (!mounted) return;
      setState(() => _error = 'Sign-in service is temporarily unavailable.');
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<void> _continue() async {
    final config = _config;
    final path = config?.authorizationStartPath;
    if (config == null || !config.enabled || path == null || _launching) return;

    setState(() => _launching = true);
    try {
      final launched = await launchUrl(
        widget.apiBaseUri.resolve(path),
        mode: LaunchMode.externalApplication,
      );
      if (!launched && mounted) {
        setState(() => _error = 'Unable to open the secure identity provider.');
      }
    } catch (_) {
      if (mounted) setState(() => _error = 'Unable to open the secure identity provider.');
    } finally {
      if (mounted) setState(() => _launching = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.extension<TradingColors>()!;
    final scheme = theme.colorScheme;
    final config = _config;
    final providerReady = config?.enabled == true && config?.authorizationStartPath != null;

    return Scaffold(
      body: DecoratedBox(
        decoration: const BoxDecoration(
          gradient: LinearGradient(
            begin: Alignment.centerLeft,
            end: Alignment.centerRight,
            colors: [Color(0xFF020511), Color(0xFF05061C), Color(0xFF14031C)],
            stops: [0, 0.48, 1],
          ),
        ),
        child: Stack(
          children: [
            const Positioned.fill(child: _CyberGrid()),
            Positioned(
              left: -52,
              top: -44,
              child: _Aura(color: colors.neonCyan, size: 230),
            ),
            Positioned(
              right: -80,
              bottom: 36,
              child: _Aura(color: colors.neonMagenta, size: 220),
            ),
            SafeArea(
              child: LayoutBuilder(
                builder: (context, constraints) {
                  final width = constraints.maxWidth;
                  final maxWidth = width < 700 ? 430.0 : 640.0;
                  return SingleChildScrollView(
                    padding: const EdgeInsets.fromLTRB(16, 24, 16, 28),
                    child: Center(
                      child: ConstrainedBox(
                        constraints: BoxConstraints(maxWidth: maxWidth),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            ShaderMask(
                              shaderCallback: (bounds) => LinearGradient(
                                colors: [colors.neonCyan, colors.neonViolet, colors.neonMagenta],
                              ).createShader(bounds),
                              child: Text(
                                'Secure Sign In',
                                key: const ValueKey('secure-sign-in-title'),
                                style: theme.textTheme.headlineMedium?.copyWith(
                                  color: Colors.white,
                                  fontWeight: FontWeight.w900,
                                ),
                              ),
                            ),
                            const SizedBox(height: 5),
                            Text(
                              'Session creation, MFA/step-up readiness and device security',
                              style: theme.textTheme.bodySmall?.copyWith(color: const Color(0xFF5F6D87)),
                            ),
                            const SizedBox(height: 48),
                            Container(
                              width: double.infinity,
                              padding: const EdgeInsets.fromLTRB(18, 20, 18, 22),
                              decoration: BoxDecoration(
                                borderRadius: BorderRadius.circular(24),
                                gradient: const LinearGradient(
                                  colors: [Color(0xFFF7FBFF), Color(0xFFF8F9FF), Color(0xFFFFF0FC)],
                                ),
                                border: Border.all(color: const Color(0x5700EFFF)),
                                boxShadow: [
                                  BoxShadow(color: colors.neonCyan.withValues(alpha: 0.12), blurRadius: 28),
                                  BoxShadow(color: colors.neonMagenta.withValues(alpha: 0.08), blurRadius: 34),
                                ],
                              ),
                              child: Theme(
                                data: theme.copyWith(
                                  textTheme: theme.textTheme.apply(
                                    bodyColor: const Color(0xFF10172A),
                                    displayColor: const Color(0xFF10172A),
                                  ),
                                ),
                                child: Column(
                                  crossAxisAlignment: CrossAxisAlignment.start,
                                  children: [
                                    _StatusPill(
                                      label: providerReady ? 'SECURE SESSION' : 'SIGN-IN SETUP REQUIRED',
                                      positive: providerReady,
                                    ),
                                    const SizedBox(height: 18),
                                    Text(
                                      'End-to-end flow',
                                      style: theme.textTheme.titleMedium?.copyWith(
                                        color: const Color(0xFF10172A),
                                        fontWeight: FontWeight.w900,
                                      ),
                                    ),
                                    const SizedBox(height: 16),
                                    _JourneyStep(index: 1, label: config?.providerName ?? 'Identity / approved IdP'),
                                    const SizedBox(height: 10),
                                    const _JourneyStep(index: 2, label: 'Password or approved challenge'),
                                    const SizedBox(height: 10),
                                    const _JourneyStep(index: 3, label: 'MFA / step-up if required'),
                                    const SizedBox(height: 10),
                                    const _JourneyStep(index: 4, label: 'Account-scoped session issued', active: true),
                                    const SizedBox(height: 18),
                                    Container(
                                      height: 3,
                                      decoration: BoxDecoration(
                                        borderRadius: BorderRadius.circular(2),
                                        gradient: LinearGradient(
                                          colors: [colors.neonCyan, colors.neonViolet, colors.neonMagenta],
                                        ),
                                      ),
                                    ),
                                    const SizedBox(height: 14),
                                    const Text(
                                      'States: loading • success • warning • stale • forbidden • server error • retry',
                                      style: TextStyle(color: Color(0xFF5F6D87), fontSize: 10),
                                    ),
                                    const SizedBox(height: 18),
                                    if (_loading)
                                      const Center(child: CircularProgressIndicator())
                                    else if (_error != null)
                                      _MessageCard(
                                        key: const ValueKey('secure-sign-in-error'),
                                        icon: Icons.cloud_off_rounded,
                                        title: 'Sign-in service unavailable',
                                        body: _error!,
                                      )
                                    else if (!providerReady)
                                      const _MessageCard(
                                        key: ValueKey('secure-sign-in-not-configured'),
                                        icon: Icons.admin_panel_settings_outlined,
                                        title: 'Production identity provider not configured',
                                        body: 'The app is failing closed. No developer key or embedded credential will be accepted.',
                                      ),
                                    const SizedBox(height: 16),
                                    Wrap(
                                      spacing: 10,
                                      runSpacing: 10,
                                      children: [
                                        _GradientButton(
                                          key: const ValueKey('secure-sign-in-continue'),
                                          label: _launching ? 'Opening…' : 'Continue',
                                          enabled: providerReady && !_launching,
                                          onPressed: _continue,
                                        ),
                                        OutlinedButton.icon(
                                          onPressed: _loading ? null : _load,
                                          icon: const Icon(Icons.shield_outlined),
                                          label: const Text('Security help'),
                                          style: OutlinedButton.styleFrom(
                                            foregroundColor: const Color(0xFF10172A),
                                            side: const BorderSide(color: Color(0x5500D9FF)),
                                            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
                                          ),
                                        ),
                                      ],
                                    ),
                                  ],
                                ),
                              ),
                            ),
                            const SizedBox(height: 18),
                            Text(
                              providerReady
                                  ? 'Continue opens the configured identity provider. Account session creation remains server-authoritative.'
                                  : 'An approved identity provider must be configured server-side before sign-in can begin.',
                              style: theme.textTheme.bodySmall?.copyWith(color: const Color(0xFF8FA8CC), height: 1.45),
                            ),
                          ],
                        ),
                      ),
                    ),
                  );
                },
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _JourneyStep extends StatelessWidget {
  const _JourneyStep({required this.index, required this.label, this.active = false});

  final int index;
  final String label;
  final bool active;

  @override
  Widget build(BuildContext context) => Container(
        width: double.infinity,
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
        decoration: BoxDecoration(
          color: const Color(0xD1F8FBFF),
          borderRadius: BorderRadius.circular(13),
          border: Border.all(color: const Color(0x1A25E9FF)),
        ),
        child: Row(
          children: [
            Container(
              width: 30,
              height: 30,
              alignment: Alignment.center,
              decoration: BoxDecoration(
                color: active ? const Color(0xFF7C5CFF) : const Color(0xFF081127),
                borderRadius: BorderRadius.circular(15),
                border: Border.all(color: const Color(0x8000D9FF)),
              ),
              child: Text('$index', style: const TextStyle(color: Colors.white, fontWeight: FontWeight.w800, fontSize: 12)),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Text(
                label,
                style: const TextStyle(color: Color(0xFF10172A), fontWeight: FontWeight.w700, fontSize: 12),
              ),
            ),
          ],
        ),
      );
}

class _StatusPill extends StatelessWidget {
  const _StatusPill({required this.label, required this.positive});
  final String label;
  final bool positive;

  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 7),
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(999),
          color: positive ? const Color(0x1F0EA66E) : const Color(0x1FFF8B38),
          border: Border.all(color: positive ? const Color(0x7300D9FF) : const Color(0x66FF8B38)),
        ),
        child: Text(
          label,
          style: TextStyle(
            color: positive ? const Color(0xFF0EA66E) : const Color(0xFF9A4E00),
            fontWeight: FontWeight.w700,
            fontSize: 11,
          ),
        ),
      );
}

class _MessageCard extends StatelessWidget {
  const _MessageCard({required this.icon, required this.title, required this.body, super.key});
  final IconData icon;
  final String title;
  final String body;

  @override
  Widget build(BuildContext context) => Container(
        width: double.infinity,
        padding: const EdgeInsets.all(14),
        decoration: BoxDecoration(
          color: const Color(0x0D081127),
          borderRadius: BorderRadius.circular(14),
          border: Border.all(color: const Color(0x22081A2D)),
        ),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Icon(icon, color: const Color(0xFF6E40FF), size: 20),
            const SizedBox(width: 10),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(title, style: const TextStyle(color: Color(0xFF10172A), fontWeight: FontWeight.w800)),
                  const SizedBox(height: 4),
                  Text(body, style: const TextStyle(color: Color(0xFF5F6D87), fontSize: 12, height: 1.4)),
                ],
              ),
            ),
          ],
        ),
      );
}

class _GradientButton extends StatelessWidget {
  const _GradientButton({required this.label, required this.enabled, required this.onPressed, super.key});
  final String label;
  final bool enabled;
  final VoidCallback onPressed;

  @override
  Widget build(BuildContext context) => Opacity(
        opacity: enabled ? 1 : 0.45,
        child: DecoratedBox(
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(12),
            gradient: const LinearGradient(colors: [Color(0xFF0DEBFF), Color(0xFF6E40FF), Color(0xFFFF24C7)]),
            boxShadow: enabled
                ? const [
                    BoxShadow(color: Color(0x520DEBFF), blurRadius: 20),
                    BoxShadow(color: Color(0x2EFF24C7), blurRadius: 28),
                  ]
                : null,
          ),
          child: Material(
            color: Colors.transparent,
            child: InkWell(
              borderRadius: BorderRadius.circular(12),
              onTap: enabled ? onPressed : null,
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
                child: Text(label, style: const TextStyle(color: Colors.white, fontWeight: FontWeight.w800, fontSize: 13)),
              ),
            ),
          ),
        ),
      );
}

class _CyberGrid extends StatelessWidget {
  const _CyberGrid();

  @override
  Widget build(BuildContext context) => CustomPaint(painter: _CyberGridPainter());
}

class _CyberGridPainter extends CustomPainter {
  @override
  void paint(Canvas canvas, Size size) {
    final vertical = Paint()..color = const Color(0x0C4D67FF)..strokeWidth = 1;
    final horizontal = Paint()..color = const Color(0x08A24CFF)..strokeWidth = 1;
    for (double x = 0; x <= size.width; x += 36) {
      canvas.drawLine(Offset(x, 0), Offset(x, size.height), vertical);
    }
    for (double y = 0; y <= size.height; y += 36) {
      canvas.drawLine(Offset(0, y), Offset(size.width, y), horizontal);
    }
  }

  @override
  bool shouldRepaint(covariant CustomPainter oldDelegate) => false;
}

class _Aura extends StatelessWidget {
  const _Aura({required this.color, required this.size});
  final Color color;
  final double size;

  @override
  Widget build(BuildContext context) => IgnorePointer(
        child: Container(
          width: size,
          height: size,
          decoration: BoxDecoration(
            shape: BoxShape.circle,
            gradient: RadialGradient(
              colors: [color.withValues(alpha: 0.22), color.withValues(alpha: 0)],
            ),
          ),
        ),
      );
}

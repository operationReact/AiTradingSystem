import 'package:flutter/material.dart';

class SignInPage extends StatelessWidget {
  const SignInPage({super.key});

  static const _providerName = String.fromEnvironment('AUTH_PROVIDER_NAME');
  static const _authConfigured = bool.fromEnvironment(
    'AUTH_PROVIDER_CONFIGURED',
    defaultValue: false,
  );

  static const _background = Color(0xFF030817);
  static const _cyan = Color(0xFF0DEBFF);
  static const _violet = Color(0xFF6E40FF);
  static const _magenta = Color(0xFFFF24C7);
  static const _muted = Color(0xFF8FA8CC);

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: _background,
      body: Stack(
        children: [
          const Positioned.fill(child: _AuthBackdrop()),
          SafeArea(
            child: LayoutBuilder(
              builder: (context, constraints) {
                return SingleChildScrollView(
                  physics: const ClampingScrollPhysics(),
                  padding: const EdgeInsets.fromLTRB(22, 24, 22, 24),
                  child: ConstrainedBox(
                    constraints: BoxConstraints(
                      minHeight: (constraints.maxHeight - 48)
                          .clamp(0.0, double.infinity)
                          .toDouble(),
                    ),
                    child: Center(
                      child: ConstrainedBox(
                        constraints: const BoxConstraints(maxWidth: 390),
                        child: Column(
                          mainAxisAlignment: MainAxisAlignment.center,
                          children: [
                            const _TradingBrand(),
                            const SizedBox(height: 36),
                            Semantics(
                              header: true,
                              child: const Text(
                                'Trade Smarter\nwith AI',
                                textAlign: TextAlign.center,
                                style: TextStyle(
                                  color: Colors.white,
                                  fontSize: 30,
                                  height: 1.06,
                                  fontWeight: FontWeight.w900,
                                  letterSpacing: -0.8,
                                ),
                              ),
                            ),
                            const SizedBox(height: 14),
                            const Text(
                              'AI research  •  Risk-first trading  •\nReal market insights',
                              textAlign: TextAlign.center,
                              style: TextStyle(
                                color: _muted,
                                fontSize: 12.5,
                                height: 1.45,
                                fontWeight: FontWeight.w600,
                              ),
                            ),
                            const SizedBox(height: 20),
                            const SizedBox(
                              height: 210,
                              width: double.infinity,
                              child: CustomPaint(painter: _MarketWavePainter()),
                            ),
                            const SizedBox(height: 16),
                            _SocialSignInButton(
                              key: const ValueKey('secure-sign-in-google'),
                              label: 'Continue with Google',
                              leading: const _GoogleMark(),
                              onPressed: () => _showConfigurationBoundary(context, 'Google'),
                            ),
                            const SizedBox(height: 11),
                            _SocialSignInButton(
                              key: const ValueKey('secure-sign-in-apple'),
                              label: 'Continue with Apple',
                              leading: const Icon(Icons.apple, color: Colors.black, size: 22),
                              onPressed: () => _showConfigurationBoundary(context, 'Apple'),
                            ),
                            const SizedBox(height: 11),
                            _EmailSignInButton(
                              key: const ValueKey('secure-sign-in-email'),
                              onPressed: () => _showConfigurationBoundary(context, 'Email'),
                            ),
                            const SizedBox(height: 23),
                            Row(
                              mainAxisAlignment: MainAxisAlignment.center,
                              children: [
                                const Text(
                                  'New here? ',
                                  style: TextStyle(
                                    color: _muted,
                                    fontSize: 12,
                                    fontWeight: FontWeight.w600,
                                  ),
                                ),
                                TextButton(
                                  key: const ValueKey('secure-sign-in-create-account'),
                                  onPressed: () => _showConfigurationBoundary(context, 'Create account'),
                                  style: TextButton.styleFrom(
                                    foregroundColor: const Color(0xFFB794FF),
                                    padding: const EdgeInsets.symmetric(horizontal: 2, vertical: 4),
                                    minimumSize: Size.zero,
                                    tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                                  ),
                                  child: const Text(
                                    'Create Account',
                                    style: TextStyle(
                                      fontSize: 12,
                                      fontWeight: FontWeight.w800,
                                      decoration: TextDecoration.underline,
                                      decorationColor: Color(0xFFB794FF),
                                    ),
                                  ),
                                ),
                              ],
                            ),
                          ],
                        ),
                      ),
                    ),
                  ),
                );
              },
            ),
          ),
        ],
      ),
    );
  }

  void _showConfigurationBoundary(BuildContext context, String method) {
    final providerLabel = _providerName.trim().isEmpty
        ? 'approved identity provider'
        : _providerName.trim();
    final message = _authConfigured
        ? '$method secure sign-in will hand off to $providerLabel.'
        : '$method sign-in UI is ready. Production identity integration is not enabled in this build yet.';

    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(
        SnackBar(
          behavior: SnackBarBehavior.floating,
          content: Text(message),
        ),
      );
  }
}

class _TradingBrand extends StatelessWidget {
  const _TradingBrand();

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        const _TradingMark(),
        const SizedBox(height: 12),
        Text(
          'AI Trading',
          style: Theme.of(context).textTheme.titleLarge?.copyWith(
                color: Colors.white,
                fontSize: 21,
                fontWeight: FontWeight.w900,
                letterSpacing: -0.3,
              ),
        ),
      ],
    );
  }
}

class _TradingMark extends StatelessWidget {
  const _TradingMark();

  @override
  Widget build(BuildContext context) {
    const heights = <double>[18, 29, 40, 31];
    const colors = <Color>[
      SignInPage._violet,
      SignInPage._magenta,
      SignInPage._cyan,
      Color(0xFF69FFF1),
    ];

    return SizedBox(
      width: 52,
      height: 44,
      child: Row(
        mainAxisAlignment: MainAxisAlignment.center,
        crossAxisAlignment: CrossAxisAlignment.end,
        children: List.generate(
          heights.length,
          (index) => Container(
            width: 7,
            height: heights[index],
            margin: const EdgeInsets.symmetric(horizontal: 2.5),
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(6),
              gradient: LinearGradient(
                begin: Alignment.bottomCenter,
                end: Alignment.topCenter,
                colors: [colors[index].withValues(alpha: 0.55), colors[index]],
              ),
              boxShadow: [
                BoxShadow(
                  color: colors[index].withValues(alpha: 0.30),
                  blurRadius: 9,
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _SocialSignInButton extends StatelessWidget {
  const _SocialSignInButton({
    super.key,
    required this.label,
    required this.leading,
    required this.onPressed,
  });

  final String label;
  final Widget leading;
  final VoidCallback onPressed;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: double.infinity,
      height: 50,
      child: FilledButton(
        onPressed: onPressed,
        style: FilledButton.styleFrom(
          backgroundColor: Colors.white,
          foregroundColor: const Color(0xFF111318),
          elevation: 0,
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(13)),
          side: BorderSide(color: Colors.white.withValues(alpha: 0.9)),
        ),
        child: Stack(
          alignment: Alignment.center,
          children: [
            Align(alignment: Alignment.centerLeft, child: leading),
            Text(
              label,
              style: const TextStyle(
                fontSize: 14,
                fontWeight: FontWeight.w800,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _GoogleMark extends StatelessWidget {
  const _GoogleMark();

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 23,
      height: 23,
      alignment: Alignment.center,
      decoration: BoxDecoration(
        shape: BoxShape.circle,
        border: Border.all(color: const Color(0xFFE3E7EE)),
      ),
      child: const Text(
        'G',
        style: TextStyle(
          color: Color(0xFF4285F4),
          fontSize: 14,
          fontWeight: FontWeight.w900,
        ),
      ),
    );
  }
}

class _EmailSignInButton extends StatelessWidget {
  const _EmailSignInButton({super.key, required this.onPressed});

  final VoidCallback onPressed;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: double.infinity,
      height: 50,
      child: DecoratedBox(
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(13),
          gradient: const LinearGradient(
            colors: [Color(0xFF9D55FF), Color(0xFF5B31FF)],
          ),
          boxShadow: [
            BoxShadow(
              color: SignInPage._violet.withValues(alpha: 0.30),
              blurRadius: 22,
              offset: const Offset(0, 8),
            ),
          ],
        ),
        child: Material(
          color: Colors.transparent,
          child: InkWell(
            onTap: onPressed,
            borderRadius: BorderRadius.circular(13),
            child: const Center(
              child: Text(
                'Sign in with Email',
                style: TextStyle(
                  color: Colors.white,
                  fontSize: 14,
                  fontWeight: FontWeight.w900,
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _AuthBackdrop extends StatelessWidget {
  const _AuthBackdrop();

  @override
  Widget build(BuildContext context) {
    return IgnorePointer(
      child: DecoratedBox(
        decoration: const BoxDecoration(
          gradient: RadialGradient(
            center: Alignment(0.70, -0.35),
            radius: 1.15,
            colors: [
              Color(0x2214467A),
              Color(0x110A2148),
              SignInPage._background,
            ],
            stops: [0, 0.42, 1],
          ),
        ),
        child: const SizedBox.expand(),
      ),
    );
  }
}

class _MarketWavePainter extends CustomPainter {
  const _MarketWavePainter();

  @override
  void paint(Canvas canvas, Size size) {
    final grid = Paint()
      ..color = const Color(0xFF1C2A4B).withValues(alpha: 0.18)
      ..strokeWidth = 0.7;

    for (var i = 1; i < 6; i++) {
      final y = size.height * i / 7;
      canvas.drawLine(Offset(0, y), Offset(size.width, y), grid);
    }

    for (var i = 1; i < 7; i++) {
      final x = size.width * i / 8;
      canvas.drawLine(Offset(x, size.height * 0.18), Offset(x, size.height), grid);
    }

    final glowPath = Path()
      ..moveTo(-10, size.height * 0.78)
      ..cubicTo(
        size.width * 0.14,
        size.height * 0.68,
        size.width * 0.22,
        size.height * 0.85,
        size.width * 0.35,
        size.height * 0.60,
      )
      ..cubicTo(
        size.width * 0.45,
        size.height * 0.42,
        size.width * 0.53,
        size.height * 0.70,
        size.width * 0.62,
        size.height * 0.48,
      )
      ..cubicTo(
        size.width * 0.72,
        size.height * 0.22,
        size.width * 0.78,
        size.height * 0.40,
        size.width * 0.88,
        size.height * 0.18,
      )
      ..cubicTo(
        size.width * 0.94,
        size.height * 0.06,
        size.width * 1.02,
        size.height * 0.30,
        size.width + 12,
        size.height * 0.24,
      );

    final glow = Paint()
      ..style = PaintingStyle.stroke
      ..strokeCap = StrokeCap.round
      ..strokeJoin = StrokeJoin.round
      ..strokeWidth = 14
      ..maskFilter = const MaskFilter.blur(BlurStyle.normal, 18)
      ..shader = const LinearGradient(
        colors: [SignInPage._magenta, SignInPage._violet, SignInPage._cyan],
      ).createShader(Offset.zero & size);
    canvas.drawPath(glowPath, glow);

    final line = Paint()
      ..style = PaintingStyle.stroke
      ..strokeCap = StrokeCap.round
      ..strokeJoin = StrokeJoin.round
      ..strokeWidth = 2.2
      ..shader = const LinearGradient(
        colors: [Color(0xFFFF3FDB), SignInPage._violet, SignInPage._cyan, Color(0xFF84FFF7)],
      ).createShader(Offset.zero & size);
    canvas.drawPath(glowPath, line);

    final secondaryPath = Path()
      ..moveTo(-10, size.height * 0.90)
      ..cubicTo(
        size.width * 0.20,
        size.height * 0.76,
        size.width * 0.29,
        size.height * 0.96,
        size.width * 0.44,
        size.height * 0.72,
      )
      ..cubicTo(
        size.width * 0.61,
        size.height * 0.51,
        size.width * 0.71,
        size.height * 0.82,
        size.width + 10,
        size.height * 0.43,
      );
    canvas.drawPath(
      secondaryPath,
      Paint()
        ..style = PaintingStyle.stroke
        ..strokeWidth = 1.15
        ..color = const Color(0xFF8E68FF).withValues(alpha: 0.78),
    );

    final baseline = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = 0.9
      ..color = SignInPage._cyan.withValues(alpha: 0.20);
    canvas.drawLine(
      Offset(0, size.height * 0.94),
      Offset(size.width, size.height * 0.94),
      baseline,
    );
  }

  @override
  bool shouldRepaint(covariant CustomPainter oldDelegate) => false;
}

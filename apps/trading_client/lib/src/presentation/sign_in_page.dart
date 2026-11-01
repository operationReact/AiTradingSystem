import 'package:flutter/material.dart';

class SignInPage extends StatefulWidget {
  const SignInPage({
    this.biometricEnrolled = false,
    this.onSignUp,
    this.onFingerprintSignIn,
    this.onPasswordSignIn,
    super.key,
  });

  final bool biometricEnrolled;
  final VoidCallback? onSignUp;
  final VoidCallback? onFingerprintSignIn;
  final Future<void> Function(String identifier, String password)? onPasswordSignIn;

  @override
  State<SignInPage> createState() => _SignInPageState();
}

class _SignInPageState extends State<SignInPage> {
  static const _background = Color(0xFF030817);
  static const _cyan = Color(0xFF12E7F4);
  static const _violet = Color(0xFF6B46FF);
  static const _magenta = Color(0xFFE53CFF);
  static const _muted = Color(0xFF8CA5C8);

  final _formKey = GlobalKey<FormState>();
  final _identifierController = TextEditingController();
  final _passwordController = TextEditingController();
  bool _rememberMe = true;
  bool _obscurePassword = true;
  bool _submitting = false;

  @override
  void dispose() {
    _identifierController.dispose();
    _passwordController.dispose();
    super.dispose();
  }

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
                  padding: const EdgeInsets.fromLTRB(20, 18, 20, 24),
                  child: ConstrainedBox(
                    constraints: BoxConstraints(
                      minHeight: (constraints.maxHeight - 42).clamp(0.0, double.infinity).toDouble(),
                    ),
                    child: Center(
                      child: ConstrainedBox(
                        constraints: const BoxConstraints(maxWidth: 390),
                        child: Form(
                          key: _formKey,
                          child: Column(
                            mainAxisAlignment: MainAxisAlignment.center,
                            children: [
                              const _TradingBrand(),
                              const SizedBox(height: 24),
                              const Text(
                                'Trade Smarter\nwith AI',
                                textAlign: TextAlign.center,
                                style: TextStyle(
                                  color: Colors.white,
                                  fontSize: 29,
                                  height: 1.05,
                                  fontWeight: FontWeight.w900,
                                  letterSpacing: -0.7,
                                ),
                              ),
                              const SizedBox(height: 10),
                              const Text(
                                'AI research  •  Paper trading  •\nRisk controls  •  Real trading when ready',
                                textAlign: TextAlign.center,
                                style: TextStyle(
                                  color: _muted,
                                  fontSize: 11.5,
                                  height: 1.45,
                                  fontWeight: FontWeight.w600,
                                ),
                              ),
                              const SizedBox(height: 12),
                              const SizedBox(
                                height: 126,
                                width: double.infinity,
                                child: CustomPaint(painter: _MarketWavePainter()),
                              ),
                              const SizedBox(height: 14),
                              _AuthTextField(
                                key: const ValueKey('auth-sign-in-identifier'),
                                controller: _identifierController,
                                hintText: 'Email or Phone Number',
                                keyboardType: TextInputType.emailAddress,
                                prefixIcon: Icons.mail_outline_rounded,
                                validator: (value) {
                                  if (value == null || value.trim().isEmpty) {
                                    return 'Enter your email or phone number';
                                  }
                                  return null;
                                },
                              ),
                              const SizedBox(height: 11),
                              _AuthTextField(
                                key: const ValueKey('auth-sign-in-password'),
                                controller: _passwordController,
                                hintText: 'Password',
                                prefixIcon: Icons.lock_outline_rounded,
                                obscureText: _obscurePassword,
                                suffixIcon: IconButton(
                                  tooltip: _obscurePassword ? 'Show password' : 'Hide password',
                                  onPressed: () => setState(() => _obscurePassword = !_obscurePassword),
                                  icon: Icon(
                                    _obscurePassword ? Icons.visibility_off_outlined : Icons.visibility_outlined,
                                    color: _muted,
                                    size: 19,
                                  ),
                                ),
                                validator: (value) {
                                  if (value == null || value.isEmpty) return 'Enter your password';
                                  return null;
                                },
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
                                      activeColor: _cyan,
                                      checkColor: const Color(0xFF06111F),
                                      side: const BorderSide(color: Color(0xFF4A6284)),
                                      visualDensity: VisualDensity.compact,
                                    ),
                                  ),
                                  const SizedBox(width: 5),
                                  const Text(
                                    'Remember me',
                                    style: TextStyle(color: Colors.white, fontSize: 11, fontWeight: FontWeight.w600),
                                  ),
                                  const Spacer(),
                                  TextButton(
                                    key: const ValueKey('auth-forgot-password'),
                                    onPressed: () => _showNotice(context, 'Password recovery will be connected to the customer auth API.'),
                                    style: TextButton.styleFrom(
                                      foregroundColor: _cyan,
                                      padding: EdgeInsets.zero,
                                      minimumSize: Size.zero,
                                      tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                                    ),
                                    child: const Text('Forgot password?', style: TextStyle(fontSize: 11, fontWeight: FontWeight.w700)),
                                  ),
                                ],
                              ),
                              const SizedBox(height: 13),
                              _GradientButton(
                                key: const ValueKey('auth-sign-in-submit'),
                                label: _submitting ? 'Signing in…' : 'Sign In',
                                icon: Icons.arrow_forward_rounded,
                                enabled: !_submitting,
                                onPressed: _submitPassword,
                              ),
                              if (widget.biometricEnrolled) ...[
                                const SizedBox(height: 11),
                                _FingerprintButton(
                                  key: const ValueKey('auth-fingerprint-sign-in'),
                                  onPressed: widget.onFingerprintSignIn,
                                ),
                              ],
                              const SizedBox(height: 20),
                              Row(
                                mainAxisAlignment: MainAxisAlignment.center,
                                children: [
                                  const Text(
                                    "Don't have an account? ",
                                    style: TextStyle(color: _muted, fontSize: 11.5, fontWeight: FontWeight.w600),
                                  ),
                                  TextButton(
                                    key: const ValueKey('auth-open-sign-up'),
                                    onPressed: widget.onSignUp ?? () => _showNotice(context, 'Sign Up screen is being connected next.'),
                                    style: TextButton.styleFrom(
                                      foregroundColor: _cyan,
                                      padding: EdgeInsets.zero,
                                      minimumSize: Size.zero,
                                      tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                                    ),
                                    child: const Text('Sign Up', style: TextStyle(fontSize: 11.5, fontWeight: FontWeight.w900)),
                                  ),
                                ],
                              ),
                            ],
                          ),
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

  Future<void> _submitPassword() async {
    if (!(_formKey.currentState?.validate() ?? false)) return;
    if (widget.onPasswordSignIn == null) {
      _showNotice(context, 'Sign In UI is ready. Production customer authentication will be connected after UI approval.');
      return;
    }
    setState(() => _submitting = true);
    try {
      await widget.onPasswordSignIn!(
        _identifierController.text.trim(),
        _passwordController.text,
      );
    } finally {
      if (mounted) setState(() => _submitting = false);
    }
  }

  void _showNotice(BuildContext context, String message) {
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(behavior: SnackBarBehavior.floating, content: Text(message)));
  }
}

class _TradingBrand extends StatelessWidget {
  const _TradingBrand();

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        Container(
          width: 50,
          height: 50,
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(14),
            gradient: const LinearGradient(
              begin: Alignment.bottomLeft,
              end: Alignment.topRight,
              colors: [_SignInPageState._cyan, _SignInPageState._violet, _SignInPageState._magenta],
            ),
            boxShadow: const [BoxShadow(color: Color(0x5536D9FF), blurRadius: 22)],
          ),
          child: const Icon(Icons.query_stats_rounded, color: Colors.white, size: 28),
        ),
        const SizedBox(height: 9),
        const Text(
          'AiTradingSystem',
          style: TextStyle(color: Colors.white, fontSize: 17, fontWeight: FontWeight.w900, letterSpacing: -0.3),
        ),
      ],
    );
  }
}

class _AuthTextField extends StatelessWidget {
  const _AuthTextField({
    super.key,
    required this.controller,
    required this.hintText,
    required this.prefixIcon,
    this.keyboardType,
    this.obscureText = false,
    this.suffixIcon,
    this.validator,
  });

  final TextEditingController controller;
  final String hintText;
  final IconData prefixIcon;
  final TextInputType? keyboardType;
  final bool obscureText;
  final Widget? suffixIcon;
  final String? Function(String?)? validator;

  @override
  Widget build(BuildContext context) {
    return TextFormField(
      controller: controller,
      keyboardType: keyboardType,
      obscureText: obscureText,
      validator: validator,
      style: const TextStyle(color: Colors.white, fontSize: 13.5, fontWeight: FontWeight.w600),
      decoration: InputDecoration(
        hintText: hintText,
        hintStyle: const TextStyle(color: Color(0xFF91A7C7), fontSize: 13.5),
        prefixIcon: Icon(prefixIcon, color: const Color(0xFFD5E3F7), size: 20),
        suffixIcon: suffixIcon,
        filled: true,
        fillColor: const Color(0xFF09162A),
        contentPadding: const EdgeInsets.symmetric(horizontal: 15, vertical: 15),
        border: OutlineInputBorder(borderRadius: BorderRadius.circular(12), borderSide: const BorderSide(color: Color(0xFF28405E))),
        enabledBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(12), borderSide: const BorderSide(color: Color(0xFF28405E))),
        focusedBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(12), borderSide: const BorderSide(color: _SignInPageState._cyan, width: 1.3)),
        errorBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(12), borderSide: const BorderSide(color: Color(0xFFFF6B8A))),
      ),
    );
  }
}

class _GradientButton extends StatelessWidget {
  const _GradientButton({super.key, required this.label, required this.onPressed, this.icon, this.enabled = true});
  final String label;
  final VoidCallback? onPressed;
  final IconData? icon;
  final bool enabled;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      height: 48,
      width: double.infinity,
      child: DecoratedBox(
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(12),
          gradient: LinearGradient(
            colors: enabled
                ? const [_SignInPageState._cyan, Color(0xFF31D6FF), _SignInPageState._violet, _SignInPageState._magenta]
                : const [Color(0xFF30445A), Color(0xFF263548)],
          ),
          boxShadow: enabled ? const [BoxShadow(color: Color(0x403BD9FF), blurRadius: 20, offset: Offset(0, 7))] : null,
        ),
        child: Material(
          color: Colors.transparent,
          child: InkWell(
            onTap: enabled ? onPressed : null,
            borderRadius: BorderRadius.circular(12),
            child: Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Text(label, style: const TextStyle(color: Color(0xFF04111D), fontSize: 14, fontWeight: FontWeight.w900)),
                if (icon != null) ...[const SizedBox(width: 8), Icon(icon, color: const Color(0xFF04111D), size: 18)],
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _FingerprintButton extends StatelessWidget {
  const _FingerprintButton({super.key, this.onPressed});
  final VoidCallback? onPressed;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: double.infinity,
      height: 48,
      child: OutlinedButton.icon(
        onPressed: onPressed,
        icon: const Icon(Icons.fingerprint_rounded, color: _SignInPageState._cyan, size: 25),
        label: const Text('Sign in with Fingerprint'),
        style: OutlinedButton.styleFrom(
          foregroundColor: Colors.white,
          side: const BorderSide(color: _SignInPageState._cyan, width: 1.1),
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
          textStyle: const TextStyle(fontSize: 13, fontWeight: FontWeight.w800),
        ),
      ),
    );
  }
}

class _AuthBackdrop extends StatelessWidget {
  const _AuthBackdrop();

  @override
  Widget build(BuildContext context) {
    return const DecoratedBox(
      decoration: BoxDecoration(
        gradient: RadialGradient(
          center: Alignment(0.72, -0.18),
          radius: 1.05,
          colors: [Color(0x28133C65), Color(0x12081226), _SignInPageState._background],
          stops: [0, 0.42, 1],
        ),
      ),
      child: SizedBox.expand(),
    );
  }
}

class _MarketWavePainter extends CustomPainter {
  const _MarketWavePainter();

  @override
  void paint(Canvas canvas, Size size) {
    final grid = Paint()
      ..color = const Color(0xFF1B2E4B).withValues(alpha: 0.26)
      ..strokeWidth = 0.6;
    for (var i = 1; i < 6; i++) {
      final y = size.height * i / 6;
      canvas.drawLine(Offset(0, y), Offset(size.width, y), grid);
    }

    final path = Path()
      ..moveTo(-8, size.height * 0.83)
      ..cubicTo(size.width * .12, size.height * .70, size.width * .24, size.height * .87, size.width * .34, size.height * .60)
      ..cubicTo(size.width * .45, size.height * .36, size.width * .53, size.height * .71, size.width * .62, size.height * .45)
      ..cubicTo(size.width * .72, size.height * .19, size.width * .80, size.height * .40, size.width * .90, size.height * .15)
      ..cubicTo(size.width * .95, size.height * .04, size.width, size.height * .16, size.width + 8, size.height * .10);

    final glow = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = 11
      ..strokeCap = StrokeCap.round
      ..maskFilter = const MaskFilter.blur(BlurStyle.normal, 14)
      ..shader = const LinearGradient(colors: [_SignInPageState._magenta, _SignInPageState._violet, _SignInPageState._cyan]).createShader(Offset.zero & size);
    canvas.drawPath(path, glow);

    final line = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = 2
      ..strokeCap = StrokeCap.round
      ..shader = const LinearGradient(colors: [_SignInPageState._magenta, _SignInPageState._violet, _SignInPageState._cyan, Color(0xFF73FFF4)]).createShader(Offset.zero & size);
    canvas.drawPath(path, line);

    final candlePaint = Paint()..strokeWidth = 2;
    final candleXs = <double>[.11, .17, .24, .31, .43, .49, .58, .67, .74, .83, .91];
    final candleHeights = <double>[20, 32, 24, 42, 34, 26, 48, 39, 55, 44, 61];
    for (var i = 0; i < candleXs.length; i++) {
      final x = size.width * candleXs[i];
      final base = size.height * (0.88 - candleXs[i] * .45);
      final height = candleHeights[i];
      final color = i.isEven ? _SignInPageState._cyan : _SignInPageState._violet;
      candlePaint.color = color.withValues(alpha: .82);
      canvas.drawLine(Offset(x, base + 5), Offset(x, base - height - 6), candlePaint);
      canvas.drawRRect(
        RRect.fromRectAndRadius(Rect.fromLTWH(x - 3, base - height, 6, height), const Radius.circular(2)),
        Paint()..color = color.withValues(alpha: .88),
      );
    }
  }

  @override
  bool shouldRepaint(covariant CustomPainter oldDelegate) => false;
}

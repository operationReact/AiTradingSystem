import 'package:flutter/material.dart';

abstract final class AuthPalette {
  static const background = Color(0xFF030817);
  static const surface = Color(0xFF09162A);
  static const cyan = Color(0xFF12E7F4);
  static const violet = Color(0xFF6B46FF);
  static const magenta = Color(0xFFE53CFF);
  static const muted = Color(0xFF8CA5C8);
  static const border = Color(0xFF28405E);
}

class AuthShell extends StatelessWidget {
  const AuthShell({required this.child, this.padding = const EdgeInsets.fromLTRB(20, 18, 20, 24), super.key});
  final Widget child;
  final EdgeInsets padding;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AuthPalette.background,
      body: Stack(
        children: [
          const Positioned.fill(child: AuthBackdrop()),
          SafeArea(
            child: LayoutBuilder(
              builder: (context, constraints) => SingleChildScrollView(
                padding: padding,
                child: ConstrainedBox(
                  constraints: BoxConstraints(
                    minHeight: (constraints.maxHeight - padding.vertical).clamp(0.0, double.infinity).toDouble(),
                  ),
                  child: Center(
                    child: ConstrainedBox(
                      constraints: const BoxConstraints(maxWidth: 390),
                      child: child,
                    ),
                  ),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class AuthBackButton extends StatelessWidget {
  const AuthBackButton({required this.onPressed, super.key});
  final VoidCallback onPressed;

  @override
  Widget build(BuildContext context) {
    return Align(
      alignment: Alignment.centerLeft,
      child: IconButton(
        tooltip: 'Back',
        onPressed: onPressed,
        padding: EdgeInsets.zero,
        constraints: const BoxConstraints.tightFor(width: 40, height: 40),
        icon: const Icon(Icons.arrow_back_rounded, color: Colors.white, size: 23),
      ),
    );
  }
}

class TradingBrand extends StatelessWidget {
  const TradingBrand({this.compact = false, super.key});
  final bool compact;

  @override
  Widget build(BuildContext context) {
    final size = compact ? 44.0 : 50.0;
    return Column(
      children: [
        Container(
          width: size,
          height: size,
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(compact ? 12 : 14),
            gradient: const LinearGradient(
              begin: Alignment.bottomLeft,
              end: Alignment.topRight,
              colors: [AuthPalette.cyan, AuthPalette.violet, AuthPalette.magenta],
            ),
            boxShadow: const [BoxShadow(color: Color(0x5536D9FF), blurRadius: 22)],
          ),
          child: Icon(Icons.query_stats_rounded, color: Colors.white, size: compact ? 25 : 28),
        ),
        SizedBox(height: compact ? 7 : 9),
        Text(
          'AiTradingSystem',
          style: TextStyle(
            color: Colors.white,
            fontSize: compact ? 16 : 17,
            fontWeight: FontWeight.w900,
            letterSpacing: -0.3,
          ),
        ),
      ],
    );
  }
}

class AuthTextField extends StatelessWidget {
  const AuthTextField({
    required this.controller,
    required this.hintText,
    required this.prefixIcon,
    this.keyboardType,
    this.obscureText = false,
    this.suffixIcon,
    this.validator,
    this.textInputAction,
    super.key,
  });

  final TextEditingController controller;
  final String hintText;
  final IconData prefixIcon;
  final TextInputType? keyboardType;
  final bool obscureText;
  final Widget? suffixIcon;
  final String? Function(String?)? validator;
  final TextInputAction? textInputAction;

  @override
  Widget build(BuildContext context) {
    return TextFormField(
      controller: controller,
      keyboardType: keyboardType,
      obscureText: obscureText,
      validator: validator,
      textInputAction: textInputAction,
      style: const TextStyle(color: Colors.white, fontSize: 13.5, fontWeight: FontWeight.w600),
      decoration: InputDecoration(
        hintText: hintText,
        hintStyle: const TextStyle(color: Color(0xFF91A7C7), fontSize: 13.5),
        prefixIcon: Icon(prefixIcon, color: const Color(0xFFD5E3F7), size: 20),
        suffixIcon: suffixIcon,
        filled: true,
        fillColor: AuthPalette.surface,
        contentPadding: const EdgeInsets.symmetric(horizontal: 15, vertical: 15),
        border: OutlineInputBorder(borderRadius: BorderRadius.circular(12), borderSide: const BorderSide(color: AuthPalette.border)),
        enabledBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(12), borderSide: const BorderSide(color: AuthPalette.border)),
        focusedBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(12), borderSide: const BorderSide(color: AuthPalette.cyan, width: 1.3)),
        errorBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(12), borderSide: const BorderSide(color: Color(0xFFFF6B8A))),
        focusedErrorBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(12), borderSide: const BorderSide(color: Color(0xFFFF6B8A), width: 1.3)),
      ),
    );
  }
}

class GradientAuthButton extends StatelessWidget {
  const GradientAuthButton({required this.label, required this.onPressed, this.icon, this.enabled = true, super.key});
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
                ? const [AuthPalette.cyan, Color(0xFF31D6FF), AuthPalette.violet, AuthPalette.magenta]
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

class FingerprintOutlineButton extends StatelessWidget {
  const FingerprintOutlineButton({required this.onPressed, this.label = 'Sign in with Fingerprint', super.key});
  final VoidCallback? onPressed;
  final String label;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: double.infinity,
      height: 48,
      child: OutlinedButton.icon(
        onPressed: onPressed,
        icon: const Icon(Icons.fingerprint_rounded, color: AuthPalette.cyan, size: 25),
        label: Text(label),
        style: OutlinedButton.styleFrom(
          foregroundColor: Colors.white,
          side: const BorderSide(color: AuthPalette.cyan, width: 1.1),
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
          textStyle: const TextStyle(fontSize: 13, fontWeight: FontWeight.w800),
        ),
      ),
    );
  }
}

class AuthBackdrop extends StatelessWidget {
  const AuthBackdrop({super.key});

  @override
  Widget build(BuildContext context) {
    return const DecoratedBox(
      decoration: BoxDecoration(
        gradient: RadialGradient(
          center: Alignment(0.72, -0.18),
          radius: 1.05,
          colors: [Color(0x28133C65), Color(0x12081226), AuthPalette.background],
          stops: [0, 0.42, 1],
        ),
      ),
      child: SizedBox.expand(),
    );
  }
}

class MarketWave extends StatelessWidget {
  const MarketWave({this.height = 126, super.key});
  final double height;

  @override
  Widget build(BuildContext context) {
    return SizedBox(height: height, width: double.infinity, child: const CustomPaint(painter: _MarketWavePainter()));
  }
}

class AuthInfoRow extends StatelessWidget {
  const AuthInfoRow({required this.icon, required this.title, super.key});
  final IconData icon;
  final String title;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 13, vertical: 12),
      decoration: BoxDecoration(
        color: AuthPalette.surface,
        border: Border.all(color: AuthPalette.border.withValues(alpha: .7)),
        borderRadius: BorderRadius.circular(12),
      ),
      child: Row(
        children: [
          Icon(icon, color: AuthPalette.cyan, size: 20),
          const SizedBox(width: 11),
          Expanded(child: Text(title, style: const TextStyle(color: Colors.white, fontSize: 12.5, fontWeight: FontWeight.w700))),
        ],
      ),
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
      ..shader = const LinearGradient(colors: [AuthPalette.magenta, AuthPalette.violet, AuthPalette.cyan]).createShader(Offset.zero & size);
    canvas.drawPath(path, glow);

    final line = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = 2
      ..strokeCap = StrokeCap.round
      ..shader = const LinearGradient(colors: [AuthPalette.magenta, AuthPalette.violet, AuthPalette.cyan, Color(0xFF73FFF4)]).createShader(Offset.zero & size);
    canvas.drawPath(path, line);

    final candlePaint = Paint()..strokeWidth = 2;
    final candleXs = <double>[.11, .17, .24, .31, .43, .49, .58, .67, .74, .83, .91];
    final candleHeights = <double>[20, 32, 24, 42, 34, 26, 48, 39, 55, 44, 61];
    for (var i = 0; i < candleXs.length; i++) {
      final x = size.width * candleXs[i];
      final base = size.height * (0.88 - candleXs[i] * .45);
      final height = candleHeights[i];
      final color = i.isEven ? AuthPalette.cyan : AuthPalette.violet;
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

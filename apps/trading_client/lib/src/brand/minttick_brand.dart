import 'package:flutter/material.dart';

/// Canonical product identity for customer-facing surfaces.
abstract final class MintTickBrand {
  static const appName = 'MintTick';
  static const productName = 'MintTick — AI Trading Platform';
}

/// Display-only identity. Trading authorization remains server-side.
abstract final class MintTickPalette {
  static const mint = Color(0xFF00E676);
  static const teal = Color(0xFF00BFA5);
  static const navy = Color(0xFF0B0F1A);
  // Neutral near-black page canvas, intentionally not pure black or navy.
  static const nearBlack = Color(0xFF0B0D10);
  static const ice = Color(0xFFF8FAFC);
  static const readableGreen = Color(0xFF007A52);
}

/// Resolution-independent mark, based on the approved rising-M identity.
class MintTickMark extends StatelessWidget {
  const MintTickMark({this.width = 48, this.monochrome = false, super.key});
  final double width;
  final bool monochrome;

  @override
  Widget build(BuildContext context) {
    return Semantics(
      label: 'MintTick logo',
      image: true,
      child: SizedBox(
        width: width,
        height: width * 170 / 280,
        child: CustomPaint(
          key: const ValueKey('minttick-mark'),
          painter: _MintTickPainter(
            monochrome ? Theme.of(context).colorScheme.onSurface : null,
          ),
        ),
      ),
    );
  }
}

class MintTickLockup extends StatelessWidget {
  const MintTickLockup({
    this.markWidth = 44,
    this.fontSize = 21,
    this.markScale = 1,
    this.markOpacity = 1,
    this.wordmarkOpacity = 1,
    this.wordmarkOffset = 0,
    super.key,
  });
  final double markWidth;
  final double fontSize;
  // Optional visual-only entrance controls. Other product lockups remain static.
  final double markScale;
  final double markOpacity;
  final double wordmarkOpacity;
  final double wordmarkOffset;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final mint = scheme.brightness == Brightness.dark
        ? MintTickPalette.mint
        : MintTickPalette.readableGreen;
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Opacity(
          opacity: markOpacity,
          child: Transform.scale(
            scale: markScale,
            child: MintTickMark(width: markWidth),
          ),
        ),
        const SizedBox(width: 9),
        Flexible(
          child: Opacity(
            opacity: wordmarkOpacity,
            child: Transform.translate(
              offset: Offset(wordmarkOffset, 0),
              child: Text.rich(
                TextSpan(children: [
                  TextSpan(text: 'Mint', style: TextStyle(color: scheme.onSurface)),
                  TextSpan(text: 'Tick', style: TextStyle(color: mint)),
                ]),
                key: const ValueKey('minttick-wordmark'),
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(
                  fontWeight: FontWeight.w800,
                  fontSize: fontSize,
                  letterSpacing: -0.6,
                  height: 1.05,
                ),
              ),
            ),
          ),
        ),
      ],
    );
  }
}

class _MintTickPainter extends CustomPainter {
  const _MintTickPainter(this.solidColor);
  final Color? solidColor;

  @override
  void paint(Canvas canvas, Size size) {
    canvas.save();
    canvas.scale(size.width / 280, size.height / 170);
    final paint = Paint()..isAntiAlias = true;
    if (solidColor == null) {
      paint.shader = const LinearGradient(
        begin: Alignment.topLeft,
        end: Alignment.bottomRight,
        colors: [MintTickPalette.teal, MintTickPalette.mint],
      ).createShader(const Rect.fromLTWH(0, 0, 280, 170));
    } else {
      paint.color = solidColor!;
    }

    final mark = Path()
      ..moveTo(6, 151)
      ..lineTo(64, 30)
      ..quadraticBezierTo(72, 13, 91, 13)
      ..lineTo(110, 13)
      ..quadraticBezierTo(125, 13, 136, 26)
      ..lineTo(174, 74)
      ..lineTo(273, 6)
      ..lineTo(229, 84)
      ..lineTo(178, 128)
      ..quadraticBezierTo(167, 138, 157, 126)
      ..lineTo(100, 57)
      ..lineTo(63, 144)
      ..quadraticBezierTo(59, 153, 48, 153)
      ..close();
    canvas.drawPath(mark, paint);
    for (final rect in [
      const Rect.fromLTWH(174, 130, 19, 24),
      const Rect.fromLTWH(202, 110, 19, 44),
      const Rect.fromLTWH(230, 90, 19, 64),
    ]) {
      canvas.drawRRect(
        RRect.fromRectAndRadius(rect, const Radius.circular(2.5)),
        paint,
      );
    }
    canvas.restore();
  }

  @override
  bool shouldRepaint(covariant _MintTickPainter oldDelegate) =>
      oldDelegate.solidColor != solidColor;
}

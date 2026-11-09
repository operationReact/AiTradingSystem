import 'package:flutter/material.dart';
import '../../../brand/minttick_brand.dart';

/// Visual tokens matched to the approved MintTick Mind HTML reference.
/// Restricted to the mock-only Mind screens; existing app themes stay intact.
abstract final class MindPalette {
  static const background = MintTickPalette.nearBlack;
  static const panel = Color(0xFF111512);
  static const card = MintTickPalette.darkCard;
  static const border = MintTickPalette.darkBorder;
  static const mint = MintTickPalette.mint;
  static const green = MintTickPalette.mint;
  static const text = Color(0xFFF5F6F4);
  static const muted = MintTickPalette.mutedSilver;
  // Semantic research states stay distinct from the brand accent.
  static const yellow = Color(0xFFE6BD73);
  static const red = Color(0xFFF48C93);
  static const purple = Color(0xFFC0C5C0);
  static const blue = Color(0xFFA5ABA6);
}

class MindPanel extends StatelessWidget {
  const MindPanel({required this.child, super.key, this.padding = const EdgeInsets.all(14), this.highlight = false, this.onTap});
  final Widget child;
  final EdgeInsets padding;
  final bool highlight;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final surface = Container(
      padding: padding,
      decoration: BoxDecoration(
        color: highlight ? const Color(0xFF18231A) : MindPalette.card,
        border: Border.all(color: highlight ? const Color(0xFF36533C) : MindPalette.border),
        borderRadius: BorderRadius.circular(18),
      ),
      child: child,
    );
    if (onTap == null) return surface;
    return Material(
      color: Colors.transparent,
      child: InkWell(
        borderRadius: BorderRadius.circular(18),
        onTap: onTap,
        child: surface,
      ),
    );
  }
}

class MindTag extends StatelessWidget {
  const MindTag(this.label, {super.key, this.color = MindPalette.mint});
  final String label;
  final Color color;
  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 5),
    decoration: BoxDecoration(
      color: color.withValues(alpha: 0.10),
      border: Border.all(color: color.withValues(alpha: 0.45)),
      borderRadius: BorderRadius.circular(100),
    ),
    child: Text(label, style: TextStyle(color: color, fontSize: 11, fontWeight: FontWeight.w700)),
  );
}

class MindSectionTitle extends StatelessWidget {
  const MindSectionTitle({required this.title, required this.icon, super.key, this.onSeeAll});
  final String title;
  final IconData icon;
  final VoidCallback? onSeeAll;
  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(bottom: 10, top: 3),
    child: Row(
      children: [
        Icon(icon, size: 19, color: MindPalette.mint),
        const SizedBox(width: 9),
        Expanded(child: Text(title, style: const TextStyle(color: MindPalette.text, fontSize: 18, fontWeight: FontWeight.w800, letterSpacing: -.3))),
        if (onSeeAll != null)
          TextButton(
            onPressed: onSeeAll,
            style: TextButton.styleFrom(foregroundColor: MindPalette.mint, minimumSize: const Size(40, 35), padding: const EdgeInsets.symmetric(horizontal: 2)),
            child: const Row(mainAxisSize: MainAxisSize.min, children: [Text('View all', style: TextStyle(fontSize: 12)), SizedBox(width: 3), Icon(Icons.arrow_forward, size: 15)]),
          ),
      ],
    ),
  );
}

/// Decorative sparklines are deliberately deterministic; never represent a quote feed.
class MindSparkline extends StatelessWidget {
  const MindSparkline({super.key, this.seed = 0, this.color = MindPalette.mint, this.width = 72, this.height = 29});
  final int seed;
  final Color color;
  final double width;
  final double height;
  @override
  Widget build(BuildContext context) => SizedBox(width: width, height: height, child: CustomPaint(painter: _MindSparkPainter(seed, color)));
}

class _MindSparkPainter extends CustomPainter {
  _MindSparkPainter(this.seed, this.color);
  final int seed;
  final Color color;
  @override
  void paint(Canvas canvas, Size size) {
    final points = <Offset>[];
    double height = 0.62;
    for (var i=0; i<17; i++) {
      final variation = (((i * 11 + seed * 7) % 9)-3) / 25;
      height = (height - .035 + variation).clamp(.10, .88).toDouble();
      points.add(Offset(size.width * i / 16, size.height * height));
    }
    final path = Path()..moveTo(points.first.dx, points.first.dy);
    for (final point in points.skip(1)) { path.lineTo(point.dx, point.dy); }
    final shade = Path.from(path)
      ..lineTo(size.width, size.height)
      ..lineTo(0, size.height)
      ..close();
    canvas.drawPath(shade, Paint()..color=color.withValues(alpha:.06));
    canvas.drawPath(path, Paint()..color=color..strokeWidth=1.7..style=PaintingStyle.stroke..strokeJoin=StrokeJoin.round..strokeCap=StrokeCap.round);
  }
  @override
  bool shouldRepaint(covariant _MindSparkPainter old) => old.seed!=seed || old.color!=color;
}

class MindMetric extends StatelessWidget {
  const MindMetric({required this.label, required this.value, required this.caption, super.key, this.color=MindPalette.mint, this.seed=1});
  final String label;
  final String value;
  final String caption;
  final Color color;
  final int seed;
  @override
  Widget build(BuildContext context) => MindPanel(
    child: SizedBox(
      height: 104,
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text(label,maxLines:1,overflow:TextOverflow.ellipsis,style:const TextStyle(color:MindPalette.text,fontSize:12)),
        const SizedBox(height: 8),
        Text(value, maxLines: 1, overflow: TextOverflow.ellipsis, style: TextStyle(color: color, fontWeight: FontWeight.w800, fontSize: 22)),
        const Spacer(),
        Row(children: [
          Expanded(child: Text(caption,maxLines:2,overflow:TextOverflow.ellipsis,style: const TextStyle(color: MindPalette.muted,fontSize:10.5))),
          MindSparkline(seed:seed,color:color,width:46,height:23),
        ]),
      ]),
    ),
  );
}

class MindEmptyState extends StatelessWidget {
  const MindEmptyState(this.message, {super.key});
  final String message;
  @override
  Widget build(BuildContext context)=> MindPanel(child:Padding(padding: const EdgeInsets.all(24),child:Center(child:Text(message,style:const TextStyle(color:MindPalette.muted),textAlign:TextAlign.center))));
}

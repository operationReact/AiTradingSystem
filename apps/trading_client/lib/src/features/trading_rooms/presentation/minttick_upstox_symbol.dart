import 'package:flutter/material.dart';

/// A sharp, resolution-independent Upstox identifier for compact broker rows.
///
/// This is a locally rendered brand-colour lettermark rather than a remote
/// image or an assertion that MintTick is endorsed by the broker. Keep this
/// small tile flat, legible and free of gradients or network dependencies.
class UpstoxSymbol extends StatelessWidget {
  const UpstoxSymbol({super.key, this.size = 44});

  final double size;

  @override
  Widget build(BuildContext context) {
    return Semantics(
      label: 'Upstox',
      image: true,
      child: ExcludeSemantics(
        child: Container(
          width: size,
          height: size,
          alignment: Alignment.center,
          decoration: BoxDecoration(
            color: const Color(0xFF542087),
            borderRadius: BorderRadius.circular(size * 0.25),
          ),
          child: Text(
            'up',
            maxLines: 1,
            textAlign: TextAlign.center,
            style: TextStyle(
              color: Colors.white,
              fontSize: size * 0.47,
              fontWeight: FontWeight.w900,
              height: 1,
              letterSpacing: -size * 0.046,
            ),
          ),
        ),
      ),
    );
  }
}

import 'package:flutter/material.dart';

import 'auth_components.dart';

class AuthSuccessPage extends StatefulWidget {
  const AuthSuccessPage({required this.onContinue, super.key});
  final VoidCallback onContinue;

  @override
  State<AuthSuccessPage> createState() => _AuthSuccessPageState();
}

class _AuthSuccessPageState extends State<AuthSuccessPage> with SingleTickerProviderStateMixin {
  late final AnimationController _controller;
  late final Animation<double> _progress;

  @override
  void initState() {
    super.initState();
    _controller = AnimationController(vsync: this, duration: const Duration(milliseconds: 1350));
    _progress = CurvedAnimation(parent: _controller, curve: Curves.easeOutCubic);
    _controller.forward().whenComplete(() {
      if (mounted) widget.onContinue();
    });
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AuthShell(
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Container(
            width: 104,
            height: 104,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              border: Border.all(color: const Color(0xFF30F1B1), width: 2.4),
              gradient: const RadialGradient(colors: [Color(0x3330F1B1), Color(0x0710A877)]),
              boxShadow: const [BoxShadow(color: Color(0x5530F1B1), blurRadius: 34, spreadRadius: 3)],
            ),
            child: const Icon(Icons.check_rounded, color: Color(0xFF30F1B1), size: 58),
          ),
          const SizedBox(height: 34),
          const Text(
            "You're signed in!",
            textAlign: TextAlign.center,
            style: TextStyle(color: Colors.white, fontSize: 24, fontWeight: FontWeight.w900, letterSpacing: -0.5),
          ),
          const SizedBox(height: 13),
          const Text(
            'Setting up your trading workspace…',
            style: TextStyle(color: AuthPalette.muted, fontSize: 12.5, fontWeight: FontWeight.w600),
          ),
          const SizedBox(height: 25),
          SizedBox(
            width: 230,
            child: AnimatedBuilder(
              animation: _progress,
              builder: (context, _) => Stack(
                children: [
                  Container(
                    height: 5,
                    decoration: BoxDecoration(color: const Color(0xFF103A58), borderRadius: BorderRadius.circular(99)),
                  ),
                  FractionallySizedBox(
                    widthFactor: _progress.value,
                    child: Container(
                      height: 5,
                      decoration: BoxDecoration(
                        borderRadius: BorderRadius.circular(99),
                        gradient: const LinearGradient(colors: [Color(0xFF21E6B2), AuthPalette.cyan]),
                        boxShadow: const [BoxShadow(color: Color(0x6621E6B2), blurRadius: 10)],
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),
          const SizedBox(height: 110),
          const MarketWave(height: 120),
        ],
      ),
    );
  }
}

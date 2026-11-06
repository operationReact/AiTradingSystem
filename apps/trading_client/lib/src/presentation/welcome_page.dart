import 'dart:async';

import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../brand/minttick_brand.dart';
import '../brand/minttick_sonic_logo.dart';
import '../theme/app_theme.dart';

/// Guest-first brand experience. Never blocks access on animation/audio and
/// never requests broker credentials or public market data.
class MintTickWelcomePage extends StatefulWidget {
  const MintTickWelcomePage({super.key, this.sound});
  final BrandSonicLogo? sound;

  @override
  State<MintTickWelcomePage> createState() => _MintTickWelcomePageState();
}

class _MintTickWelcomePageState extends State<MintTickWelcomePage>
    with SingleTickerProviderStateMixin {
  static const seenKey = 'minttick.welcome.seen.v1';
  static const soundKey = 'minttick.brand.sound_enabled.v1';

  late final AnimationController _reveal = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 1450),
  );
  BrandSonicLogo? _ownedSound;
  bool _soundEnabled = false;
  bool _interacted = false;

  BrandSonicLogo get _sound => widget.sound ?? (_ownedSound ??= BrandSonicLogo());

  @override
  void initState() {
    super.initState();
    _reveal.forward();
    unawaited(_restoreSoundPreference());
  }

  Future<void> _restoreSoundPreference() async {
    try {
      final enabled = await SharedPreferencesAsync().getBool(soundKey) ?? false;
      if (mounted && !_interacted) setState(() => _soundEnabled = enabled);
    } catch (_) {
      // Launch remains fully usable when preferences are unavailable.
    }
  }

  Future<void> _toggleSound() async {
    _interacted = true;
    final enabled = !_soundEnabled;
    setState(() => _soundEnabled = enabled);
    unawaited(_saveSoundPreference(enabled));
    if (enabled) {
      await _playSound();
    } else {
      await _sound.stop();
    }
  }

  Future<void> _saveSoundPreference(bool enabled) async {
    try {
      await SharedPreferencesAsync().setBool(soundKey, enabled);
    } catch (_) {
      // Audio opt-in remains optional if local storage is unavailable.
    }
  }

  Future<void> _playSound() async {
    try {
      await _sound.play();
    } catch (_) {
      // This is an optional flourish; unsupported audio must not block login.
    }
  }

  void _replay() {
    _reveal.forward(from: 0);
    if (_soundEnabled) unawaited(_playSound());
  }

  Future<void> _continue() async {
    try {
      await SharedPreferencesAsync().setBool(seenKey, true);
    } catch (_) {
      // Failing to persist a cosmetic preference must not block navigation.
    }
    if (!mounted) return;
    final navigator = Navigator.of(context);
    if (navigator.canPop()) {
      navigator.pop();
    } else {
      navigator.pushNamedAndRemoveUntil('/sign-in', (_) => false);
    }
  }

  @override
  void dispose() {
    _reveal.dispose();
    unawaited(_ownedSound?.dispose() ?? Future<void>.value());
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final reduceMotion = MediaQuery.of(context).disableAnimations;
    final theme = Theme.of(context);
    final returningFromSettings = Navigator.of(context).canPop();
    return Scaffold(
      key: const ValueKey('minttick-welcome-screen'),
      backgroundColor: MintTickPalette.navy,
      body: SafeArea(
        child: LayoutBuilder(
          builder: (context, box) => SingleChildScrollView(
            padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 20),
            child: ConstrainedBox(
              constraints: BoxConstraints(minHeight: box.maxHeight - 40),
              child: Center(
                child: ConstrainedBox(
                  constraints: const BoxConstraints(maxWidth: 440),
                  child: Column(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      SizedBox(
                        height: 250,
                        child: Stack(
                          alignment: Alignment.center,
                          children: [
                            // A stationary, muted halo adds depth without
                            // recoloring or distorting the canonical SVG mark.
                            Container(
                              width: 280,
                              height: 230,
                              decoration: BoxDecoration(
                                gradient: RadialGradient(
                                  colors: [
                                    MintTickPalette.teal.withValues(alpha: 0.11),
                                    Colors.transparent,
                                  ],
                                ),
                              ),
                            ),
                            AnimatedBuilder(
                              animation: _reveal,
                              builder: (context, child) {
                                final progress = reduceMotion
                                    ? 1.0
                                    : Curves.easeOutCubic.transform(_reveal.value);
                                return Opacity(
                                  opacity: progress.clamp(0, 1),
                                  child: Transform.translate(
                                    offset: Offset(0, (1 - progress) * 14),
                                    child: Transform(
                                      alignment: Alignment.center,
                                      transform: Matrix4.identity()
                                        ..setEntry(3, 2, 0.0005)
                                        ..rotateY((1 - progress) * 0.08),
                                      child: Transform.scale(
                                        scale: 0.975 + 0.025 * progress,
                                        child: child,
                                      ),
                                    ),
                                  ),
                                );
                              },
                              // Always render the canonical brand's white/green
                              // wordmark against the fixed dark intro surface,
                              // even when the user's app theme is light.
                              child: Theme(
                                data: buildTheme(Brightness.dark),
                                child: const MintTickLockup(
                                  markWidth: 96,
                                  fontSize: 39,
                                ),
                              ),
                            ),
                          ],
                        ),
                      ),
                      const SizedBox(height: 8),
                      Text(
                        'MARKETS  ·  INTELLIGENCE  ·  CONTROL',
                        textAlign: TextAlign.center,
                        style: theme.textTheme.labelSmall?.copyWith(
                          color: MintTickPalette.ice.withValues(alpha: 0.65),
                          letterSpacing: 1.15,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                      const SizedBox(height: 16),
                      Text(
                        'Trade with clarity.',
                        textAlign: TextAlign.center,
                        style: theme.textTheme.headlineMedium?.copyWith(
                          color: MintTickPalette.ice,
                          fontWeight: FontWeight.w700,
                          letterSpacing: -0.6,
                        ),
                      ),
                      const SizedBox(height: 10),
                      Text(
                        'Thoughtful market tools and intelligent research, in one place.',
                        textAlign: TextAlign.center,
                        style: theme.textTheme.bodyMedium?.copyWith(
                          color: MintTickPalette.ice.withValues(alpha: 0.74),
                        ),
                      ),
                      const SizedBox(height: 36),
                      SizedBox(
                        width: double.infinity,
                        height: 52,
                        child: FilledButton(
                          key: const ValueKey('minttick-welcome-continue'),
                          onPressed: _continue,
                          child: Text(returningFromSettings
                              ? 'Done'
                              : 'Continue to sign in'),
                        ),
                      ),
                      const SizedBox(height: 10),
                      Wrap(
                        alignment: WrapAlignment.center,
                        spacing: 10,
                        children: [
                          TextButton.icon(
                            key: const ValueKey('minttick-welcome-sound-toggle'),
                            onPressed: _toggleSound,
                            icon: Icon(_soundEnabled
                                ? Icons.volume_up_outlined
                                : Icons.volume_off_outlined, size: 18),
                            label: Text(_soundEnabled ? 'Sound on' : 'Sound off'),
                            style: TextButton.styleFrom(
                              foregroundColor: MintTickPalette.ice,
                            ),
                          ),
                          TextButton.icon(
                            key: const ValueKey('minttick-welcome-replay'),
                            onPressed: _replay,
                            icon: const Icon(Icons.replay_rounded, size: 18),
                            label: const Text('Replay'),
                            style: TextButton.styleFrom(
                              foregroundColor: MintTickPalette.ice,
                            ),
                          ),
                        ],
                      ),
                      const SizedBox(height: 8),
                      Text(
                        'Sound is optional and respects your device settings.',
                        textAlign: TextAlign.center,
                        style: theme.textTheme.labelSmall?.copyWith(
                          color: MintTickPalette.ice.withValues(alpha: 0.52),
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

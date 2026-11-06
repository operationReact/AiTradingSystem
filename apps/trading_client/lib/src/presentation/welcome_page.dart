import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../brand/minttick_brand.dart';
import '../brand/minttick_sonic_logo.dart';
import '../theme/app_theme.dart';

/// First-launch brand screen using the exact approved MintTick vector lockup.
/// All navigation remains usable while motion, audio, or storage are unavailable.
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
  bool _soundPreferenceTouched = false;

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
      if (mounted && !_soundPreferenceTouched) {
        setState(() => _soundEnabled = enabled);
      }
    } catch (_) {
      // The opening screen must render even without a local preferences plugin.
    }
  }

  Future<void> _toggleSound() async {
    _soundPreferenceTouched = true;
    final enabled = !_soundEnabled;
    setState(() => _soundEnabled = enabled);
    unawaited(_saveSoundPreference(enabled));
    if (enabled) {
      await _playSound();
    } else {
      try {
        await _sound.stop();
      } catch (_) {
        // Audio is never a prerequisite for interacting with the app.
      }
    }
  }

  Future<void> _saveSoundPreference(bool enabled) async {
    try {
      await SharedPreferencesAsync().setBool(soundKey, enabled);
    } catch (_) {
      // The local preference is best effort, not an authentication state.
    }
  }

  Future<void> _playSound() async {
    try {
      await _sound.play();
    } catch (_) {
      // Devices without an audio engine still display the approved logo.
    }
  }

  void _replay() {
    _reveal.forward(from: 0);
    if (_soundEnabled) unawaited(_playSound());
  }

  Future<void> _enter(String route) async {
    try {
      await SharedPreferencesAsync().setBool(seenKey, true);
    } catch (_) {
      // Never block sign-in/create-account navigation on local storage.
    }
    if (!mounted) return;
    Navigator.of(context).pushNamedAndRemoveUntil(route, (_) => false);
  }

  @override
  void dispose() {
    _reveal.dispose();
    unawaited(_ownedSound?.dispose() ?? Future<void>.value());
    super.dispose();
  }

  Widget _logo({required double availableWidth, required bool reduceMotion}) {
    // Scale the *entire* canonical lockup uniformly so its painter and
    // wordmark retain their exact approved proportions on 320dp devices.
    final width = math.min(availableWidth, 350.0);
    return AnimatedBuilder(
      animation: _reveal,
      builder: (context, child) {
        final progress = reduceMotion
            ? 1.0
            : Curves.easeOutCubic.transform(_reveal.value);
        return Opacity(
          opacity: progress.clamp(0.0, 1.0),
          child: Transform.translate(
            offset: Offset(0, (1 - progress) * 14),
            child: Transform(
              alignment: Alignment.center,
              transform: Matrix4.identity()
                ..setEntry(3, 2, 0.0005)
                ..rotateY((1 - progress) * 0.06),
              child: Transform.scale(
                scale: 0.975 + 0.025 * progress,
                child: child,
              ),
            ),
          ),
        );
      },
      // The mark and lettering are the real MintTickMark/MintTickLockup
      // widgets. Never synthesize or recolor the approved logo for mockups.
      child: SizedBox(
        width: width,
        child: FittedBox(
          fit: BoxFit.scaleDown,
          alignment: Alignment.center,
          child: SizedBox(
            width: 390,
            child: Theme(
              data: buildTheme(Brightness.dark),
              child: const MintTickLockup(markWidth: 104, fontSize: 39),
            ),
          ),
        ),
      ),
    );
  }

  Widget _actions(BuildContext context, {required bool replayFromSettings}) {
    final primaryStyle = FilledButton.styleFrom(
      minimumSize: const Size(double.infinity, 54),
      foregroundColor: MintTickPalette.navy,
      backgroundColor: MintTickPalette.mint,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
      textStyle: const TextStyle(fontSize: 16, fontWeight: FontWeight.w800),
    );
    final secondaryStyle = OutlinedButton.styleFrom(
      minimumSize: const Size(double.infinity, 54),
      foregroundColor: MintTickPalette.ice,
      backgroundColor: const Color(0xFF19232D),
      side: const BorderSide(color: Color(0xFF789A9C)),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
      textStyle: const TextStyle(fontSize: 16, fontWeight: FontWeight.w700),
    );

    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        if (replayFromSettings)
          SizedBox(
            width: double.infinity,
            child: FilledButton(
              key: const ValueKey('minttick-welcome-done'),
              onPressed: () => Navigator.of(context).pop(),
              style: primaryStyle,
              child: const Text('Done'),
            ),
          )
        else ...[
          SizedBox(
            width: double.infinity,
            child: FilledButton(
              key: const ValueKey('minttick-welcome-sign-in'),
              onPressed: () => _enter('/sign-in'),
              style: primaryStyle,
              child: const Text('Sign in'),
            ),
          ),
          const SizedBox(height: 12),
          SizedBox(
            width: double.infinity,
            child: OutlinedButton(
              key: const ValueKey('minttick-welcome-create-account'),
              onPressed: () => _enter('/sign-up'),
              style: secondaryStyle,
              child: const Text('Create account'),
            ),
          ),
        ],
        const SizedBox(height: 18),
        Wrap(
          alignment: WrapAlignment.center,
          crossAxisAlignment: WrapCrossAlignment.center,
          children: [
            TextButton(
              key: const ValueKey('minttick-welcome-sound-toggle'),
              onPressed: _toggleSound,
              style: TextButton.styleFrom(
                foregroundColor: const Color(0xFF94A5AD),
                textStyle: const TextStyle(fontSize: 13),
                padding: const EdgeInsets.symmetric(horizontal: 4),
                minimumSize: const Size(44, 44),
              ),
              child: Text(_soundEnabled ? 'Sound on' : 'Sound off'),
            ),
            const Text('·', style: TextStyle(color: Color(0xFF94A5AD))),
            TextButton(
              key: const ValueKey('minttick-welcome-replay'),
              onPressed: _replay,
              style: TextButton.styleFrom(
                foregroundColor: const Color(0xFF94A5AD),
                textStyle: const TextStyle(fontSize: 13),
                padding: const EdgeInsets.symmetric(horizontal: 4),
                minimumSize: const Size(44, 44),
              ),
              child: const Text('Replay intro'),
            ),
          ],
        ),
      ],
    );
  }

  @override
  Widget build(BuildContext context) {
    final reduceMotion = MediaQuery.of(context).disableAnimations;
    final replayFromSettings = Navigator.of(context).canPop();
    return AnnotatedRegion<SystemUiOverlayStyle>(
      value: SystemUiOverlayStyle.light,
      child: Scaffold(
        key: const ValueKey('minttick-welcome-screen'),
        backgroundColor: MintTickPalette.navy,
        body: SafeArea(
          child: LayoutBuilder(
            builder: (context, bounds) {
              final horizontal = bounds.maxWidth < 380 ? 22.0 : 26.0;
              return SingleChildScrollView(
                child: ConstrainedBox(
                  constraints: BoxConstraints(minHeight: bounds.maxHeight),
                  child: Padding(
                    padding: EdgeInsets.fromLTRB(horizontal, 12, horizontal, 26),
                    child: ConstrainedBox(
                      constraints: BoxConstraints(
                        minHeight: math.max(0, bounds.maxHeight - 38),
                      ),
                      child: Column(
                        children: [
                          SizedBox(
                            height: math.max(
                              280,
                              bounds.maxHeight < 630
                                  ? 300
                                  : bounds.maxHeight - (replayFromSettings ? 150 : 210),
                            ),
                            child: Stack(
                              alignment: Alignment.center,
                              children: [
                                Positioned.fill(
                                  child: DecoratedBox(
                                    decoration: BoxDecoration(
                                      gradient: RadialGradient(
                                        center: const Alignment(0, 0.07),
                                        radius: 0.75,
                                        colors: [
                                          MintTickPalette.teal.withValues(alpha: 0.095),
                                          MintTickPalette.navy.withValues(alpha: 0),
                                        ],
                                      ),
                                    ),
                                  ),
                                ),
                                Align(
                                  alignment: const Alignment(0, 0.19),
                                  child: Column(
                                    mainAxisSize: MainAxisSize.min,
                                    children: [
                                      _logo(
                                        availableWidth: bounds.maxWidth - horizontal * 2,
                                        reduceMotion: reduceMotion,
                                      ),
                                      const SizedBox(height: 26),
                                      Container(
                                        width: math.min(190, bounds.maxWidth * 0.5),
                                        height: 1,
                                        color: MintTickPalette.teal.withValues(alpha: 0.11),
                                      ),
                                    ],
                                  ),
                                ),
                              ],
                            ),
                          ),
                          ConstrainedBox(
                            constraints: const BoxConstraints(maxWidth: 440),
                            child: _actions(
                              context,
                              replayFromSettings: replayFromSettings,
                            ),
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
      ),
    );
  }
}

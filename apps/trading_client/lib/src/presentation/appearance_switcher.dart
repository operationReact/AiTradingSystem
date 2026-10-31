import 'package:flutter/material.dart';

import '../theme/app_theme.dart';
import 'responsive_layout.dart';

class AppearanceSwitcher extends StatelessWidget {
  const AppearanceSwitcher({
    required this.designStyle,
    required this.themeMode,
    required this.onDesignStyleChanged,
    required this.onThemeModeChanged,
    super.key,
  });

  final DesignStyle designStyle;
  final ThemeMode themeMode;
  final ValueChanged<DesignStyle> onDesignStyleChanged;
  final ValueChanged<ThemeMode> onThemeModeChanged;

  @override
  Widget build(BuildContext context) {
    final width = MediaQuery.sizeOf(context).width;
    final compact = ResponsiveLayout.classify(width) == ResponsiveClass.mobile;

    return Semantics(
      container: true,
      label: 'Appearance controls',
      child: Material(
        color: Colors.transparent,
        child: SafeArea(
          minimum: const EdgeInsets.all(8),
          child: Card(
            child: Padding(
              padding: EdgeInsets.symmetric(horizontal: compact ? 6 : 10, vertical: 6),
              child: Wrap(
                spacing: 6,
                runSpacing: 6,
                crossAxisAlignment: WrapCrossAlignment.center,
                children: [
                  SegmentedButton<DesignStyle>(
                    key: const ValueKey('global-design-style-control'),
                    showSelectedIcon: false,
                    segments: [
                      ButtonSegment(
                        value: DesignStyle.neonFuturisticAi,
                        icon: compact ? const Icon(Icons.auto_awesome_outlined, size: 18) : null,
                        label: Text(compact ? 'Neon' : 'Neon AI'),
                      ),
                      ButtonSegment(
                        value: DesignStyle.cleanModern,
                        icon: compact ? const Icon(Icons.crop_square_rounded, size: 18) : null,
                        label: const Text('Clean'),
                      ),
                    ],
                    selected: {designStyle},
                    onSelectionChanged: (selection) {
                      if (selection.isNotEmpty) onDesignStyleChanged(selection.first);
                    },
                  ),
                  SegmentedButton<ThemeMode>(
                    key: const ValueKey('global-theme-mode-control'),
                    showSelectedIcon: false,
                    segments: [
                      ButtonSegment(
                        value: ThemeMode.system,
                        icon: compact ? const Icon(Icons.settings_brightness_outlined, size: 18) : null,
                        label: Text('System', key: const ValueKey('global-theme-system')),
                      ),
                      ButtonSegment(
                        value: ThemeMode.light,
                        icon: compact ? const Icon(Icons.light_mode_outlined, size: 18) : null,
                        label: Text('Light', key: const ValueKey('global-theme-light')),
                      ),
                      ButtonSegment(
                        value: ThemeMode.dark,
                        icon: compact ? const Icon(Icons.dark_mode_outlined, size: 18) : null,
                        label: Text('Dark', key: const ValueKey('global-theme-dark')),
                      ),
                    ],
                    selected: {themeMode},
                    onSelectionChanged: (selection) {
                      if (selection.isNotEmpty) onThemeModeChanged(selection.first);
                    },
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

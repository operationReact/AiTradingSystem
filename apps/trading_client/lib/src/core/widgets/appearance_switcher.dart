import 'package:flutter/material.dart';

/// Appearance only: the same components and functionality in both modes.
class AppearanceSwitcher extends StatelessWidget {
  const AppearanceSwitcher({
    required this.themeMode,
    required this.onThemeModeChanged,
    super.key,
  });

  final ThemeMode themeMode;
  final ValueChanged<ThemeMode> onThemeModeChanged;

  @override
  Widget build(BuildContext context) => Semantics(
        container: true,
        label: 'Choose MintTick light or dark mode',
        child: SegmentedButton<ThemeMode>(
          key: const ValueKey('global-theme-mode-control'),
          showSelectedIcon: true,
          segments: const [
            ButtonSegment(
              value: ThemeMode.light,
              icon: Icon(Icons.light_mode_outlined, size: 18),
              label: Text('Light'),
            ),
            ButtonSegment(
              value: ThemeMode.dark,
              icon: Icon(Icons.dark_mode_outlined, size: 18),
              label: Text('Dark'),
            ),
          ],
          selected: {themeMode == ThemeMode.light ? ThemeMode.light : ThemeMode.dark},
          onSelectionChanged: (selection) {
            if (selection.isNotEmpty) onThemeModeChanged(selection.first);
          },
        ),
      );
}

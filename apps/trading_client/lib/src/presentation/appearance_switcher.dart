import 'package:flutter/material.dart';

import '../theme/app_theme.dart';

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
    return Semantics(
      container: true,
      label: 'Appearance controls',
      child: Material(
        color: Colors.transparent,
        child: SafeArea(
          minimum: const EdgeInsets.all(12),
          child: Card(
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
              child: Wrap(
                spacing: 8,
                runSpacing: 8,
                crossAxisAlignment: WrapCrossAlignment.center,
                children: [
                  SegmentedButton<DesignStyle>(
                    key: const ValueKey('global-design-style-control'),
                    showSelectedIcon: false,
                    segments: const [
                      ButtonSegment(
                        value: DesignStyle.neonFuturisticAi,
                        label: Text('Neon AI'),
                      ),
                      ButtonSegment(
                        value: DesignStyle.cleanModern,
                        label: Text('Clean'),
                      ),
                    ],
                    selected: {designStyle},
                    onSelectionChanged: (selection) {
                      if (selection.isNotEmpty) {
                        onDesignStyleChanged(selection.first);
                      }
                    },
                  ),
                  DropdownButtonHideUnderline(
                    child: DropdownButton<ThemeMode>(
                      key: const ValueKey('global-theme-mode-control'),
                      value: themeMode,
                      items: const [
                        DropdownMenuItem(
                          value: ThemeMode.system,
                          child: Text('System'),
                        ),
                        DropdownMenuItem(
                          value: ThemeMode.light,
                          child: Text('Light'),
                        ),
                        DropdownMenuItem(
                          value: ThemeMode.dark,
                          child: Text('Dark'),
                        ),
                      ],
                      onChanged: (value) {
                        if (value != null) onThemeModeChanged(value);
                      },
                    ),
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

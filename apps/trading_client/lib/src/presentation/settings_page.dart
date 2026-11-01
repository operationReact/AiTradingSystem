import 'package:flutter/material.dart';

import '../features/trading_control/data/trading_control_repository.dart';
import '../features/trading_control/presentation/trading_control_card.dart';
import '../theme/app_theme.dart';

class SettingsPage extends StatelessWidget {
  const SettingsPage({
    required this.designStyle,
    required this.themeMode,
    required this.onDesignStyleChanged,
    required this.onThemeModeChanged,
    required this.tradingControlRepository,
    super.key,
  });

  final DesignStyle designStyle;
  final ThemeMode themeMode;
  final ValueChanged<DesignStyle> onDesignStyleChanged;
  final ValueChanged<ThemeMode> onThemeModeChanged;
  final TradingControlRepository tradingControlRepository;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.extension<TradingColors>()!;

    return Scaffold(
      appBar: AppBar(
        title: const Text('Settings'),
        centerTitle: false,
      ),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.fromLTRB(20, 12, 20, 120),
          children: [
            Text(
              'Control your trading experience',
              style: theme.textTheme.headlineSmall?.copyWith(fontWeight: FontWeight.w800),
            ),
            const SizedBox(height: 8),
            Text(
              'Use PAPER mode while strategies are validated. Manual and automated execution both remain protected by the same risk engine.',
              style: theme.textTheme.bodyMedium?.copyWith(color: theme.colorScheme.onSurfaceVariant, height: 1.45),
            ),
            const SizedBox(height: 24),
            TradingControlCard(repository: tradingControlRepository),
            const SizedBox(height: 16),
            _SectionCard(
              title: 'Interface profile',
              subtitle: 'Visual language, surfaces and emphasis',
              child: Column(
                children: [
                  _ProfileTile(
                    title: 'Neon Futuristic AI',
                    subtitle: 'Cyan / violet / magenta glow, dark trading surfaces',
                    icon: Icons.auto_awesome_rounded,
                    selected: designStyle == DesignStyle.neonFuturisticAi,
                    accent: colors.neonCyan,
                    onTap: () => onDesignStyleChanged(DesignStyle.neonFuturisticAi),
                  ),
                  const SizedBox(height: 10),
                  _ProfileTile(
                    title: 'Clean Modern',
                    subtitle: 'Refined neutral surfaces for dense analysis',
                    icon: Icons.space_dashboard_outlined,
                    selected: designStyle == DesignStyle.cleanModern,
                    accent: theme.colorScheme.primary,
                    onTap: () => onDesignStyleChanged(DesignStyle.cleanModern),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 16),
            _SectionCard(
              title: 'Color mode',
              subtitle: 'Independent from the interface profile',
              child: SegmentedButton<ThemeMode>(
                key: const ValueKey('settings-theme-mode-control'),
                showSelectedIcon: false,
                segments: const [
                  ButtonSegment(value: ThemeMode.system, icon: Icon(Icons.settings_brightness_rounded), label: Text('System')),
                  ButtonSegment(value: ThemeMode.light, icon: Icon(Icons.light_mode_rounded), label: Text('Light')),
                  ButtonSegment(value: ThemeMode.dark, icon: Icon(Icons.dark_mode_rounded), label: Text('Dark')),
                ],
                selected: {themeMode},
                onSelectionChanged: (selection) {
                  if (selection.isNotEmpty) onThemeModeChanged(selection.first);
                },
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _SectionCard extends StatelessWidget {
  const _SectionCard({required this.title, required this.subtitle, required this.child});

  final String title;
  final String subtitle;
  final Widget child;

  @override
  Widget build(BuildContext context) => Card(
        child: Padding(
          padding: const EdgeInsets.all(18),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(title, style: Theme.of(context).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w800)),
              const SizedBox(height: 4),
              Text(subtitle, style: Theme.of(context).textTheme.bodySmall?.copyWith(color: Theme.of(context).colorScheme.onSurfaceVariant)),
              const SizedBox(height: 18),
              child,
            ],
          ),
        ),
      );
}

class _ProfileTile extends StatelessWidget {
  const _ProfileTile({
    required this.title,
    required this.subtitle,
    required this.icon,
    required this.selected,
    required this.accent,
    required this.onTap,
  });

  final String title;
  final String subtitle;
  final IconData icon;
  final bool selected;
  final Color accent;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return InkWell(
      key: ValueKey('profile-$title'),
      borderRadius: BorderRadius.circular(16),
      onTap: onTap,
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 180),
        padding: const EdgeInsets.all(14),
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(16),
          border: Border.all(color: selected ? accent : scheme.outlineVariant, width: selected ? 1.6 : 1),
          color: selected ? accent.withValues(alpha: 0.08) : Colors.transparent,
        ),
        child: Row(
          children: [
            Container(
              width: 42,
              height: 42,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                color: accent.withValues(alpha: 0.12),
                boxShadow: selected ? [BoxShadow(color: accent.withValues(alpha: 0.24), blurRadius: 18)] : null,
              ),
              child: Icon(icon, color: accent),
            ),
            const SizedBox(width: 14),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(title, style: Theme.of(context).textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w800)),
                  const SizedBox(height: 3),
                  Text(subtitle, style: Theme.of(context).textTheme.bodySmall?.copyWith(color: scheme.onSurfaceVariant)),
                ],
              ),
            ),
            if (selected) Icon(Icons.check_circle_rounded, color: accent),
          ],
        ),
      ),
    );
  }
}

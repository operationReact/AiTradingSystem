import 'package:flutter/material.dart';

import '../features/trading_control/data/trading_control_repository.dart';
import '../features/trading_control/presentation/trading_control_card.dart';

/// MintTick has exactly one design system. Users can only choose its
/// accessible light or dark appearance; layout and feature set stay identical.
class SettingsPage extends StatelessWidget {
  const SettingsPage({
    required this.themeMode,
    required this.onThemeModeChanged,
    this.tradingControlRepository,
    this.onSignOut,
    this.onHistoryMonitor,
    super.key,
  });

  final ThemeMode themeMode;
  final ValueChanged<ThemeMode> onThemeModeChanged;
  final TradingControlRepository? tradingControlRepository;
  final Future<void> Function()? onSignOut;
  final VoidCallback? onHistoryMonitor;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;

    return Scaffold(
      appBar: AppBar(title: const Text('Settings')),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.fromLTRB(16, 12, 16, 112),
          children: [
            Text('Appearance',
                style: theme.textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w700)),
            const SizedBox(height: 5),
            Text(
              'The same MintTick experience in light or dark.',
              style: theme.textTheme.bodyMedium?.copyWith(color: scheme.onSurfaceVariant),
            ),
            const SizedBox(height: 14),
            Card(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Row(
                      children: [
                        Icon(Icons.palette_outlined, size: 20, color: scheme.primary),
                        const SizedBox(width: 10),
                        Expanded(
                          child: Text('Color mode',
                              style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w700)),
                        ),
                      ],
                    ),
                    const SizedBox(height: 14),
                    SegmentedButton<ThemeMode>(
                      key: const ValueKey('settings-theme-mode-control'),
                      showSelectedIcon: true,
                      expandedInsets: EdgeInsets.zero,
                      segments: const [
                        ButtonSegment(
                          value: ThemeMode.light,
                          icon: Icon(Icons.light_mode_rounded, size: 19),
                          label: Text('Light'),
                        ),
                        ButtonSegment(
                          value: ThemeMode.dark,
                          icon: Icon(Icons.dark_mode_rounded, size: 19),
                          label: Text('Dark'),
                        ),
                      ],
                      selected: {themeMode == ThemeMode.light ? ThemeMode.light : ThemeMode.dark},
                      onSelectionChanged: (selection) {
                        if (selection.isNotEmpty) onThemeModeChanged(selection.first);
                      },
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 12),
            Card(
              child: ListTile(
                key: const ValueKey('settings-replay-brand-intro'),
                leading: const Icon(Icons.animation_outlined),
                title: const Text('Brand introduction'),
                subtitle: const Text('View the approved MintTick brand logo.'),
                trailing: const Icon(Icons.chevron_right_rounded),
                onTap: () => Navigator.of(context).pushNamed('/welcome-replay'),
              ),
            ),
            if (onHistoryMonitor != null) ...[
              const SizedBox(height: 12),
              Card(
                child: ListTile(
                  key: const ValueKey('settings-historical-monitor'),
                  leading: const Icon(Icons.storage_outlined),
                  title: const Text('Historical Data Monitor'),
                  subtitle: const Text('Owner-only Angel One download and scheduler progress.'),
                  trailing: const Icon(Icons.chevron_right_rounded),
                  onTap: onHistoryMonitor,
                ),
              ),
            ],
            if (onSignOut != null) ...[
              const SizedBox(height: 14),
              Card(
                child: ListTile(
                  key: const ValueKey('minttick-settings-sign-out'),
                  leading: const Icon(Icons.logout_rounded),
                  title: const Text('Sign out'),
                  subtitle: const Text('End your MintTick session on this device.'),
                  onTap: () async => onSignOut?.call(),
                ),
              ),
            ],
            const SizedBox(height: 24),
            Text(tradingControlRepository == null
                    ? 'Trading readiness'
                    : 'Trading controls',
                style: theme.textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w700)),
            const SizedBox(height: 5),
            Text(
              tradingControlRepository == null
                  ? 'Paper and Live are available as Trading Room plans. '
                    'Order execution requires separate activation and authorization.'
                  : 'Only PAPER trading is available in the operator workspace. '
                    'Risk limits remain enforced.',
              style: theme.textTheme.bodyMedium?.copyWith(color: scheme.onSurfaceVariant),
            ),
            const SizedBox(height: 14),
            if (tradingControlRepository != null)
              TradingControlCard(repository: tradingControlRepository!)
            else
              const Card(
                child: Padding(
                  padding: EdgeInsets.all(16),
                  child: Text('Trading controls are not available in this session.'),
                ),
              ),
          ],
        ),
      ),
    );
  }
}

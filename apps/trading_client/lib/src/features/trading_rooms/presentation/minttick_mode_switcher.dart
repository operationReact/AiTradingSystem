import 'package:flutter/material.dart';
import '../domain/trading_workspace_mode.dart';

/// Always-visible workspace context. Mode switching changes only the view;
/// it never enables order placement or transfers balances between modes.
class MintTickModeSwitcher extends StatelessWidget {
  const MintTickModeSwitcher({
    required this.mode,
    super.key,
  });

  final TradingWorkspaceMode mode;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return AnimatedBuilder(
      animation: mode,
      builder: (context, _) => Container(
        key: const ValueKey('workspace-mode-switcher'),
        padding: const EdgeInsets.all(12),
        decoration: BoxDecoration(
          color: scheme.surfaceContainerLow,
          borderRadius: BorderRadius.circular(16),
          border: Border.all(color: scheme.outlineVariant),
        ),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Row(children: [
            Icon(Icons.swap_horiz_rounded, color: scheme.primary, size: 17),
            const SizedBox(width: 8),
            Text('TRADING WORKSPACE',
              style: Theme.of(context).textTheme.labelSmall?.copyWith(
                letterSpacing: 0.7,
                fontWeight: FontWeight.w800,
                color: scheme.onSurfaceVariant,
              )),
          ]),
          const SizedBox(height: 10),
          SizedBox(width: double.infinity, child: SegmentedButton<String>(
            key: const ValueKey('paper-live-toggle'),
            showSelectedIcon: true,
            style: SegmentedButton.styleFrom(
              visualDensity: VisualDensity.comfortable,
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 12),
            ),
            segments: const [
              ButtonSegment(
                value: TradingWorkspaceMode.paper,
                label: Text('Paper'),
                icon: Icon(Icons.science_outlined, size: 18),
              ),
              ButtonSegment(
                value: TradingWorkspaceMode.live,
                label: Text('Live'),
                icon: Icon(Icons.candlestick_chart_rounded, size: 18),
              ),
            ],
            selected: {mode.selected},
            onSelectionChanged: (values) => mode.select(values.first),
          )),
          const SizedBox(height: 8),
          AnimatedSwitcher(
            duration: MediaQuery.of(context).disableAnimations
                ? Duration.zero : const Duration(milliseconds: 180),
            child: Text(
              mode.isPaper
                  ? 'Simulated funds and Paper Trading Rooms'
                  : 'Real brokerage data and Live Trading Rooms',
              key: ValueKey(mode.selected),
              style: Theme.of(context).textTheme.bodySmall
                  ?.copyWith(color: scheme.onSurfaceVariant),
            ),
          ),
        ]),
      ),
    );
  }
}

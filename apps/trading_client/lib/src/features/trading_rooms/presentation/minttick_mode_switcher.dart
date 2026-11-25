import 'package:flutter/material.dart';
import '../domain/trading_workspace_mode.dart';

/// Compact workspace selector. Changing mode never authorizes order execution,
/// combines Paper/Live balances, or moves Trading Rooms across modes.
class MintTickModeSwitcher extends StatelessWidget {
  const MintTickModeSwitcher({required this.mode, super.key});

  final TradingWorkspaceMode mode;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return AnimatedBuilder(
      animation: mode,
      builder: (context, _) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 3),
        child: Row(
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    'Workspace',
                    style: Theme.of(context).textTheme.titleSmall?.copyWith(
                      fontWeight: FontWeight.w800,
                      color: scheme.onSurface,
                    ),
                  ),
                  const SizedBox(height: 2),
                  Text(
                    mode.isPaper ? 'Simulated' : 'Broker-linked',
                    style: Theme.of(context).textTheme.labelSmall?.copyWith(
                      color: scheme.onSurfaceVariant,
                    ),
                  ),
                ],
              ),
            ),
            SizedBox(
              width: 184,
              child: SegmentedButton<String>(
                key: const ValueKey('paper-live-toggle'),
                showSelectedIcon: false,
                style: ButtonStyle(
                  minimumSize: const WidgetStatePropertyAll(Size(0, 42)),
                  tapTargetSize: MaterialTapTargetSize.padded,
                  visualDensity: VisualDensity.compact,
                  padding: const WidgetStatePropertyAll(
                    EdgeInsets.symmetric(horizontal: 7, vertical: 5),
                  ),
                  backgroundColor: WidgetStateProperty.resolveWith(
                    (states) => states.contains(WidgetState.selected)
                        ? scheme.primary.withValues(alpha: 0.13)
                        : scheme.surfaceContainerLow,
                  ),
                  foregroundColor: WidgetStateProperty.resolveWith(
                    (states) => states.contains(WidgetState.selected)
                        ? scheme.onSurface
                        : scheme.onSurfaceVariant,
                  ),
                  side: WidgetStatePropertyAll(
                    BorderSide(color: scheme.outlineVariant),
                  ),
                  shape: WidgetStatePropertyAll(
                    RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(12),
                    ),
                  ),
                ),
                segments: const [
                  ButtonSegment(
                    value: TradingWorkspaceMode.paper,
                    label: Text('Paper'),
                  ),
                  ButtonSegment(
                    value: TradingWorkspaceMode.live,
                    label: Text('Live'),
                  ),
                ],
                selected: {mode.selected},
                onSelectionChanged: (values) => mode.select(values.first),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

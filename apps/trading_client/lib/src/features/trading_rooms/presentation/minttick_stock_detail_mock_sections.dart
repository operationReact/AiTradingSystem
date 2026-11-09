import 'package:flutter/material.dart';

import 'minttick_customer_widgets.dart';

/// Placeholder content for stock-detail sections whose data source is not connected yet.
/// Every value here is fixed sample text, never market data, and each block is labelled MOCK.
class MintTickStockDetailMockSections extends StatelessWidget {
  const MintTickStockDetailMockSections({super.key, required this.symbol});

  final String symbol;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        MintTickSurface(child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            _header(theme, 'News'),
            const SizedBox(height: 8),
            Text('Mock headline 1 · sample text for $symbol', key: const ValueKey('mock-news-1'),
                style: theme.textTheme.bodyMedium),
            const SizedBox(height: 4),
            Text('Mock headline 2 · sample text for $symbol', key: const ValueKey('mock-news-2'),
                style: theme.textTheme.bodyMedium),
            const SizedBox(height: 4),
            Text('Mock source · not a news feed', style: theme.textTheme.labelSmall
                ?.copyWith(color: scheme.onSurfaceVariant)),
          ],
        )),
        const SizedBox(height: 16),
        MintTickSurface(child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            _header(theme, 'Sector and similar stocks'),
            const SizedBox(height: 8),
            Text('Mock sector: Sample sector', key: const ValueKey('mock-sector'),
                style: theme.textTheme.bodyMedium),
            const SizedBox(height: 8),
            Wrap(spacing: 8, runSpacing: 8, children: [
              for (final s in const ['MOCK-A', 'MOCK-B', 'MOCK-C'])
                Chip(label: Text(s)),
            ]),
          ],
        )),
        const SizedBox(height: 16),
        MintTickSurface(child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            _header(theme, 'MintTick Mind'),
            const SizedBox(height: 8),
            Text('Mock commentary: sample insight text. Rules-based analysis is not connected yet.',
                key: const ValueKey('mock-mind-text'), style: theme.textTheme.bodyMedium),
          ],
        )),
        const SizedBox(height: 16),
        OutlinedButton(
          key: const ValueKey('mock-analyze-trade-room'),
          onPressed: () => ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
            content: Text('Mock action: Trade Room is not connected yet.'),
          )),
          child: const Text('Analyze in Trade Room (mock)'),
        ),
      ],
    );
  }

  Widget _header(ThemeData theme, String title) => Row(children: [
        Expanded(child: Text(title, style: theme.textTheme.titleSmall
            ?.copyWith(fontWeight: FontWeight.w700))),
        const MintTickMockBadge(),
      ]);
}

/// Small label that marks a block as sample content, not live data.
class MintTickMockBadge extends StatelessWidget {
  const MintTickMockBadge({super.key});

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Container(
      key: const ValueKey('mock-data-badge'),
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
      decoration: BoxDecoration(
        border: Border.all(color: scheme.outline),
        borderRadius: BorderRadius.circular(6),
      ),
      child: Text('MOCK', style: Theme.of(context).textTheme.labelSmall),
    );
  }
}

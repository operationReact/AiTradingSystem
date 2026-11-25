import 'package:flutter/material.dart';

import '../data/customer_portfolio_models.dart';
import 'minttick_customer_widgets.dart';

/// One broker-neutral design system for LIVE account pages. Provider logos,
/// verified data and capabilities vary; navigation, density and states do not.
class MintTickBrokerHeader extends StatelessWidget {
  const MintTickBrokerHeader({
    required this.logo, required this.broker, required this.account,
    required this.connected, required this.onRefresh,
    this.onBack, this.menu, this.refreshKey, super.key,
  });
  final Widget logo;
  final String broker;
  final String? account;
  final bool connected;
  final VoidCallback? onBack;
  final VoidCallback? onRefresh;
  final Widget? menu;
  final Key? refreshKey;

  @override
  Widget build(BuildContext context) {
    final text = Theme.of(context).textTheme;
    final muted = Theme.of(context).colorScheme.onSurfaceVariant;
    return Row(children: [
      if (onBack != null) IconButton(
        icon: const Icon(Icons.arrow_back_rounded),
        tooltip: 'Back to connected brokers',
        onPressed: onBack,
        visualDensity: VisualDensity.compact,
      ),
      logo,
      const SizedBox(width: 10),
      Expanded(child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(broker, maxLines: 1, overflow: TextOverflow.ellipsis,
            style: text.titleLarge?.copyWith(fontWeight: FontWeight.w800)),
          Text('Account ${account ?? '—'}', maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: text.labelSmall?.copyWith(color: muted)),
        ],
      )),
      MintTickStatusLabel(connected ? 'CONNECTED' : 'NOT CONNECTED',
        warning: !connected),
      IconButton(
        key: refreshKey,
        tooltip: 'Refresh broker data',
        icon: const Icon(Icons.refresh_rounded, size: 21),
        visualDensity: VisualDensity.compact,
        onPressed: onRefresh,
      ),
      ?menu,
    ]);
  }
}

class MintTickBrokerPortfolioHero extends StatelessWidget {
  const MintTickBrokerPortfolioHero({
    required this.value, required this.invested, required this.pnl,
    required this.holdingsCount, required this.isLoading,
    required this.broker, this.valueKey, this.orderExecutionEnabled = false,
    super.key,
  });
  final num? value;
  final num? invested;
  final num? pnl;
  final int? holdingsCount;
  final bool isLoading;
  final String broker;
  final bool orderExecutionEnabled;
  final Key? valueKey;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final theme = Theme.of(context).textTheme;
    final percent = invested != null && invested! > 0 && pnl != null
        ? pnl! / invested! * 100 : null;
    final pnlColor = pnl == null ? scheme.onSurface
        : pnl! < 0 ? scheme.error : scheme.primary;
    return MintTickSurface(
      accent: true, padding: const EdgeInsets.fromLTRB(14, 12, 14, 12),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          Expanded(child: Text('Current value',
            style: theme.bodySmall?.copyWith(color: scheme.onSurfaceVariant))),
          Text('LIVE · BROKER REPORTED',
            style: theme.labelSmall?.copyWith(
              color: scheme.primary, fontWeight: FontWeight.w700)),
        ]),
        const SizedBox(height: 5),
        Text(portfolioRupees(value), key: valueKey,
          style: theme.headlineMedium?.copyWith(fontWeight: FontWeight.w900)),
        const SizedBox(height: 2),
        Text(holdingsCount == null
            ? (isLoading ? 'Loading verified holdings…'
                : 'Holdings data unavailable — not ₹0')
            : holdingsCount == 0
                ? 'No holdings reported by $broker'
                : '$holdingsCount broker holdings',
          style: theme.labelSmall?.copyWith(color: scheme.onSurfaceVariant)),
        const SizedBox(height: 10),
        Divider(height: 1, color: scheme.outlineVariant),
        const SizedBox(height: 10),
        Row(children: [
          _metric(context, 'Invested', invested),
          const SizedBox(width: 12),
          _metric(context, 'Total P&L', pnl, valueColor: pnlColor),
        ]),
        if (percent != null) ...[
          const SizedBox(height: 5),
          Text('Total return: ${percent >= 0 ? '+' : ''}${percent.toStringAsFixed(2)}%',
            style: theme.labelSmall?.copyWith(color: pnlColor)),
        ],
        const SizedBox(height: 7),
        Text(orderExecutionEnabled
            ? 'Broker-linked account · Orders require separate authorization'
            : 'Read-only link · Real-money order execution disabled',
          style: theme.labelSmall?.copyWith(color: scheme.onSurfaceVariant)),
      ]),
    );
  }

  Widget _metric(BuildContext context, String title, num? amount,
      {Color? valueColor}) {
    final text = Theme.of(context).textTheme;
    return Expanded(child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(title, style: text.labelSmall?.copyWith(
          color: Theme.of(context).colorScheme.onSurfaceVariant)),
        const SizedBox(height: 4),
        FittedBox(fit: BoxFit.scaleDown,
          alignment: Alignment.centerLeft,
          child: Text(portfolioRupees(amount),
            style: text.titleMedium?.copyWith(
              color: valueColor, fontWeight: FontWeight.w800))),
      ],
    ));
  }
}

class MintTickBrokerTabs extends StatelessWidget {
  const MintTickBrokerTabs({
    required this.selected, required this.onSelected,
    required this.keyPrefix, super.key,
  });
  final String selected;
  final ValueChanged<String> onSelected;
  final String keyPrefix;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return SingleChildScrollView(
      scrollDirection: Axis.horizontal,
      child: Row(children: [
        for (final (id, title) in const [
          ('holdings', 'Holdings'),
          ('positions', 'Positions'),
          ('funds', 'Funds'),
          ('orders', 'Orders'),
        ]) ...[
          ChoiceChip(
            key: ValueKey('$keyPrefix$id'),
            label: Text(title),
            selected: selected == id,
            visualDensity: VisualDensity.compact,
            materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
            selectedColor: colors.primaryContainer,
            side: BorderSide(color: selected == id
                ? colors.primary : colors.outlineVariant),
            onSelected: (_) => onSelected(id),
          ),
          const SizedBox(width: 7),
        ],
      ]),
    );
  }
}

class MintTickBrokerSection extends StatelessWidget {
  const MintTickBrokerSection({
    required this.title, required this.children,
    required this.onRefresh, this.error, this.loading = false,
    this.refreshKey, super.key,
  });
  final String title;
  final List<Widget> children;
  final VoidCallback? onRefresh;
  final bool loading;
  final String? error;
  final Key? refreshKey;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    return MintTickSurface(padding: const EdgeInsets.all(12),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          Expanded(child: Text(title,
            style: text.titleMedium?.copyWith(fontWeight: FontWeight.w800))),
          if (loading) const SizedBox(height: 15, width: 15,
            child: CircularProgressIndicator(strokeWidth: 2)),
          IconButton(
            key: refreshKey, tooltip: 'Reload section',
            visualDensity: VisualDensity.compact,
            onPressed: loading ? null : onRefresh,
            icon: const Icon(Icons.refresh_rounded, size: 18),
          ),
        ]),
        const SizedBox(height: 5),
        if (error != null)
          Text(error!, style: text.bodySmall?.copyWith(
            color: colors.onSurfaceVariant))
        else if (loading && children.isEmpty)
          Text('Loading broker data…', style: text.bodySmall?.copyWith(
            color: colors.onSurfaceVariant))
        else
          ...children,
      ]),
    );
  }
}

class MintTickBrokerHoldingsList extends StatelessWidget {
  const MintTickBrokerHoldingsList({required this.rows, super.key});
  final List<PortfolioInstrument>? rows;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    if (rows == null) {
      return const Text('Holdings unavailable. Refresh to retry.');
    }
    if (rows!.isEmpty) return const Text('No holdings reported by broker.');
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      for (final holding in rows!.take(50)) ...[
        Padding(
          padding: const EdgeInsets.symmetric(vertical: 7),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Row(children: [
              Expanded(child: Text(holding.symbol,
                style: text.bodyMedium?.copyWith(fontWeight: FontWeight.w700),
                maxLines: 1, overflow: TextOverflow.ellipsis)),
              const SizedBox(width: 8),
              Text(portfolioRupees(holding.marketValue),
                style: text.bodyMedium?.copyWith(fontWeight: FontWeight.w800)),
            ]),
            const SizedBox(height: 3),
            Text('Qty ${holding.quantity ?? '—'} · '
                'Last ${portfolioRupees(holding.lastPrice)} · '
                'Avg ${portfolioRupees(holding.averagePrice)}',
              style: text.bodySmall?.copyWith(color: scheme.onSurfaceVariant)),
            if (holding.pnl != null)
              Text('Broker P&L ${portfolioRupees(holding.pnl)}',
                style: text.labelSmall?.copyWith(color: holding.pnl! < 0
                    ? scheme.error : scheme.primary)),
          ]),
        ),
        Divider(height: 1, color: scheme.outlineVariant),
      ],
      if (rows!.length > 50)
        Text('Showing first 50 of ${rows!.length} holdings',
          style: text.bodySmall?.copyWith(color: scheme.onSurfaceVariant)),
      if (portfolioHoldingValue(rows) == null) ...[
        const SizedBox(height: 7),
        Text('Some broker holding prices or quantities are unavailable; '
            'missing totals are shown as —, not ₹0.',
          style: text.bodySmall?.copyWith(color: scheme.onSurfaceVariant)),
      ],
      const SizedBox(height: 6),
      Text('Prices are broker reported and may lag the broker app.',
        style: text.labelSmall?.copyWith(color: scheme.onSurfaceVariant)),
    ]);
  }
}

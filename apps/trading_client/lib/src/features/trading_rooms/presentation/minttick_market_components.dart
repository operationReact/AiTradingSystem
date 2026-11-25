import 'package:flutter/material.dart';

import '../data/customer_market_repository.dart';
import 'minttick_equity_avatar.dart';

// ---------------------------------------------------------------------------
// Formatting
// ---------------------------------------------------------------------------

/// Indian digit grouping: 1234567.8 → "12,34,567.80".
String formatInr(num value, {int decimals = 2}) {
  final negative = value < 0;
  final fixed = value.abs().toStringAsFixed(decimals);
  final parts = fixed.split('.');
  var whole = parts.first;
  if (whole.length > 3) {
    final last3 = whole.substring(whole.length - 3);
    var rest = whole.substring(0, whole.length - 3);
    final groups = <String>[];
    while (rest.length > 2) {
      groups.insert(0, rest.substring(rest.length - 2));
      rest = rest.substring(0, rest.length - 2);
    }
    if (rest.isNotEmpty) groups.insert(0, rest);
    whole = '${groups.join(',')},$last3';
  }
  final body = parts.length > 1 ? '$whole.${parts[1]}' : whole;
  return negative ? '-$body' : body;
}

String formatPrice(num value) => '₹${formatInr(value)}';

/// Traded value in crore / lakh, e.g. 12_34_00_000 → "₹12.34 Cr".
String formatValue(num value) {
  if (value >= 1e7) return '₹${formatInr(value / 1e7)} Cr';
  if (value >= 1e5) return '₹${formatInr(value / 1e5)} L';
  return '₹${formatInr(value, decimals: 0)}';
}

String formatVolume(num value) {
  if (value >= 1e7) return '${(value / 1e7).toStringAsFixed(2)} Cr';
  if (value >= 1e5) return '${(value / 1e5).toStringAsFixed(2)} L';
  if (value >= 1e3) return '${(value / 1e3).toStringAsFixed(1)} K';
  return '$value';
}

String signedPct(num value) => '${value > 0 ? '+' : ''}${value.toStringAsFixed(2)}%';

String signedNumber(num value) => '${value > 0 ? '+' : ''}${formatInr(value)}';

String clockText(DateTime time) =>
    '${time.hour.toString().padLeft(2, '0')}:${time.minute.toString().padLeft(2, '0')}';

String clockSeconds(DateTime time) =>
    '${clockText(time)}:${time.second.toString().padLeft(2, '0')}';

const _months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

String dayClock(DateTime time) =>
    '${time.day} ${_months[time.month - 1]} ${clockText(time)}';

String isoDate(DateTime d) =>
    '${d.year.toString().padLeft(4, '0')}-${d.month.toString().padLeft(2, '0')}-'
    '${d.day.toString().padLeft(2, '0')}';

/// Direction colour; direction is always also carried by sign/arrow text.
Color directionColor(ColorScheme scheme, num value) =>
    value > 0 ? scheme.primary : value < 0 ? scheme.error : scheme.onSurfaceVariant;

const segmentLabels = {
  'ALL': 'All NSE', 'NIFTY50': 'Nifty 50', 'LARGE': 'Large cap', 'MID': 'Mid cap',
  'SMALL': 'Small cap', 'MICRO': 'Micro cap',
};

const segmentRanks = {
  'LARGE': 'Top 100', 'MID': 'Rank 101–250', 'SMALL': 'Rank 251–500', 'MICRO': 'Rank 501–750',
};

// ---------------------------------------------------------------------------
// Building blocks
// ---------------------------------------------------------------------------

class MarketSectionHeader extends StatelessWidget {
  const MarketSectionHeader({required this.title, this.trailing, this.subtitle, super.key});
  final String title;
  final String? subtitle;
  final Widget? trailing;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.only(top: 18, bottom: 8),
      child: Row(children: [
        Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Semantics(header: true, child: Text(title, style: theme.textTheme.titleMedium
              ?.copyWith(fontWeight: FontWeight.w800, letterSpacing: -0.2))),
          if (subtitle != null)
            Padding(
              padding: const EdgeInsets.only(top: 1),
              child: Text(subtitle!, maxLines: 1, overflow: TextOverflow.ellipsis,
                  style: theme.textTheme.labelSmall
                      ?.copyWith(color: theme.colorScheme.onSurfaceVariant)),
            ),
        ])),
        ?trailing,
      ]),
    );
  }
}

class MarketSeeAll extends StatelessWidget {
  const MarketSeeAll({required this.onTap, this.label = 'See all', super.key});
  final VoidCallback onTap;
  final String label;

  @override
  Widget build(BuildContext context) => TextButton(
    onPressed: onTap,
    style: TextButton.styleFrom(
      visualDensity: VisualDensity.compact,
      padding: const EdgeInsets.symmetric(horizontal: 6),
      minimumSize: const Size(48, 36),
    ),
    child: Row(mainAxisSize: MainAxisSize.min, children: [
      Text(label),
      const Icon(Icons.chevron_right_rounded, size: 18),
    ]),
  );
}

/// Filled change pill: "+1.24%" on a tinted background, arrow included for non-colour cues.
class ChangePill extends StatelessWidget {
  const ChangePill({required this.pct, this.dense = false, super.key});
  final num pct;
  final bool dense;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final color = directionColor(scheme, pct);
    return Container(
      padding: EdgeInsets.symmetric(horizontal: dense ? 5 : 7, vertical: dense ? 2 : 3),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.13),
        borderRadius: BorderRadius.circular(6),
      ),
      child: Row(mainAxisSize: MainAxisSize.min, children: [
        Icon(pct > 0 ? Icons.arrow_drop_up_rounded
            : pct < 0 ? Icons.arrow_drop_down_rounded : Icons.remove_rounded,
            size: dense ? 14 : 16, color: color),
        Text(signedPct(pct).replaceFirst('+', '').replaceFirst('-', ''),
          style: Theme.of(context).textTheme.labelMedium?.copyWith(
            color: color, fontWeight: FontWeight.w800,
            fontSize: dense ? 11.5 : null,
            fontFeatures: const [FontFeature.tabularFigures()])),
      ]),
    );
  }
}

/// Advance/decline bar with counts in text.
class BreadthBar extends StatelessWidget {
  const BreadthBar({required this.advancers, required this.decliners,
    required this.unchanged, this.height = 8, this.showCounts = true, super.key});
  final int advancers;
  final int decliners;
  final int unchanged;
  final double height;
  final bool showCounts;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final total = advancers + decliners + unchanged;
    final style = theme.textTheme.labelMedium?.copyWith(fontWeight: FontWeight.w700,
        fontFeatures: const [FontFeature.tabularFigures()]);
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      ClipRRect(
        borderRadius: BorderRadius.circular(height),
        child: SizedBox(height: height, child: total == 0
            ? ColoredBox(color: scheme.outlineVariant)
            : Row(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                if (advancers > 0) Expanded(flex: advancers, child: ColoredBox(color: scheme.primary)),
                if (unchanged > 0) Expanded(flex: unchanged, child: ColoredBox(color: scheme.outline)),
                if (decliners > 0) Expanded(flex: decliners, child: ColoredBox(color: scheme.error)),
              ])),
      ),
      if (showCounts) ...[
        const SizedBox(height: 7),
        Wrap(spacing: 14, runSpacing: 4, children: [
          Text('Advancing $advancers', style: style?.copyWith(color: scheme.primary)),
          Text('Declining $decliners', style: style?.copyWith(color: scheme.error)),
          if (unchanged > 0)
            Text('Unchanged $unchanged', style: style?.copyWith(color: scheme.onSurfaceVariant)),
        ]),
      ],
    ]);
  }
}

/// Compact horizontally scrolling index card.
class IndexTickerCard extends StatelessWidget {
  const IndexTickerCard({required this.index, required this.stored, super.key});
  final CustomerIndexRow index;
  final bool stored;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final stale = index.freshness == 'STALE';
    final time = clockText(index.exchangeAt.toLocal());
    final change = index.ltp - index.previousClose;
    final color = directionColor(scheme, index.changePct);
    final range = index.high - index.low;
    final position = range <= 0 ? 0.5 : ((index.ltp - index.low) / range).clamp(0.0, 1.0).toDouble();
    final freshness = stored
        ? 'Stored quote · ${dayClock(index.exchangeAt.toLocal())}'
        : switch (index.freshness) {
            'LIVE' => 'Live · $time',
            'STALE' => 'Stale · last tick $time',
            _ => 'Last recorded · ${dayClock(index.exchangeAt.toLocal())}',
          };
    return Semantics(
      container: true,
      label: stale
          ? '${index.displayName}, stale, value hidden, last tick $time.'
          : '${index.displayName}, ${formatInr(index.ltp)}, change ${signedPct(index.changePct)}, $freshness.',
      child: ExcludeSemantics(
        child: Container(
          key: ValueKey('customer-market-index-${index.indexCode}'),
          width: 168,
          padding: const EdgeInsets.fromLTRB(12, 10, 12, 10),
          decoration: BoxDecoration(
            color: scheme.surfaceContainerLow,
            borderRadius: BorderRadius.circular(14),
            border: Border.all(color: scheme.outlineVariant.withValues(alpha: 0.8)),
          ),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(index.displayName, maxLines: 1, overflow: TextOverflow.ellipsis,
                style: theme.textTheme.labelMedium?.copyWith(
                    fontWeight: FontWeight.w700, color: scheme.onSurfaceVariant)),
            const SizedBox(height: 4),
            if (stale) ...[
              Text('—', style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w800)),
              Text('Value hidden', style: theme.textTheme.labelSmall
                  ?.copyWith(color: scheme.onSurfaceVariant)),
            ] else ...[
              Text(formatInr(index.ltp), maxLines: 1,
                  style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w800,
                      fontFeatures: const [FontFeature.tabularFigures()])),
              const SizedBox(height: 1),
              Text('${signedNumber(change)} (${signedPct(index.changePct)})', maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: theme.textTheme.labelSmall?.copyWith(color: color,
                      fontWeight: FontWeight.w800,
                      fontFeatures: const [FontFeature.tabularFigures()])),
              const SizedBox(height: 7),
              LayoutBuilder(builder: (context, constraints) => SizedBox(
                height: 6,
                child: Stack(clipBehavior: Clip.none, children: [
                  Positioned(left: 0, right: 0, top: 2, child: Container(height: 2,
                      decoration: BoxDecoration(color: scheme.outlineVariant,
                          borderRadius: BorderRadius.circular(2)))),
                  Positioned(left: (constraints.maxWidth - 6) * position, top: 0,
                      child: Container(width: 6, height: 6,
                          decoration: BoxDecoration(color: color, shape: BoxShape.circle))),
                ]),
              )),
            ],
            const SizedBox(height: 5),
            Text(freshness, maxLines: 1, overflow: TextOverflow.ellipsis,
                style: theme.textTheme.labelSmall?.copyWith(
                    color: scheme.onSurfaceVariant, fontSize: 10.5)),
          ]),
        ),
      ),
    );
  }
}

/// Large / mid / small / micro cap tile.
class SegmentTile extends StatelessWidget {
  const SegmentTile({required this.segment, required this.onTap, super.key});
  final CustomerSegmentSummary segment;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final avg = segment.avgChangePct;
    final ready = segment.available && segment.quoted > 0 && avg != null;
    final color = ready ? directionColor(scheme, avg) : scheme.onSurfaceVariant;
    final b = segment.breadth;
    return Semantics(
      button: true,
      label: ready
          ? '${segment.label}, average change ${signedPct(avg)}, ${b.advancers} advancing, '
              '${b.decliners} declining, ${segment.quoted} quoted.'
          : '${segment.label}, ${segment.available ? 'no quotes yet' : 'official list not imported yet'}.',
      child: ExcludeSemantics(
        child: Material(
          color: ready ? color.withValues(alpha: 0.07) : scheme.surfaceContainerLow,
          borderRadius: BorderRadius.circular(14),
          child: InkWell(
            key: ValueKey('market-segment-${segment.segment}'),
            borderRadius: BorderRadius.circular(14),
            onTap: onTap,
            child: Container(
              padding: const EdgeInsets.fromLTRB(12, 10, 12, 10),
              decoration: BoxDecoration(
                borderRadius: BorderRadius.circular(14),
                border: Border.all(color: ready ? color.withValues(alpha: 0.28)
                    : scheme.outlineVariant.withValues(alpha: 0.8)),
              ),
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Row(children: [
                  Expanded(child: Text(segment.label, maxLines: 1, overflow: TextOverflow.ellipsis,
                      style: theme.textTheme.labelLarge?.copyWith(fontWeight: FontWeight.w800))),
                  Icon(Icons.chevron_right_rounded, size: 16, color: scheme.onSurfaceVariant),
                ]),
                Text(segmentRanks[segment.segment] ?? '', style: theme.textTheme.labelSmall
                    ?.copyWith(color: scheme.onSurfaceVariant, fontSize: 10.5)),
                const SizedBox(height: 6),
                if (ready) ...[
                  Text(signedPct(avg), style: theme.textTheme.titleMedium?.copyWith(
                      color: color, fontWeight: FontWeight.w800,
                      fontFeatures: const [FontFeature.tabularFigures()])),
                  const SizedBox(height: 6),
                  BreadthBar(advancers: b.advancers, decliners: b.decliners,
                      unchanged: b.unchanged, height: 4, showCounts: false),
                  const SizedBox(height: 4),
                  Text('▲ ${b.advancers}  ▼ ${b.decliners}', style: theme.textTheme.labelSmall
                      ?.copyWith(color: scheme.onSurfaceVariant,
                          fontFeatures: const [FontFeature.tabularFigures()])),
                ] else ...[
                  Text('—', style: theme.textTheme.titleMedium?.copyWith(
                      color: scheme.onSurfaceVariant, fontWeight: FontWeight.w800)),
                  const SizedBox(height: 6),
                  Text(segment.available ? 'No quotes this session' : 'List not imported',
                      maxLines: 2, style: theme.textTheme.labelSmall
                          ?.copyWith(color: scheme.onSurfaceVariant)),
                ],
              ]),
            ),
          ),
        ),
      ),
    );
  }
}

/// Dense mover row used across Explore, Movers and sector drill-down.
class BoardStockRow extends StatelessWidget {
  const BoardStockRow({required this.row, required this.onTap, this.metric = 'CHANGE',
    this.rank, super.key});
  final CustomerBoardRow row;
  final VoidCallback onTap;
  /// Sort that produced the list; picks the secondary figure (value, volume, 52W).
  final String metric;
  final int? rank;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final secondary = switch (metric) {
      'ACTIVE' => 'Value ${formatValue(row.tradedValue)}',
      'VOLUME' => 'Vol ${formatVolume(row.volume)}',
      'HIGH52' => row.week52High == null ? '' : '52W H ${formatInr(row.week52High!)}',
      'LOW52' => row.week52Low == null ? '' : '52W L ${formatInr(row.week52Low!)}',
      'GAP_UP' || 'GAP_DOWN' => 'Gap ${signedPct(row.gapPct)}',
      _ => [
          if (row.capSegment != null) segmentLabels[row.capSegment]!,
          if (row.industry != null) row.industry!,
        ].join(' · '),
    };
    final name = row.companyName.isEmpty ? row.symbol : row.companyName;
    return Semantics(
      button: true,
      label: '$name, ${row.symbol}, ${formatPrice(row.ltp)}, change ${signedPct(row.changePct)}'
          '${secondary.isEmpty ? '' : ', $secondary'}, exchange time '
          '${dayClock(row.exchangeAt.toLocal())}. Opens stock detail.',
      child: ExcludeSemantics(
        child: InkWell(
          key: ValueKey('market-board-row-${row.instrumentId}'),
          onTap: onTap,
          child: Container(
            constraints: const BoxConstraints(minHeight: 58),
            padding: const EdgeInsets.symmetric(vertical: 8),
            decoration: BoxDecoration(border: Border(bottom: BorderSide(
                color: scheme.outlineVariant.withValues(alpha: 0.45)))),
            child: Row(children: [
              if (rank != null)
                SizedBox(width: 22, child: Text('$rank', style: theme.textTheme.labelSmall
                    ?.copyWith(color: scheme.onSurfaceVariant))),
              MintTickEquityAvatar(tradingSymbol: row.tradingSymbol,
                  companyName: row.companyName, size: 36),
              const SizedBox(width: 10),
              Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text(row.symbol, maxLines: 1, overflow: TextOverflow.ellipsis,
                    style: theme.textTheme.bodyMedium?.copyWith(fontWeight: FontWeight.w800)),
                const SizedBox(height: 1),
                Text(secondary.isEmpty ? name : '$name · $secondary', maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: theme.textTheme.labelSmall?.copyWith(color: scheme.onSurfaceVariant)),
              ])),
              const SizedBox(width: 8),
              Column(crossAxisAlignment: CrossAxisAlignment.end, children: [
                Text(formatPrice(row.ltp), style: theme.textTheme.bodyMedium?.copyWith(
                    fontWeight: FontWeight.w800,
                    fontFeatures: const [FontFeature.tabularFigures()])),
                const SizedBox(height: 3),
                ChangePill(pct: row.changePct, dense: true),
              ]),
            ]),
          ),
        ),
      ),
    );
  }
}

/// Compact mover card for horizontal carousels.
class BoardMoverCard extends StatelessWidget {
  const BoardMoverCard({required this.row, required this.onTap, super.key});
  final CustomerBoardRow row;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    return Semantics(
      button: true,
      label: '${row.companyName}, ${formatPrice(row.ltp)}, change ${signedPct(row.changePct)}. Opens stock detail.',
      child: ExcludeSemantics(
        child: Material(
          color: scheme.surfaceContainerLow,
          borderRadius: BorderRadius.circular(14),
          child: InkWell(
            key: ValueKey('market-mover-card-${row.instrumentId}'),
            borderRadius: BorderRadius.circular(14),
            onTap: onTap,
            child: Container(
              width: 132,
              padding: const EdgeInsets.all(11),
              decoration: BoxDecoration(
                borderRadius: BorderRadius.circular(14),
                border: Border.all(color: scheme.outlineVariant.withValues(alpha: 0.8)),
              ),
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                MintTickEquityAvatar(tradingSymbol: row.tradingSymbol,
                    companyName: row.companyName, size: 30),
                const SizedBox(height: 8),
                Text(row.symbol, maxLines: 1, overflow: TextOverflow.ellipsis,
                    style: theme.textTheme.labelLarge?.copyWith(fontWeight: FontWeight.w800)),
                Text(formatPrice(row.ltp), maxLines: 1, style: theme.textTheme.labelMedium
                    ?.copyWith(color: scheme.onSurfaceVariant,
                        fontFeatures: const [FontFeature.tabularFigures()])),
                const SizedBox(height: 6),
                ChangePill(pct: row.changePct, dense: true),
              ]),
            ),
          ),
        ),
      ),
    );
  }
}

/// Heat-map tile: background intensity scales with |average change|.
class SectorHeatTile extends StatelessWidget {
  const SectorHeatTile({required this.sector, required this.name, required this.avgChangePct,
    required this.advancers, required this.decliners, required this.scale,
    this.onTap, super.key});
  final String sector;
  final String name;
  final num avgChangePct;
  final int advancers;
  final int decliners;
  final double scale;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final color = directionColor(scheme, avgChangePct);
    final intensity = scale <= 0 ? 0.0
        : (avgChangePct.abs() / scale).clamp(0.0, 1.0).toDouble();
    return Semantics(
      button: onTap != null,
      label: '$name, average change ${signedPct(avgChangePct)}, $advancers up, $decliners down.',
      child: ExcludeSemantics(
        child: Material(
          color: Color.alphaBlend(color.withValues(alpha: 0.06 + 0.24 * intensity),
              scheme.surfaceContainerLow),
          borderRadius: BorderRadius.circular(12),
          child: InkWell(
            key: ValueKey('market-sector-$sector'),
            borderRadius: BorderRadius.circular(12),
            onTap: onTap,
            child: Padding(
              padding: const EdgeInsets.fromLTRB(10, 9, 10, 9),
              child: Column(crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
                Text(name, maxLines: 1, overflow: TextOverflow.ellipsis,
                    style: theme.textTheme.labelMedium?.copyWith(fontWeight: FontWeight.w700)),
                const SizedBox(height: 6),
                Text(signedPct(avgChangePct), style: theme.textTheme.titleSmall?.copyWith(
                    color: color, fontWeight: FontWeight.w800,
                    fontFeatures: const [FontFeature.tabularFigures()])),
                Text('▲$advancers ▼$decliners', maxLines: 1, overflow: TextOverflow.clip,
                    style: theme.textTheme.labelSmall
                        ?.copyWith(color: scheme.onSurfaceVariant, fontSize: 10.5)),
              ]),
            ),
          ),
        ),
      ),
    );
  }
}

/// Short readable sector names for tiles.
String shortSector(String sector) => switch (sector) {
  'Automobile and Auto Components' => 'Auto',
  'Fast Moving Consumer Goods' => 'FMCG',
  'Information Technology' => 'IT',
  'Oil Gas & Consumable Fuels' => 'Oil & Gas',
  'Metals & Mining' => 'Metals',
  'Construction Materials' => 'Cement',
  'Telecommunication' => 'Telecom',
  'Financial Services' => 'Financials',
  'Consumer Durables' => 'Durables',
  'Consumer Services' => 'Consumer Svcs',
  'Media Entertainment & Publication' => 'Media',
  'Forest Materials' => 'Forest',
  _ => sector,
};

/// Choice chips in a single horizontally scrolling row (never wraps into tall blocks).
class MarketChipRow extends StatelessWidget {
  const MarketChipRow({required this.labels, required this.selected,
    required this.onSelected, this.keyPrefix, this.enabled, super.key});
  final List<String> labels;
  final int selected;
  final ValueChanged<int> onSelected;
  final String? keyPrefix;
  final List<bool>? enabled;

  @override
  Widget build(BuildContext context) {
    return SingleChildScrollView(
      scrollDirection: Axis.horizontal,
      child: Row(children: [
        for (var i = 0; i < labels.length; i++)
          Padding(
            padding: const EdgeInsets.only(right: 6),
            child: ChoiceChip(
              key: keyPrefix == null ? null : ValueKey('$keyPrefix-$i'),
              label: Text(labels[i]),
              selected: selected == i,
              showCheckmark: false,
              visualDensity: VisualDensity.compact,
              onSelected: enabled != null && !enabled![i] ? null : (_) => onSelected(i),
            ),
          ),
      ]),
    );
  }
}

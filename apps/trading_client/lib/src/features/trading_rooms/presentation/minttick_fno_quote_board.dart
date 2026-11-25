import 'package:flutter/material.dart';
import '../data/customer_paper_fno_repository.dart';

/// Real server-backed option quote panel. Fresh V3 trades, separately
/// authorized retained last trades, and short-lived read-only V2 chain
/// snapshots retain distinct labels and NEVER share order eligibility.
/// No generated index quote, volume, candles or broker authorization.
class MintTickFnoQuoteBoard extends StatelessWidget {
  const MintTickFnoQuoteBoard({
    required this.market, required this.fresh, required this.strike,
    required this.selected, required this.onStrike, required this.onLeg,
    required this.onTicket, required this.canClose, super.key,
  });
  final CustomerFnoMarket market;
  final bool fresh;
  final num? strike;
  final CustomerFnoLeg? selected;
  final ValueChanged<num> onStrike;
  final ValueChanged<CustomerFnoLeg> onLeg;
  final bool canClose;
  /// Opens a ticket. Submission still requires explicit confirmation.
  final void Function(CustomerFnoLeg leg, String side) onTicket;

  CustomerFnoStrike? get active {
    if (market.strikes.isEmpty) return null;
    for (final s in market.strikes) {
      if (s.strikePrice == strike) return s;
    }
    return market.strikes.first;
  }

  bool get verified => fresh && market.displayRightsVerified &&
      market.hasFreshQuotes;

  /// The backend returns retained last trades only with independent rights.
  /// A stale price can be displayed as Last, but never used for order entry.
  static bool retainedTradeVisible(
      CustomerFnoMarket market, CustomerFnoLeg leg, DateTime now) {
    final at = leg.exchangeAt?.toUtc();
    final utc = now.toUtc();
    return market.displayRightsVerified && !leg.quoteFresh &&
        leg.ltp != null && leg.ltp! > 0 && at != null &&
        !at.isAfter(utc) &&
        utc.difference(at) <= const Duration(days: 7);
  }

  /// V2 HTTP snapshots are display-only and cannot establish exchange time.
  static CustomerFnoChainSnapshotIndicator? recentChainSnapshot(
      CustomerFnoMarket market, CustomerFnoLeg leg, DateTime now) {
    final at = market.snapshotAt?.toUtc();
    final utc = now.toUtc();
    if (!market.displayRightsVerified || at == null || at.isAfter(utc) ||
        utc.difference(at) > const Duration(minutes: 5)) {
      return null;
    }
    return market.chainSnapshotIndicators[leg.instrumentKey];
  }

  static String lastTime(DateTime? at) {
    if (at == null) return '—';
    final local = at.toLocal();
    return '${local.day.toString().padLeft(2, '0')}/'
        '${local.month.toString().padLeft(2, '0')} '
        '${local.hour.toString().padLeft(2, '0')}:'
        '${local.minute.toString().padLeft(2, '0')}';
  }

  static String value(num? v) => v == null || !v.isFinite || v <= 0
      ? '—' : '₹${v.toStringAsFixed(2)}';

  @override
  Widget build(BuildContext context) {
    final c = Theme.of(context).colorScheme;
    final t = Theme.of(context).textTheme;
    final s = active;
    // First strike's CE is a display-only choice, never an implicit trade.
    final observed = selected ?? s?.call;
    final metrics = verified && observed?.quoteFresh == true ? observed : null;
    String decimal(num? v) => v == null ? '—' : v.toStringAsFixed(2);
    final iv = metrics?.iv;
    final greeks = <String, String>{
      'Delta': decimal(metrics?.delta),
      'Theta': decimal(metrics?.theta),
      'IV': iv == null ? '—' : '${(iv * 100).toStringAsFixed(2)}%',
      'Vega': decimal(metrics?.vega),
    };
    final hasVerifiedGreeks = metrics != null &&
        (metrics.delta != null || metrics.theta != null ||
         metrics.iv != null || metrics.vega != null);
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      const SizedBox(height: 8),
      Row(children: [
        const Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start,
          children: [Text('NIFTY 50', style: TextStyle(fontSize: 21,
            fontWeight: FontWeight.w800)),
            Text('NSE · Index', style: TextStyle(fontSize: 11))])),
        Column(crossAxisAlignment: CrossAxisAlignment.end, children: [
          const Text('—', key: ValueKey('fno-index-unavailable'),
            style: TextStyle(fontWeight: FontWeight.w800, fontSize: 22)),
          Text(verified ? 'OPTIONS BID/ASK VERIFIED' : 'QUOTE NOT VERIFIED LIVE',
            style: t.labelSmall?.copyWith(
              color: verified ? c.primary : c.onSurfaceVariant, fontSize: 9)),
        ]),
      ]),
      Text('Index LTP unavailable from current F&O API',
        style: t.labelSmall?.copyWith(color: c.onSurfaceVariant)),
      Divider(color: c.outlineVariant, height: 12),
      _OptionsReferenceList(
        market: market, verified: verified, selected: selected,
        onLeg: onLeg, onStrike: onStrike,
      ),
      const SizedBox(height: 8),
      Row(children: [
        const Text('Strike', style: TextStyle(fontWeight: FontWeight.w700)),
        const SizedBox(width: 12),
        Expanded(child: Container(
          height: 44, padding: const EdgeInsets.symmetric(horizontal: 10),
          decoration: _box(c),
          child: DropdownButtonHideUnderline(child: DropdownButton<num>(
            key: const ValueKey('fno-premium-strike-picker'),
            isExpanded: true, value: s?.strikePrice,
            hint: const Text('No listed strikes'),
            items: [
              for (final row in market.strikes)
                DropdownMenuItem(value: row.strikePrice,
                  child: Text(row.strikePrice.toString())),
            ],
            onChanged: s == null ? null :
              (v) { if (v != null) onStrike(v); },
          )),
        )),
      ]),
      const SizedBox(height: 10),
      if (s != null) Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Expanded(child: _card(context, s.call, s.strikePrice, 'CE', c.primary)),
        const SizedBox(width: 8),
        Expanded(child: _card(context, s.put, s.strikePrice, 'PE', c.error)),
      ]) else const Text('No verified CE/PE contracts for this expiry.'),
      const SizedBox(height: 8),
      Row(children: [
        Expanded(child: FilledButton(
          key: const ValueKey('fno-premium-buy-call'),
          onPressed: !verified || s?.call?.paperEligible != true ||
              s?.call?.quoteFresh != true ? null : () => onTicket(s!.call!, 'BUY'),
          child: const FittedBox(child: Text('BUY CALL')))),
        const SizedBox(width: 6),
        Expanded(child: OutlinedButton(
          key: const ValueKey('fno-premium-buy-put'),
          onPressed: !verified || s?.put?.paperEligible != true ||
              s?.put?.quoteFresh != true ? null : () => onTicket(s!.put!, 'BUY'),
          child: const FittedBox(child: Text('BUY PUT')))),
        const SizedBox(width: 6),
        Expanded(child: OutlinedButton(
          key: const ValueKey('fno-premium-sell-close'),
          onPressed: !verified || !canClose || selected == null ||
              !selected!.quoteFresh || !selected!.paperEligible
                ? null : () => onTicket(selected!, 'SELL'),
          child: const Text('SELL'))),
      ]),
      Text('Buttons select a PAPER contract; the guarded limit-price ticket '
          'below still controls all order submission.',
        style: t.labelSmall?.copyWith(color: c.onSurfaceVariant)),
      const SizedBox(height: 12),
      // Show missing data in a compact state, not an empty chart-sized box.
      Container(
        key: const ValueKey('fno-premium-candle-placeholder'),
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 9),
        decoration: _box(c),
        child: Row(children: [
          Icon(Icons.candlestick_chart_outlined, color: c.onSurfaceVariant,
            size: 18),
          const SizedBox(width: 9),
          Expanded(child: Column(
            crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text('Verified option candles unavailable',
              style: t.bodySmall?.copyWith(fontWeight: FontWeight.w600)),
            Text('OHLCV history endpoint not yet connected',
              style: t.labelSmall?.copyWith(color: c.onSurfaceVariant)),
          ])),
        ]),
      ),
      // No empty Greeks or analytics cards: the API must supply verified data.
      if (hasVerifiedGreeks) ...[
        const SizedBox(height: 8),
        _strip(context, 'Greeks', ['Delta', 'Theta', 'IV', 'Vega'], greeks),
      ],
    ]);
  }

  Widget _card(BuildContext context, CustomerFnoLeg? leg,
      num strike, String side, Color accent) {
    final c = Theme.of(context).colorScheme;
    final t = Theme.of(context).textTheme;
    final live = verified && leg != null && leg.quoteFresh;
    final retained = leg != null &&
        retainedTradeVisible(market, leg, DateTime.now().toUtc());
    final chain = leg == null ? null :
        recentChainSnapshot(market, leg, DateTime.now().toUtc());
    final selectedLeg = leg != null && selected?.instrumentKey == leg.instrumentKey;
    final displayedLtp = live || retained ? leg.ltp : null;
    final chainLtp = displayedLtp == null ? chain?.ltp : null;
    final oi = market.displayRightsVerified
        ? ((live || retained ? leg.openInterest : null) ?? chain?.openInterest)
        : null;
    final pct = live ? leg.priceChangePct : null;
    return OutlinedButton(
      key: leg == null ? ValueKey('fno-premium-$side-unlisted') :
        ValueKey('paper-fno-leg-${leg.instrumentKey}'),
      onPressed: leg == null ? null : () => onLeg(leg),
      style: OutlinedButton.styleFrom(
        padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 7),
        alignment: Alignment.topLeft,
        side: BorderSide(color: selectedLeg ? accent : c.outlineVariant),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12))),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text('$side ${strike.toStringAsFixed(0)}',
          maxLines: 1, overflow: TextOverflow.ellipsis,
          style: t.titleSmall?.copyWith(fontWeight: FontWeight.w800, color: accent)),
        const SizedBox(height: 4),
        Text(displayedLtp != null
            ? '${live ? 'LTP' : 'Last'} ${value(displayedLtp)}'
            : chainLtp != null ? 'Chain ${value(chainLtp)}'
            : live ? 'Ask ${value(leg.ask)}' : 'LTP —',
          style: t.titleSmall?.copyWith(fontWeight: FontWeight.w800),
          maxLines: 1, overflow: TextOverflow.ellipsis),
        const SizedBox(height: 3),
        Text(live ? 'Bid ${value(leg.bid)}'
            : retained ? 'Last trade · ${lastTime(leg.exchangeAt)}'
            : chain != null ? 'Chain snapshot · read only'
            : 'Quote not fresh',
          style: t.labelSmall?.copyWith(
            color: live ? accent : c.onSurfaceVariant),
          maxLines: 1, overflow: TextOverflow.ellipsis),
        const SizedBox(height: 5),
        Divider(color: c.outlineVariant, height: 1),
        const SizedBox(height: 4),
        if (pct != null) Text(
          '${pct >= 0 ? '+' : ''}${pct.toStringAsFixed(2)}% vs close',
          style: t.labelSmall?.copyWith(
            color: pct >= 0 ? c.primary : c.error),
          maxLines: 1, overflow: TextOverflow.ellipsis),
        Text('OI ${oi ?? '—'}  ·  '
          '${live ? 'Volume' : chain?.volume != null ? 'Chain Vol' : 'Volume'} '
          '${(live ? leg.volume : chain?.volume) ?? '—'}',
          style: t.labelSmall?.copyWith(color: c.onSurfaceVariant),
          maxLines: 1, overflow: TextOverflow.ellipsis),
        const SizedBox(height: 2),
        Text(leg == null ? 'Not listed' : '${leg.lotSize} units / lot',
          style: t.labelSmall?.copyWith(color: c.onSurfaceVariant),
          maxLines: 1, overflow: TextOverflow.ellipsis),
      ]),
    );
  }

  Widget _strip(BuildContext context, String label, List<String> fields,
      [Map<String, String> values = const {}]) {
    final c = Theme.of(context).colorScheme;
    final t = Theme.of(context).textTheme;
    return Container(
      decoration: _box(c),
      padding: const EdgeInsets.symmetric(vertical: 6, horizontal: 7),
      child: Row(children: [
        Expanded(flex: 3, child: Text(label, maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: t.labelMedium?.copyWith(fontWeight: FontWeight.bold))),
        for (final f in fields) Expanded(flex: 2, child: Column(children: [
          Text(f, maxLines: 1, overflow: TextOverflow.ellipsis,
            style: t.labelSmall?.copyWith(
              color: c.onSurfaceVariant, fontSize: 9)),
          Text(values[f] ?? '—', maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(fontWeight: FontWeight.bold)),
        ])),
      ]),
    );
  }

  static BoxDecoration _box(ColorScheme c) => BoxDecoration(
    color: c.surfaceContainerLow,
    border: Border.all(color: c.outlineVariant),
    borderRadius: BorderRadius.circular(12),
  );
}


/// The same premium room information architecture even while the provider is
/// disabled or permissions are unverified. All price fields are explicitly
/// unavailable; disabled controls never invoke any trading or market API.
class MintTickFnoUnavailableOverview extends StatelessWidget {
  const MintTickFnoUnavailableOverview({super.key});

  @override
  Widget build(BuildContext context) {
    final c = Theme.of(context).colorScheme;
    final t = Theme.of(context).textTheme;
    BoxDecoration card() => BoxDecoration(
      color: c.surfaceContainerLow,
      border: Border.all(color: c.outlineVariant),
      borderRadius: BorderRadius.circular(12));
    Widget quote(String kind) => Expanded(child: Container(
      padding: const EdgeInsets.all(11), decoration: card(),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text('NIFTY · $kind',
          style: t.titleSmall?.copyWith(fontWeight: FontWeight.w800)),
        const SizedBox(height: 6),
        const Text('LTP —', style: TextStyle(
          fontSize: 18, fontWeight: FontWeight.bold)),
        const SizedBox(height: 7),
        Text('Bid —   Ask —', style: t.labelSmall),
        Text('OI —   Volume —', style: t.labelSmall?.copyWith(
          color: c.onSurfaceVariant)),
      ]),
    ));
    return Column(
      key: const ValueKey('fno-premium-unavailable-overview'),
      crossAxisAlignment: CrossAxisAlignment.start, children: [
      const SizedBox(height: 12),
      Row(children: [
        const Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start,
          children: [Text('NIFTY 50', style: TextStyle(
            fontWeight: FontWeight.w800, fontSize: 21)),
            Text('NSE · Index')])),
        Column(crossAxisAlignment: CrossAxisAlignment.end, children: [
          const Text('—', style: TextStyle(
            fontWeight: FontWeight.w800, fontSize: 22)),
          Text('NOT VERIFIED LIVE', style: t.labelSmall?.copyWith(
            color: c.onSurfaceVariant)),
        ]),
      ]),
      const SizedBox(height: 10),
      Row(children: [
        const Expanded(child: Text('Expiry —')),
        const Expanded(child: Text('Strike —')),
        const Text('CE  /  PE'),
        const SizedBox(width: 8),
        const Text('Lot —'),
      ]),
      const SizedBox(height: 10),
      Row(children: [
        quote('CE'), const SizedBox(width: 8), quote('PE'),
      ]),
      const SizedBox(height: 10),
      Container(
        width: double.infinity,
        padding: const EdgeInsets.all(10), decoration: card(),
        child: Row(children: [
          Icon(Icons.candlestick_chart_outlined, size: 18,
            color: c.onSurfaceVariant),
          const SizedBox(width: 8),
          Expanded(child: Text('No verified option candles available',
            style: t.bodySmall?.copyWith(color: c.onSurfaceVariant))),
        ]),
      ),
      // With no verified feed, displaying four fake-looking analytics cards
      // adds noise and is not an actionable trading signal.
      const SizedBox(height: 10),
      Row(children: [
        Expanded(child: FilledButton(
          key: const ValueKey('fno-premium-blocked-call'),
          onPressed: null, child: const FittedBox(child: Text('BUY CALL')))),
        const SizedBox(width: 6),
        Expanded(child: OutlinedButton(
          key: const ValueKey('fno-premium-blocked-put'),
          onPressed: null, child: const FittedBox(child: Text('BUY PUT')))),
        const SizedBox(width: 6),
        Expanded(child: OutlinedButton(
          key: const ValueKey('fno-premium-blocked-sell'),
          onPressed: null, child: const Text('SELL'))),
      ]),
    ]);
  }
}

/// Compact source-backed Options list, following the requested Call/Put table.
/// The UI uses exchange-listed contracts only; no generated strike or quote.
class _OptionsReferenceList extends StatefulWidget {
  const _OptionsReferenceList({
    required this.market,
    required this.verified,
    required this.selected,
    required this.onLeg,
    required this.onStrike,
  });

  final CustomerFnoMarket market;
  final bool verified;
  final CustomerFnoLeg? selected;
  final ValueChanged<CustomerFnoLeg> onLeg;
  final ValueChanged<num> onStrike;

  @override
  State<_OptionsReferenceList> createState() => _OptionsReferenceListState();
}

class _OptionsReferenceListState extends State<_OptionsReferenceList> {
  String side = 'CE';

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    final now = DateTime.now().toUtc();
    bool live(CustomerFnoLeg leg) => widget.verified && leg.quoteFresh;
    bool retained(CustomerFnoLeg leg) =>
        MintTickFnoQuoteBoard.retainedTradeVisible(widget.market, leg, now);
    CustomerFnoChainSnapshotIndicator? chain(CustomerFnoLeg leg) =>
        MintTickFnoQuoteBoard.recentChainSnapshot(widget.market, leg, now);
    int? oi(CustomerFnoLeg leg) =>
        widget.market.displayRightsVerified
            ? ((live(leg) || retained(leg) ? leg.openInterest : null) ??
                chain(leg)?.openInterest)
            : null;
    final contracts = <(num, CustomerFnoLeg)>[];
    for (final strike in widget.market.strikes) {
      final leg = side == 'CE' ? strike.call : strike.put;
      if (leg != null) contracts.add((strike.strikePrice, leg));
    }
    contracts.sort((a, b) {
      final lhs = oi(a.$2);
      final rhs = oi(b.$2);
      if (lhs == null && rhs == null) return a.$1.compareTo(b.$1);
      if (lhs == null) return 1;
      if (rhs == null) return -1;
      return rhs.compareTo(lhs);
    });
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Row(children: [
        Expanded(child: Text('Listed Options', style: text.titleMedium
            ?.copyWith(fontWeight: FontWeight.w800))),
        for (final option in const [('CE', 'Call'), ('PE', 'Put')])
          Padding(padding: const EdgeInsets.only(left: 6),
            child: ChoiceChip(
              key: ValueKey('fno-room-option-${option.$1.toLowerCase()}'),
              label: Text(option.$2),
              selected: side == option.$1,
              onSelected: (_) => setState(() => side = option.$1),
              visualDensity: VisualDensity.compact,
            )),
      ]),
      const SizedBox(height: 6),
      Row(children: [
        Expanded(flex: 5, child: Text('Option', style: text.labelSmall
            ?.copyWith(color: colors.onSurfaceVariant))),
        Expanded(flex: 3, child: Text('OI', textAlign: TextAlign.right,
            style: text.labelSmall?.copyWith(color: colors.onSurfaceVariant))),
        Expanded(flex: 3, child: Text('LTP', textAlign: TextAlign.right,
            style: text.labelSmall?.copyWith(color: colors.onSurfaceVariant))),
      ]),
      if (contracts.isEmpty)
        const Padding(padding: EdgeInsets.symmetric(vertical: 12),
          child: Text('No listed options for this expiry.')),
      for (final (strike, leg) in contracts.take(6))
        InkWell(
          key: ValueKey('fno-room-listed-${leg.instrumentKey}'),
          onTap: () {
            widget.onStrike(strike);
            widget.onLeg(leg);
          },
          child: Padding(
            padding: const EdgeInsets.symmetric(vertical: 10),
            child: Row(children: [
              Expanded(flex: 5, child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(leg.tradingSymbol, maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: text.bodyMedium?.copyWith(
                      fontWeight: FontWeight.w700)),
                  Text('NFO', style: text.labelSmall?.copyWith(
                    color: colors.onSurfaceVariant)),
                ],
              )),
              Expanded(flex: 3, child: Text(
                oi(leg)?.toString() ?? '—',
                textAlign: TextAlign.right,
                style: text.bodyMedium?.copyWith(fontWeight: FontWeight.w700),
              )),
              Expanded(flex: 3, child: Column(
                crossAxisAlignment: CrossAxisAlignment.end,
                children: [
                  Text(
                    (live(leg) || retained(leg) ? leg.ltp : chain(leg)?.ltp)
                            ?.toStringAsFixed(2) ?? '—',
                    style: text.bodyLarge?.copyWith(
                      fontWeight: FontWeight.w800,
                      color: live(leg)
                          ? ((leg.priceChangePct ?? 0) >= 0
                              ? colors.primary : colors.error)
                          : colors.onSurfaceVariant),
                  ),
                  if (live(leg) && leg.priceChangePct != null)
                    Text(
                      '${leg.priceChangePct! >= 0 ? '+' : ''}${leg.priceChangePct!.toStringAsFixed(2)}%',
                      style: text.labelSmall?.copyWith(
                          color: colors.onSurfaceVariant)),
                  if (retained(leg) && leg.ltp != null)
                    Text('Last · ${MintTickFnoQuoteBoard.lastTime(leg.exchangeAt)}',
                      style: text.labelSmall?.copyWith(
                        color: colors.onSurfaceVariant)),
                  if (!live(leg) && !retained(leg) && chain(leg)?.ltp != null)
                    Text('Chain · read only',
                      style: text.labelSmall?.copyWith(
                        color: colors.onSurfaceVariant)),
                ],
              )),
            ]),
          ),
        ),
    ]);
  }
}

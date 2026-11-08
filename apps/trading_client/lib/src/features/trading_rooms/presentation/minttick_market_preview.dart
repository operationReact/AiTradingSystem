import 'dart:async';
import 'package:flutter/material.dart';
import 'minttick_equity_avatar.dart';

import '../data/customer_market_repository.dart';

/// Verified cash-equity quote preview. No simulated prices or charts.
/// The owner feed or each customer's verified broker connection controls quote access.
class MintTickMarketPreview extends StatefulWidget {
  const MintTickMarketPreview({
    required this.repository,
    this.onMarkets,
    this.onConnectBroker,
    super.key,
  });

  final CustomerMarketRepository repository;
  final VoidCallback? onMarkets;
  final VoidCallback? onConnectBroker;

  @override
  State<MintTickMarketPreview> createState() => _MintTickMarketPreviewState();
}

class _PreviewQuote {
  const _PreviewQuote(this.symbol, this.quote);
  final String symbol;
  final CustomerMarketQuote? quote;
}

class _MintTickMarketPreviewState extends State<MintTickMarketPreview>
    with WidgetsBindingObserver {
  late Future<List<_PreviewQuote>> _result;
  Timer? _refreshTimer;
  bool _foreground = true;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _result = _fetch();
    _refreshTimer = Timer.periodic(const Duration(seconds: 30), (_) {
      if (mounted && _foreground && _duringIndianCashSession()) _refresh();
    });
  }

  @override
  void dispose() {
    _refreshTimer?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    _foreground = state == AppLifecycleState.resumed;
    if (_foreground && mounted) _refresh();
  }

  // UI polling guard only; authoritative exchange status comes from backend.
  bool _duringIndianCashSession() {
    final ist = DateTime.now().toUtc().add(
        const Duration(hours: 5, minutes: 30));
    final mins = ist.hour * 60 + ist.minute;
    if (ist.year == 2026 && ist.month == 11 && ist.day == 8) {
      return mins >= 14 * 60 + 20 && mins < 15 * 60 + 15;
    }
    return ist.weekday <= DateTime.friday &&
        mins >= 9 * 60 + 15 && mins < 15 * 60 + 30;
  }

  @override
  void didUpdateWidget(covariant MintTickMarketPreview oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.repository != oldWidget.repository) _result = _fetch();
  }

  Future<List<_PreviewQuote>> _fetch() async {
    const symbols = ['RELIANCE', 'TCS', 'INFY'];
    final output = <_PreviewQuote>[];
    try {
      final catalogue = await widget.repository.catalogueStatus();
      if (catalogue.activeCount <= 0) {
        return [for (final s in symbols) _PreviewQuote(s, null)];
      }
    } catch (_) {
      return [for (final s in symbols) _PreviewQuote(s, null)];
    }
    for (final symbol in symbols) {
      CustomerMarketQuote? quote;
      try {
        final page = await widget.repository.search(
          query: symbol, exchange: 'NSE');
        for (final instrument in page.content) {
          if (instrument.exchange == 'NSE' &&
              instrument.tradingSymbol.toUpperCase() == '$symbol-EQ') {
            quote = await widget.repository.quote(instrument.id);
            break;
          }
        }
      } catch (_) {
        // Never synthesize a price when the feed, authorization or catalogue fails.
      }
      output.add(_PreviewQuote(symbol, quote));
      // Pace successful REST requests; do not create pending delay timers
      // when a brokerage account is disconnected or a widget is disposed.
      if (quote != null && symbol != symbols.last) {
        await Future<void>.delayed(const Duration(milliseconds: 1250));
      }
    }
    return output;
  }

  void _refresh() {
    setState(() => _result = _fetch());
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final styles = Theme.of(context).textTheme;
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Row(children: [
        Expanded(child: Text('Market overview',
          style: styles.titleMedium?.copyWith(fontWeight: FontWeight.w700))),
        IconButton(
          key: const ValueKey('customer-market-preview-refresh'),
          tooltip: 'Refresh verified quotes',
          onPressed: _refresh,
          icon: const Icon(Icons.refresh_rounded, size: 20),
        ),
      ]),
      FutureBuilder<List<_PreviewQuote>>(
        future: _result,
        builder: (context, snapshot) {
          final quotes = snapshot.data;
          if (quotes == null) {
            return Container(
              key: const ValueKey('customer-market-preview-loading'),
              padding: const EdgeInsets.all(14),
              decoration: BoxDecoration(
                color: colors.surfaceContainerLow,
                borderRadius: BorderRadius.circular(14),
              ),
              child: const Text('Checking authenticated market feed…'),
            );
          }
          final hasPrices = quotes.any((item) => item.quote != null);
          return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Row(children: [
              for (var index = 0; index < quotes.length; index++) ...[
                if (index != 0) const SizedBox(width: 8),
                Expanded(child: Container(
                  key: ValueKey('customer-market-preview-${quotes[index].symbol}'),
                  padding: const EdgeInsets.fromLTRB(10, 12, 8, 12),
                  decoration: BoxDecoration(
                    color: colors.surfaceContainerLow,
                    border: Border.all(color: colors.outlineVariant),
                    borderRadius: BorderRadius.circular(14),
                  ),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      MintTickEquityAvatar(
                        tradingSymbol: '${quotes[index].symbol}-EQ',
                        size: 28,
                      ),
                      const SizedBox(height: 7),
                      Text(quotes[index].symbol,
                        maxLines: 1, overflow: TextOverflow.ellipsis,
                        style: styles.labelSmall?.copyWith(
                          color: colors.onSurfaceVariant)),
                      const SizedBox(height: 9),
                      Text(quotes[index].quote == null
                          ? '—'
                          : '₹${quotes[index].quote!.ltp.toStringAsFixed(2)}',
                        maxLines: 1, overflow: TextOverflow.ellipsis,
                        style: styles.titleSmall?.copyWith(
                          fontWeight: FontWeight.w700)),
                      const SizedBox(height: 4),
                      Text(quotes[index].quote == null
                          ? 'Unavailable'
                          : 'ON-DEMAND',
                        maxLines: 1, overflow: TextOverflow.ellipsis,
                        style: styles.labelSmall?.copyWith(
                          color: colors.onSurfaceVariant)),
                    ],
                  ),
                )),
              ],
            ]),
            const SizedBox(height: 7),
            Text(hasPrices
                ? 'Authenticated on-demand quotes · updated while the market is open. Not a tick-by-tick stream.'
                : 'Prices need a connected broker session. No simulated values are shown.',
              style: styles.bodySmall?.copyWith(
                color: colors.onSurfaceVariant)),
            if (hasPrices) ...[
              const SizedBox(height: 3),
              Text('Source and fetch time are shown on each instrument detail.',
                style: styles.labelSmall?.copyWith(
                  color: colors.onSurfaceVariant)),
            ],
          ]);
        },
      ),
      if (widget.onConnectBroker != null)
        Align(
          alignment: Alignment.centerLeft,
          child: TextButton.icon(
            key: const ValueKey('customer-market-preview-connect-broker'),
            onPressed: widget.onConnectBroker,
            icon: const Icon(Icons.link_rounded, size: 18),
            label: const Text('Manage Angel One connection'),
          ),
        ),
      if (widget.onMarkets != null)
        Align(
          alignment: Alignment.centerRight,
          child: TextButton(
            onPressed: widget.onMarkets,
            child: const Text('View instruments and quotes'),
          ),
        ),
    ]);
  }
}

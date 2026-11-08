import 'package:flutter/material.dart';

import '../data/customer_market_repository.dart';

/// Verified cash-equity quote preview. No simulated prices or charts.
/// The owner-only broker feed controls availability and data licensing.
class MintTickMarketPreview extends StatefulWidget {
  const MintTickMarketPreview({
    required this.repository,
    this.onMarkets,
    super.key,
  });

  final CustomerMarketRepository repository;
  final VoidCallback? onMarkets;

  @override
  State<MintTickMarketPreview> createState() => _MintTickMarketPreviewState();
}

class _PreviewQuote {
  const _PreviewQuote(this.symbol, this.quote);
  final String symbol;
  final CustomerMarketQuote? quote;
}

class _MintTickMarketPreviewState extends State<MintTickMarketPreview> {
  late Future<List<_PreviewQuote>> _result;

  @override
  void initState() {
    super.initState();
    _result = _fetch();
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
      // Broker feed has a one-second pacing rule. Avoid burst refreshes.
      if (symbol != symbols.last) {
        await Future<void>.delayed(const Duration(milliseconds: 1150));
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
                          : 'Verified quote',
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
                ? 'On-demand broker quotes · Retrieved at the displayed source time, not streaming prices.'
                : 'Quotes unavailable for this account. Verify the owner-authorized feed and instrument catalogue.',
              style: styles.bodySmall?.copyWith(
                color: colors.onSurfaceVariant)),
            if (hasPrices) ...[
              const SizedBox(height: 3),
              Text('Source: Angel One private feed · Times shown on instrument details.',
                style: styles.labelSmall?.copyWith(
                  color: colors.onSurfaceVariant)),
            ],
          ]);
        },
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

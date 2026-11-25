import 'dart:async';

import 'package:flutter/material.dart';

import '../../../data/home_quote_repository.dart';

/// Home-market read model. Deliberately shows broker retrieval time rather than
/// mislabeling HTTP-polled values as exchange-timestamped WebSocket ticks.
class HomeMarketQuotePanel extends StatefulWidget {
  const HomeMarketQuotePanel({required this.repository, super.key});
  final HomeQuoteRepository repository;

  @override
  State<HomeMarketQuotePanel> createState() => _HomeMarketQuotePanelState();
}

class _HomeMarketQuotePanelState extends State<HomeMarketQuotePanel> {
  HomeQuoteSnapshot? _snapshot;
  bool _loading = true;
  String? _error;
  Timer? _refreshTimer;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) _refresh();
    });
    _refreshTimer = Timer.periodic(const Duration(seconds: 60), (_) {
      if (mounted && !_loading) _refresh();
    });
  }

  @override
  void dispose() {
    _refreshTimer?.cancel();
    super.dispose();
  }

  Future<void> _refresh() async {
    if (_loading && _snapshot != null) return;
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final snapshot = await widget.repository.fetch();
      if (!mounted) return;
      setState(() => _snapshot = snapshot);
    } catch (_) {
      if (!mounted) return;
      setState(() => _error = 'Market quotes are currently unavailable.');
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  String _retrievedAt(DateTime value) {
    final ist = value.toUtc().add(const Duration(hours: 5, minutes: 30));
    String two(int number) => number.toString().padLeft(2, '0');
    return '${two(ist.hour)}:${two(ist.minute)} IST';
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final quotes = _snapshot?.quotes ?? const <HomeEquityQuote>[];
    final partial = (_snapshot?.unavailable ?? 0) > 0;
    final warning = _error != null || partial;
    return Card(
      key: const ValueKey('home-market-quotes'),
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Row(children: [
            const Icon(Icons.show_chart_rounded, size: 21),
            const SizedBox(width: 8),
            Expanded(child: Text('Market pulse',
                style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w800))),
            IconButton(
              tooltip: 'Refresh market quotes',
              key: const ValueKey('home-market-refresh'),
              onPressed: _loading ? null : _refresh,
              icon: Icon(_loading ? Icons.hourglass_top_rounded : Icons.refresh_rounded),
            ),
          ]),
          Text('NSE equities · broker quotes refreshed every 60 seconds',
              style: theme.textTheme.bodySmall?.copyWith(color: scheme.onSurfaceVariant)),
          const SizedBox(height: 10),
          if (_loading && quotes.isEmpty)
            const Padding(
              padding: EdgeInsets.symmetric(vertical: 8),
              child: Row(children: [
                SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2)),
                SizedBox(width: 12),
                Text('Connecting to market data…'),
              ]),
            )
          else if (quotes.isEmpty)
            Text(_error ?? 'No verified quotes are available yet. Check market-data connectivity.',
                key: const ValueKey('home-market-unavailable'),
                style: theme.textTheme.bodyMedium?.copyWith(color: scheme.onSurfaceVariant))
          else ...[
            SingleChildScrollView(
              scrollDirection: Axis.horizontal,
              child: Row(children: [
                for (final quote in quotes)
                  Padding(
                    padding: const EdgeInsets.only(right: 8),
                    child: Container(
                      width: 150,
                      padding: const EdgeInsets.all(12),
                      decoration: BoxDecoration(
                        color: scheme.surfaceContainerLow,
                        borderRadius: BorderRadius.circular(12),
                        border: Border.all(color: scheme.outlineVariant),
                      ),
                      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                        Text(quote.tradingSymbol,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: theme.textTheme.labelLarge?.copyWith(fontWeight: FontWeight.w800)),
                        const SizedBox(height: 6),
                        Text('₹${quote.ltp.toStringAsFixed(2)}',
                            key: ValueKey('home-quote-${quote.tradingSymbol}'),
                            style: theme.textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w800)),
                        const SizedBox(height: 4),
                        Text('Fetched ${_retrievedAt(quote.fetchedAt)}',
                            style: theme.textTheme.labelSmall?.copyWith(color: scheme.onSurfaceVariant)),
                      ]),
                    ),
                  ),
              ]),
            ),
            if (warning) ...[
              const SizedBox(height: 8),
              Text('Some prices could not be refreshed. Verify before trading.',
                  key: const ValueKey('home-market-partial'),
                  style: theme.textTheme.bodySmall?.copyWith(color: scheme.error)),
            ],
          ],
          const SizedBox(height: 8),
          Text('On-demand quotes, not streaming ticks. Prices may be delayed or stale.',
              style: theme.textTheme.labelSmall?.copyWith(color: scheme.onSurfaceVariant)),
        ]),
      ),
    );
  }
}

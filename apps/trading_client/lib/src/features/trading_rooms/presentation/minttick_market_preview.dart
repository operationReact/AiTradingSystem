import 'dart:async';
import 'package:flutter/material.dart';
import 'minttick_equity_avatar.dart';

import '../data/customer_market_repository.dart';

/// Overview of a few liquid cash equities, read from the same batched snapshot
/// as the Markets page. A price is shown only for a backend-verified LIVE row.
/// No simulated prices, no hardcoded exchange calendar.
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

const _previewSymbols = ['RELIANCE', 'TCS', 'INFY'];
const _pollInterval = Duration(seconds: 30);
const _maxTickAge = Duration(seconds: 12);

class _PreviewTile {
  const _PreviewTile(this.symbol, this.row);
  final String symbol;
  final CustomerLiveQuote? row;
}

class _MintTickMarketPreviewState extends State<MintTickMarketPreview>
    with WidgetsBindingObserver {
  late Future<List<_PreviewTile>> _result;
  final Map<String, int> _instrumentIds = {};
  Timer? _refreshTimer;
  bool _foreground = true;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _result = _fetch();
    _refreshTimer = Timer.periodic(_pollInterval, (_) {
      if (mounted && _foreground) _refresh();
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

  @override
  void didUpdateWidget(covariant MintTickMarketPreview oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.repository != oldWidget.repository) {
      _instrumentIds.clear();
      _result = _fetch();
    }
  }

  Future<void> _resolveIds(CustomerMarketRepository repo) async {
    for (final symbol in _previewSymbols) {
      if (_instrumentIds.containsKey(symbol)) continue;
      final page = await repo.search(query: symbol, exchange: 'NSE');
      for (final instrument in page.content) {
        if (instrument.exchange == 'NSE' &&
            instrument.tradingSymbol.toUpperCase() == '$symbol-EQ') {
          _instrumentIds[symbol] = instrument.id;
          break;
        }
      }
    }
  }

  Future<List<_PreviewTile>> _fetch() async {
    final empty = [for (final s in _previewSymbols) _PreviewTile(s, null)];
    final CustomerSnapshotMarketRepository? snapshotRepo =
        widget.repository is CustomerSnapshotMarketRepository
            ? widget.repository as CustomerSnapshotMarketRepository
            : null;
    if (snapshotRepo == null) return empty;
    final repo = widget.repository;
    try {
      final catalogue = await repo.catalogueStatus();
      if (catalogue.activeCount <= 0) return empty;
      await _resolveIds(repo);
      final resolved = [
        for (final s in _previewSymbols)
          if (_instrumentIds[s] != null) _instrumentIds[s]!,
      ];
      if (resolved.isEmpty) return empty;
      final snapshot = await snapshotRepo.snapshot(resolved);
      final byId = {for (final row in snapshot.rows) row.instrumentId: row};
      return [
        for (final s in _previewSymbols)
          _PreviewTile(s, _instrumentIds[s] == null
              ? null
              : byId[_instrumentIds[s]]),
      ];
    } catch (_) {
      // Never synthesize a price when the feed, authorization or catalogue fails.
      return empty;
    }
  }

  void _refresh() {
    setState(() {
      _result = _fetch();
    });
  }

  /// Verified LIVE price, or false when the row cannot be shown as live.
  bool _isLive(CustomerLiveQuote? row) {
    if (row == null || row.status != 'LIVE' || row.ltp == null) return false;
    final at = row.exchangeAt;
    if (at == null) return false;
    final age = DateTime.now().toUtc().difference(at.toUtc());
    return age <= _maxTickAge && age >= const Duration(seconds: -2);
  }

  String _label(CustomerLiveQuote? row) {
    if (row == null) return 'NO FEED';
    if (_isLive(row)) return 'LIVE';
    return switch (row.status) {
      'MARKET_CLOSED' => 'CLOSED',
      'CONNECTING' || 'RECONNECTING' => 'CONNECTING',
      'STALE' || 'LIVE' => 'STALE',
      _ => 'NO FEED',
    };
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
      FutureBuilder<List<_PreviewTile>>(
        future: _result,
        builder: (context, snapshot) {
          final tiles = snapshot.data;
          if (tiles == null) {
            return Container(
              key: const ValueKey('customer-market-preview-loading'),
              padding: const EdgeInsets.all(14),
              decoration: BoxDecoration(
                color: colors.surfaceContainerLow,
                borderRadius: BorderRadius.circular(14),
              ),
              child: const Text('Checking MintTick market feed…'),
            );
          }
          final hasPrices = tiles.any((tile) => _isLive(tile.row));
          return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Row(children: [
              for (var index = 0; index < tiles.length; index++) ...[
                if (index != 0) const SizedBox(width: 8),
                Expanded(child: _tile(tiles[index], colors, styles)),
              ],
            ]),
            const SizedBox(height: 7),
            Text(hasPrices
                ? 'Verified exchange ticks while the market is open. Prices show only when LIVE.'
                : 'No verified live prices right now. MintTick shows no simulated values.',
              style: styles.bodySmall?.copyWith(
                color: colors.onSurfaceVariant)),
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

  Widget _tile(_PreviewTile tile, ColorScheme colors, TextTheme styles) {
    final live = _isLive(tile.row);
    final label = _label(tile.row);
    final price = live ? '₹${tile.row!.ltp!.toStringAsFixed(2)}' : '—';
    return Semantics(
      label: '${tile.symbol}, ${live ? price : 'no live price'}, $label',
      child: Container(
        key: ValueKey('customer-market-preview-${tile.symbol}'),
        padding: const EdgeInsets.fromLTRB(10, 12, 8, 12),
        decoration: BoxDecoration(
          color: colors.surfaceContainerLow,
          border: Border.all(color: colors.outlineVariant),
          borderRadius: BorderRadius.circular(14),
        ),
        child: ExcludeSemantics(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              MintTickEquityAvatar(tradingSymbol: '${tile.symbol}-EQ', size: 28),
              const SizedBox(height: 7),
              Text(tile.symbol,
                maxLines: 1, overflow: TextOverflow.ellipsis,
                style: styles.labelSmall?.copyWith(color: colors.onSurfaceVariant)),
              const SizedBox(height: 9),
              Text(price,
                maxLines: 1, overflow: TextOverflow.ellipsis,
                style: styles.titleSmall?.copyWith(
                  fontWeight: FontWeight.w700,
                  fontFeatures: const [FontFeature.tabularFigures()])),
              const SizedBox(height: 4),
              Text(label,
                maxLines: 1, overflow: TextOverflow.ellipsis,
                style: styles.labelSmall?.copyWith(
                  color: colors.onSurfaceVariant, fontWeight: FontWeight.w700)),
            ],
          ),
        ),
      ),
    );
  }
}

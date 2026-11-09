import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../../../data/instrument_repository.dart';
import '../data/customer_market_repository.dart';
import 'minttick_customer_widgets.dart';
import 'minttick_equity_avatar.dart';
import 'minttick_stock_detail_mock_sections.dart';

const _quotePoll = Duration(seconds: 5);

/// Chart timeframes map to backend candle intervals. [lookbackDays] bounds the stored window.
const _timeframes = [
  (label: '5m', interval: 'FIVE_MINUTE', lookbackDays: 5),
  (label: '1H', interval: 'ONE_HOUR', lookbackDays: 60),
  (label: '1D', interval: 'ONE_DAY', lookbackDays: 365),
];

/// Stock detail: a verified live price when available, a stored-candle chart, and
/// only the statistics the backend serves. Missing values are labelled, never estimated.
class MintTickStockDetailPage extends StatefulWidget {
  const MintTickStockDetailPage({required this.stock, required this.repository, super.key});
  final InstrumentRecord stock;
  final CustomerMarketRepository repository;

  @override
  State<MintTickStockDetailPage> createState() => _MintTickStockDetailPageState();
}

class _MintTickStockDetailPageState extends State<MintTickStockDetailPage> {
  int _timeframe = 2;
  List<CustomerCandle>? _candles;
  bool _candlesLoading = false;
  bool _candlesFailed = false;
  int _candleRequest = 0;

  CustomerLiveQuote? _quote;
  bool _quoteFailed = false;
  bool _quoteBusy = false;
  Timer? _quoteTimer;

  CustomerDayDetail? _detail;
  bool _detailFailed = false;
  bool _detailLoading = false;

  /// Null until the backend has answered. A toggle never guesses membership.
  bool? _watched;
  bool _watchBusy = false;
  bool _watchFailed = false;

  bool get _hasCandleRepo => widget.repository is CustomerCandleRepository;
  bool get _hasSnapshotRepo => widget.repository is CustomerSnapshotMarketRepository;
  bool get _hasDetailRepo => widget.repository is CustomerDetailRepository;
  bool get _hasWatchRepo => widget.repository is CustomerWatchlistRepository;

  @override
  void initState() {
    super.initState();
    _loadCandles();
    if (_hasDetailRepo) _loadDetail();
    if (_hasWatchRepo) _loadWatchlist();
    if (_hasSnapshotRepo) {
      _pollQuote();
      _quoteTimer = Timer.periodic(_quotePoll, (_) => _pollQuote());
    }
  }

  @override
  void dispose() {
    _quoteTimer?.cancel();
    super.dispose();
  }

  Future<void> _loadCandles() async {
    final CustomerCandleRepository? repo = widget.repository is CustomerCandleRepository
        ? widget.repository as CustomerCandleRepository
        : null;
    if (repo == null) return;
    final request = ++_candleRequest;
    final timeframe = _timeframes[_timeframe];
    final now = DateTime.now();
    setState(() {
      _candlesLoading = true;
      _candlesFailed = false;
    });
    try {
      final page = await repo.candles(
        instrumentId: widget.stock.id,
        interval: timeframe.interval,
        fromDate: _istDate(now.subtract(Duration(days: timeframe.lookbackDays))),
        toDate: _istDate(now),
      );
      if (!mounted || request != _candleRequest) return;
      final sorted = [...page.items]
        ..sort((a, b) => a.candleTime.compareTo(b.candleTime));
      setState(() {
        _candles = sorted;
        _candlesLoading = false;
      });
    } catch (_) {
      if (!mounted || request != _candleRequest) return;
      setState(() {
        _candles = null;
        _candlesLoading = false;
        _candlesFailed = true;
      });
    }
  }

  Future<void> _pollQuote() async {
    final CustomerSnapshotMarketRepository? repo = widget.repository is CustomerSnapshotMarketRepository
        ? widget.repository as CustomerSnapshotMarketRepository
        : null;
    if (repo == null || _quoteBusy) return;
    _quoteBusy = true;
    try {
      final snapshot = await repo.snapshot([widget.stock.id]);
      if (!mounted) return;
      setState(() {
        _quote = snapshot.rows.first;
        _quoteFailed = false;
      });
    } catch (_) {
      if (!mounted) return;
      setState(() => _quoteFailed = true);
    } finally {
      _quoteBusy = false;
    }
  }

  Future<void> _loadDetail() async {
    final CustomerDetailRepository? repo = widget.repository is CustomerDetailRepository
        ? widget.repository as CustomerDetailRepository
        : null;
    if (repo == null || _detailLoading) return;
    setState(() {
      _detailLoading = true;
      _detailFailed = false;
    });
    try {
      final detail = await repo.dayDetail(widget.stock.id);
      if (!mounted) return;
      setState(() => _detail = detail);
    } catch (_) {
      if (!mounted) return;
      setState(() {
        _detail = null;
        _detailFailed = true;
      });
    } finally {
      if (mounted) setState(() => _detailLoading = false);
    }
  }

  void _selectTimeframe(int index) {
    if (index == _timeframe) return;
    setState(() => _timeframe = index);
    _loadCandles();
  }

  Future<void> _loadWatchlist() async {
    final CustomerWatchlistRepository? repo = widget.repository is CustomerWatchlistRepository
        ? widget.repository as CustomerWatchlistRepository
        : null;
    if (repo == null) return;
    try {
      final list = await repo.watchlist();
      if (!mounted) return;
      setState(() {
        _watched = list.items.any((i) => i.instrumentId == widget.stock.id);
        _watchFailed = false;
      });
    } catch (_) {
      if (!mounted) return;
      setState(() => _watchFailed = true);
    }
  }

  Future<void> _toggleWatch() async {
    final CustomerWatchlistRepository? repo = widget.repository is CustomerWatchlistRepository
        ? widget.repository as CustomerWatchlistRepository
        : null;
    final watched = _watched;
    if (repo == null || watched == null || _watchBusy) return;
    setState(() {
      _watchBusy = true;
      _watchFailed = false;
    });
    try {
      if (watched) {
        await repo.remove(widget.stock.id);
      } else {
        await repo.add(widget.stock.id);
      }
      if (!mounted) return;
      setState(() => _watched = !watched);
    } catch (_) {
      if (!mounted) return;
      setState(() => _watchFailed = true);
    } finally {
      if (mounted) setState(() => _watchBusy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final stock = widget.stock;
    final name = stock.name.trim().isEmpty
        ? stock.tradingSymbol.replaceFirst(RegExp(r'-EQ$'), '')
        : stock.name;
    return Scaffold(
      appBar: AppBar(title: Text(stock.tradingSymbol.replaceFirst(RegExp(r'-EQ$'), ''))),
      body: SafeArea(child: ListView(
        padding: const EdgeInsets.fromLTRB(16, 8, 16, 24),
        children: [
          Row(children: [
            MintTickEquityAvatar(tradingSymbol: stock.tradingSymbol, companyName: stock.name, size: 52),
            const SizedBox(width: 12),
            Expanded(child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(name, style: theme.textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w800)),
                Text('${stock.tradingSymbol} · ${stock.exchange}',
                  style: theme.textTheme.bodySmall?.copyWith(color: scheme.onSurfaceVariant)),
              ],
            )),
            if (_hasSnapshotRepo) _marketBadge(theme),
          ]),
          const SizedBox(height: 16),
          _priceBlock(theme),
          const SizedBox(height: 16),
          Wrap(spacing: 8, children: [
            for (final (index, tf) in _timeframes.indexed)
              ChoiceChip(
                key: ValueKey('stock-detail-timeframe-${tf.label}'),
                label: Text(tf.label),
                selected: index == _timeframe,
                onSelected: (_) => _selectTimeframe(index),
              ),
          ]),
          const SizedBox(height: 12),
          _chartBlock(theme),
          const SizedBox(height: 16),
          _statsBlock(theme),
          const SizedBox(height: 16),
          _depthBlock(theme),
          const SizedBox(height: 16),
          Row(children: [
            Expanded(child: _watchButton()),
          ]),
          if (_watchFailed) ...[
            const SizedBox(height: 8),
            Text('Watchlist could not be updated. Try again.',
              key: const ValueKey('stock-detail-watchlist-error'),
              style: theme.textTheme.bodySmall?.copyWith(color: scheme.error)),
          ],
          const SizedBox(height: 16),
          MintTickStockDetailMockSections(symbol: stock.tradingSymbol.replaceFirst(RegExp(r'-EQ$'), '')),
          const SizedBox(height: 16),
          Text('Prices are shown only when verified LIVE by MintTick’s backend. Chart bars are '
              'stored historical candles, not live ticks. Information only, not investment advice.',
            style: theme.textTheme.labelSmall?.copyWith(color: scheme.onSurfaceVariant)),
        ],
      )),
    );
  }

  Widget _watchButton() {
    final watched = _watched;
    final enabled = _hasWatchRepo && watched != null && !_watchBusy;
    final label = !_hasWatchRepo
        ? 'Watchlist · unavailable'
        : watched == null
            ? (_watchFailed ? 'Watchlist · unavailable' : 'Loading watchlist…')
            : watched ? 'In watchlist · remove' : 'Add to watchlist';
    return OutlinedButton.icon(
      key: const ValueKey('stock-detail-watchlist-toggle'),
      onPressed: enabled ? _toggleWatch : null,
      icon: Icon(watched == true ? Icons.bookmark : Icons.bookmark_add_outlined),
      label: Text(label),
    );
  }

  Widget _marketBadge(ThemeData theme) {
    final scheme = theme.colorScheme;
    final quote = _quote;
    final live = quote != null && _isFreshLive(quote);
    final label = quote == null
        ? 'Checking…'
        : live
            ? 'NSE · Live'
            : _quoteFailed ? 'Request failed' : _staleLabel(quote);
    final color = live ? scheme.primary : scheme.onSurfaceVariant;
    return Semantics(
      label: 'Market status $label',
      child: Container(
        key: const ValueKey('stock-detail-market-badge'),
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(999),
          border: Border.all(color: color),
        ),
        child: Row(mainAxisSize: MainAxisSize.min, children: [
          Icon(Icons.circle, size: 8, color: color),
          const SizedBox(width: 6),
          Text(label, style: theme.textTheme.labelSmall
              ?.copyWith(color: color, fontWeight: FontWeight.w800)),
        ]),
      ),
    );
  }

  Widget _priceBlock(ThemeData theme) {
    final scheme = theme.colorScheme;
    final quote = _quote;
    final statusLabel = !_hasSnapshotRepo
        ? 'LIVE FEED UNAVAILABLE'
        : _quoteFailed
            ? 'REQUEST FAILED'
            : quote == null
                ? 'LOADING'
                : _staleLabel(quote);
    final ltp = quote != null && _isFreshLive(quote) ? quote.ltp : null;
    final tickAt = quote?.exchangeAt;
    final previousClose = _detail?.previousClose;
    final String changeText;
    if (ltp != null && previousClose != null) {
      final diff = ltp - previousClose;
      final pct = diff / previousClose * 100;
      changeText = '${diff >= 0 ? '+' : ''}${diff.toStringAsFixed(2)} '
          '(${pct >= 0 ? '+' : ''}${pct.toStringAsFixed(2)}%) vs previous close';
    } else if (previousClose == null) {
      changeText = 'Change needs the previous close from day statistics.';
    } else {
      changeText = 'Change appears once a verified LIVE price is available.';
    }
    return Semantics(
      container: true,
      label: ltp == null
          ? 'No verified price. Status ${statusLabel.toLowerCase()}.'
          : 'Price ₹${ltp.toStringAsFixed(2)}. Status live.',
      child: ExcludeSemantics(child: MintTickSurface(child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(children: [
            Expanded(child: Text(
              ltp == null ? 'No verified price' : '₹${ltp.toStringAsFixed(2)}',
              style: theme.textTheme.headlineMedium?.copyWith(fontWeight: FontWeight.w800),
            )),
            Text(statusLabel, style: theme.textTheme.labelSmall?.copyWith(
                color: scheme.onSurfaceVariant, fontWeight: FontWeight.w800)),
          ]),
          const SizedBox(height: 6),
          Text(
            changeText,
            style: theme.textTheme.bodySmall?.copyWith(color: scheme.onSurfaceVariant, height: 1.4),
          ),
          if (tickAt != null) Text(
            'Exchange tick ${_clock(tickAt.toLocal())} · source ${quote?.source ?? '—'}',
            style: theme.textTheme.bodySmall?.copyWith(color: scheme.onSurfaceVariant, height: 1.4),
          ),
        ],
      ))),
    );
  }

  Widget _chartBlock(ThemeData theme) {
    final scheme = theme.colorScheme;
    final candles = _candles;
    final Widget body;
    if (_candlesLoading && candles == null) {
      body = const Center(child: CircularProgressIndicator());
    } else if (_candlesFailed) {
      body = Center(child: Column(mainAxisSize: MainAxisSize.min, children: [
        const Text('Stored candles could not be loaded. No chart is drawn.'),
        const SizedBox(height: 8),
        TextButton(onPressed: _loadCandles, child: const Text('Retry')),
      ]));
    } else if (!_hasCandleRepo) {
      body = const Center(child: Text('Chart not available on this client.'));
    } else if (candles == null || candles.isEmpty) {
      body = const Center(child: Padding(
        padding: EdgeInsets.all(12),
        child: Text('No stored candles for this range yet. Historical bars appear after '
            'MintTick has stored them.', textAlign: TextAlign.center),
      ));
    } else if (candles.length < 2) {
      body = Center(child: Text('Only one stored bar for this range. Not enough to draw a line.',
          textAlign: TextAlign.center, style: theme.textTheme.bodySmall));
    } else {
      body = CustomPaint(
        painter: _LinePainter(
          [for (final c in candles) c.close.toDouble()],
          scheme.primary, scheme.outlineVariant),
        child: const SizedBox.expand(),
      );
    }
    final last = candles == null || candles.isEmpty ? null : candles.last;
    return MintTickSurface(child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        SizedBox(height: 200, child: Semantics(
          label: candles == null || candles.isEmpty
              ? 'Stored candle chart: no data for this range'
              : 'Stored candle chart with ${candles.length} bars',
          child: body,
        )),
        const SizedBox(height: 8),
        Text(
          last == null
              ? 'Source: stored historical candles'
              : 'Stored candles · ${last.source} · fetched ${_clock(last.fetchedAt.toLocal())}',
          style: theme.textTheme.labelSmall?.copyWith(color: scheme.onSurfaceVariant),
        ),
      ],
    ));
  }

  Widget _statsBlock(ThemeData theme) {
    final scheme = theme.colorScheme;
    final detail = _detail;
    String value(num? v) => v == null ? '—' : '₹${v.toStringAsFixed(2)}';
    final tiles = [
      ('Open', value(detail?.open)),
      ('High', value(detail?.high)),
      ('Low', value(detail?.low)),
      ('Previous close', value(detail?.previousClose)),
      ('Volume', detail == null ? '—' : '${detail.volume}'),
      ('52W high', value(detail?.week52High)),
      ('52W low', value(detail?.week52Low)),
      ('VWAP', value(detail?.averagePrice)),
      ('Exchange time', detail == null ? '—' : _clock(detail.exchangeAt.toLocal())),
    ];
    final String status;
    if (!_hasDetailRepo) {
      status = 'Day statistics are not available on this client.';
    } else if (_detailFailed) {
      status = 'Day statistics could not be loaded. Values are not estimated.';
    } else if (detail == null) {
      status = 'Loading day statistics…';
    } else {
      status = 'Source ${detail.source} · fetched ${_clock(detail.fetchedAt.toLocal())} · '
          'provider day values, not live ticks.';
    }
    return MintTickSurface(child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(children: [
          Expanded(child: Text('Day statistics', style: theme.textTheme.titleSmall
              ?.copyWith(fontWeight: FontWeight.w800))),
          if (_hasDetailRepo) TextButton(
            key: const ValueKey('stock-detail-day-refresh'),
            onPressed: _detailLoading ? null : _loadDetail,
            child: Text(_detailLoading ? 'Loading…' : 'Refresh'),
          ),
        ]),
        const SizedBox(height: 10),
        Wrap(spacing: 12, runSpacing: 12, children: [
          for (final (label, text) in tiles)
            SizedBox(width: 150, child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(label, style: theme.textTheme.labelSmall
                    ?.copyWith(color: scheme.onSurfaceVariant)),
                Text(text, style: theme.textTheme.bodyMedium
                    ?.copyWith(fontWeight: FontWeight.w700)),
              ],
            )),
        ]),
        const SizedBox(height: 12),
        Text(status, style: theme.textTheme.bodySmall?.copyWith(color: scheme.onSurfaceVariant)),
        const SizedBox(height: 4),
        Text('A dash means the provider did not send that value for this stock.',
          style: theme.textTheme.bodySmall?.copyWith(color: scheme.onSurfaceVariant)),
      ],
    ));
  }

  Widget _depthBlock(ThemeData theme) {
    final scheme = theme.colorScheme;
    final detail = _detail;
    if (!_hasDetailRepo) return const SizedBox.shrink();
    final bids = detail?.bids ?? const <CustomerDepthLevel>[];
    final asks = detail?.asks ?? const <CustomerDepthLevel>[];
    final buy = detail?.totalBuyQuantity ?? 0;
    final sell = detail?.totalSellQuantity ?? 0;
    final totalQty = buy + sell;
    final buyShare = totalQty == 0 ? null : buy / totalQty * 100;
    String qty(int v) => v.toString().replaceAllMapped(
        RegExp(r'\B(?=(\d{3})+(?!\d))'), (m) => ',');
    String price(num v) => v.toStringAsFixed(2);
    return MintTickSurface(child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(children: [
          Expanded(child: Text('Market depth (Top 5)', style: theme.textTheme.titleSmall
              ?.copyWith(fontWeight: FontWeight.w800))),
          if (detail != null) Text('Total buy ${qty(buy)} · sell ${qty(sell)}',
            key: const ValueKey('stock-detail-depth-totals'),
            style: theme.textTheme.labelSmall?.copyWith(color: scheme.onSurfaceVariant)),
        ]),
        const SizedBox(height: 10),
        if (detail == null)
          Text(_detailFailed ? 'Market depth could not be loaded.' : 'Loading market depth…',
            style: theme.textTheme.bodySmall?.copyWith(color: scheme.onSurfaceVariant))
        else if (bids.isEmpty && asks.isEmpty)
          Text('No depth is published right now. Depth appears during market hours.',
            style: theme.textTheme.bodySmall?.copyWith(color: scheme.onSurfaceVariant))
        else ...[
          if (buyShare != null) ...[
            ClipRRect(
              borderRadius: BorderRadius.circular(6),
              child: Row(children: [
                Expanded(flex: (buyShare * 10).round().clamp(1, 999),
                  child: Container(height: 22, color: scheme.primary,
                    alignment: Alignment.centerLeft, padding: const EdgeInsets.only(left: 8),
                    child: Text('Buy ${buyShare.toStringAsFixed(1)}%',
                      style: theme.textTheme.labelSmall?.copyWith(
                        color: scheme.onPrimary, fontWeight: FontWeight.w800)))),
                Expanded(flex: ((100 - buyShare) * 10).round().clamp(1, 999),
                  child: Container(height: 22, color: scheme.error,
                    alignment: Alignment.centerRight, padding: const EdgeInsets.only(right: 8),
                    child: Text('Sell ${(100 - buyShare).toStringAsFixed(1)}%',
                      style: theme.textTheme.labelSmall?.copyWith(
                        color: scheme.onError, fontWeight: FontWeight.w800)))),
              ]),
            ),
            const SizedBox(height: 10),
          ],
          Row(children: [
            Expanded(child: _depthSide(theme, 'Bid (₹)', 'Qty', bids, qty, price, scheme.primary)),
            const SizedBox(width: 12),
            Expanded(child: _depthSide(theme, 'Ask (₹)', 'Qty', asks, qty, price, scheme.error)),
          ]),
          const SizedBox(height: 8),
          Text('Provider depth snapshot, not a live order book.',
            style: theme.textTheme.labelSmall?.copyWith(color: scheme.onSurfaceVariant)),
        ],
      ],
    ));
  }

  Widget _depthSide(ThemeData theme, String head, String qtyHead, List<CustomerDepthLevel> levels,
      String Function(int) qty, String Function(num) price, Color color) {
    final scheme = theme.colorScheme;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(children: [
          Expanded(child: Text(head, style: theme.textTheme.labelSmall
              ?.copyWith(color: color, fontWeight: FontWeight.w800))),
          Text(qtyHead, style: theme.textTheme.labelSmall
              ?.copyWith(color: scheme.onSurfaceVariant)),
        ]),
        const Divider(height: 8),
        for (final level in levels)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 3),
            child: Row(children: [
              Expanded(child: Text(price(level.price), style: theme.textTheme.bodySmall
                  ?.copyWith(color: color, fontWeight: FontWeight.w700))),
              Text(qty(level.quantity), style: theme.textTheme.bodySmall),
            ]),
          ),
      ],
    );
  }
}

bool _isFreshLive(CustomerLiveQuote quote) {
  if (quote.status != 'LIVE' || quote.exchangeAt == null || quote.ltp == null) return false;
  // The backend only marks LIVE for trades in the current session; a quiet stock keeps its last trade.
  return DateTime.now().toUtc().difference(quote.exchangeAt!.toUtc()) >= const Duration(seconds: -5);
}

String _staleLabel(CustomerLiveQuote quote) {
  if (quote.status == 'LIVE') return _isFreshLive(quote) ? 'LIVE' : 'STALE';
  return switch (quote.status) {
    'STALE' => 'STALE',
    'CONNECTING' => 'CONNECTING',
    'RECONNECTING' => 'RECONNECTING',
    'MARKET_CLOSED' => 'CLOSED',
    'BROKER_CONNECTION_REQUIRED' => 'CONNECT BROKER',
    _ => 'NO FEED',
  };
}

String _istDate(DateTime moment) {
  final ist = moment.toUtc().add(const Duration(hours: 5, minutes: 30));
  return '${ist.year.toString().padLeft(4, '0')}-${ist.month.toString().padLeft(2, '0')}-'
      '${ist.day.toString().padLeft(2, '0')}';
}

String _clock(DateTime time) =>
    '${time.hour.toString().padLeft(2, '0')}:${time.minute.toString().padLeft(2, '0')}:'
    '${time.second.toString().padLeft(2, '0')}';


class _LinePainter extends CustomPainter {
  _LinePainter(this.values, this.line, this.grid);
  final List<double> values;
  final Color line;
  final Color grid;

  @override
  void paint(Canvas canvas, Size size) {
    final gridPaint = Paint()..color = grid..strokeWidth = 1;
    for (var i = 1; i < 4; i++) {
      final y = size.height * i / 4;
      canvas.drawLine(Offset(0, y), Offset(size.width, y), gridPaint);
    }
    final low = values.reduce((a, b) => math.min(a, b));
    final high = values.reduce((a, b) => math.max(a, b));
    final span = high - low == 0 ? 1.0 : high - low;
    final path = Path();
    for (var i = 0; i < values.length; i++) {
      final x = size.width * i / (values.length - 1);
      final y = size.height - (values[i] - low) / span * size.height;
      if (i == 0) {
        path.moveTo(x, y);
      } else {
        path.lineTo(x, y);
      }
    }
    canvas.drawPath(path, Paint()
      ..color = line
      ..style = PaintingStyle.stroke
      ..strokeWidth = 2
      ..strokeJoin = StrokeJoin.round);
  }

  @override
  bool shouldRepaint(_LinePainter old) =>
      old.values != values || old.line != line || old.grid != grid;
}

import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../../../data/instrument_repository.dart';
import '../data/customer_market_repository.dart';
import 'minttick_customer_widgets.dart';
import 'minttick_equity_avatar.dart';
import 'minttick_interactive_candle_chart.dart';

const _quotePoll = Duration(seconds: 2);
const _verifiedCandlePoll = Duration(seconds: 30);

/// Chart timeframes map to backend candle intervals. [lookbackDays] bounds the stored window.
const _timeframes = [
  (label: '5m', interval: 'FIVE_MINUTE', lookbackDays: 5),
  (label: '1H', interval: 'ONE_HOUR', lookbackDays: 60),
  (label: '1D', interval: 'ONE_DAY', lookbackDays: 365),
];

/// Daily candle interval is distinct from chart lookback; the owner archive
/// currently exports at most 365 calendar days, not five or twenty years.
const _dailyRanges = [
  (label: '1M', days: 30),
  (label: '3M', days: 90),
  (label: '6M', days: 180),
  (label: '1Y', days: 365),
  (label: '5Y', days: 1826),
  (label: 'MAX', days: 9000),
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
  int _dailyRange = 3;
  int? _selectedCandle;
  List<CustomerCandle>? _candles;
  bool _candlesLoading = false;
  bool _candlesFailed = false;
  bool _candleRefreshFailed = false;
  bool _candlePageTruncated = false;
  int _candleRequest = 0;
  Timer? _candleTimer;

  CustomerLiveQuote? _quote;
  bool _quoteFailed = false;
  bool _quoteBusy = false;
  Timer? _quoteTimer;

  CustomerCompanyProfile? _company;
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
  bool get _hasCompanyRepo => widget.repository is CustomerCompanyProfileRepository;
  bool get _hasWatchRepo => widget.repository is CustomerWatchlistRepository;

  @override
  void initState() {
    super.initState();
    _loadCandles();
    if (_hasDetailRepo) _loadDetail();
    if (_hasCompanyRepo) _loadCompany();
    if (_hasWatchRepo) _loadWatchlist();
    if (_hasSnapshotRepo) {
      _pollQuote();
      _quoteTimer = Timer.periodic(_quotePoll, (_) => _pollQuote());
    }
    if (_hasCandleRepo && _hasSnapshotRepo) {
      _candleTimer = Timer.periodic(_verifiedCandlePoll, (_) {
        final quote = _quote;
        if (_timeframe < 2 && quote != null && _isFreshLive(quote) &&
            !_candlesLoading) {
          // Only refresh actual provider-stored OHLCV from the server. Never
          // build candles from the LTP snapshot or a clock tick.
          _loadCandles(refresh: true);
        }
      });
    }
  }

  @override
  void dispose() {
    _quoteTimer?.cancel();
    _candleTimer?.cancel();
    super.dispose();
  }

  Future<void> _loadCandles({bool refresh = false}) async {
    final CustomerCandleRepository? repo = widget.repository is CustomerCandleRepository
        ? widget.repository as CustomerCandleRepository
        : null;
    if (repo == null || (refresh && (_candlesLoading || _candles == null))) return;
    final request = ++_candleRequest;
    final timeframe = _timeframes[_timeframe];
    final now = DateTime.now();
    final previousSelection = _selectedCandle != null && _candles != null &&
            _selectedCandle! >= 0 && _selectedCandle! < _candles!.length
        ? _candles![_selectedCandle!].candleTime.toUtc() : null;
    setState(() {
      _candlesLoading = true;
      if (!refresh) {
        _candlesFailed = false;
        _candleRefreshFailed = false;
      }
    });
    try {
      final page = await repo.candles(
        instrumentId: widget.stock.id,
        interval: _timeframe == 2 && _dailyRange >= 4
            ? 'ONE_MONTH' : timeframe.interval,
        fromDate: _istDate(now.subtract(Duration(
          days: _timeframe == 2 ? _dailyRanges[_dailyRange].days : timeframe.lookbackDays))),
        toDate: _istDate(now),
      );
      if (!mounted || request != _candleRequest) return;
      final sorted = [...page.items]
        ..sort((a, b) => a.candleTime.compareTo(b.candleTime));
      // On a periodic refresh keep the same tapped exchange bar, not the old
      // numeric index which can shift when the server rolls its bounded page.
      final selectedIndex = refresh && previousSelection != null
          ? sorted.indexWhere((c) => c.candleTime.toUtc() == previousSelection)
          : -1;
      setState(() {
        _candles = sorted;
        _selectedCandle = selectedIndex >= 0 ? selectedIndex : null;
        _candlePageTruncated = page.hasNext;
        _candlesLoading = false;
        _candlesFailed = false;
        _candleRefreshFailed = false;
      });
    } catch (_) {
      if (!mounted || request != _candleRequest) return;
      setState(() {
        // A temporary provider or account failure never erases valid bars
        // previously displayed; mark the refresh as failed instead.
        if (!refresh) _candles = null;
        _candlesLoading = false;
        if (refresh) {
          _candleRefreshFailed = true;
        } else {
          _candlesFailed = true;
        }
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

  Future<void> _loadCompany() async {
    final CustomerCompanyProfileRepository? repo =
        widget.repository is CustomerCompanyProfileRepository
            ? widget.repository as CustomerCompanyProfileRepository : null;
    if (repo == null) return;
    try {
      final profile = await repo.companyProfile(widget.stock.id);
      if (!mounted || profile.tradingSymbol != widget.stock.tradingSymbol ||
          profile.exchange != widget.stock.exchange) return;
      setState(() => _company = profile);
    } catch (_) {
      // Keep trustworthy catalogue identity visible while classified sources
      // or a newer read-only company endpoint are unavailable.
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
    setState(() {
      _timeframe = index;
      _candles = null;
      _selectedCandle = null;
    });
    _loadCandles();
  }

  void _selectDailyRange(int index) {
    if (index == _dailyRange) return;
    setState(() {
      _dailyRange = index;
      _candles = null;
      _selectedCandle = null;
    });
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
            MintTickEquityAvatar(tradingSymbol: stock.tradingSymbol, companyName: stock.name, size: 44),
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
          SingleChildScrollView(
            scrollDirection: Axis.horizontal,
            child: Row(children: [
              for (final (index, tf) in _timeframes.indexed) ...[
                ChoiceChip(
                  key: ValueKey('stock-detail-timeframe-${tf.label}'),
                  label: Text(tf.label),
                  selected: index == _timeframe,
                  onSelected: (_) => _selectTimeframe(index),
                  visualDensity: VisualDensity.compact,
                ),
                const SizedBox(width: 6),
              ],
            ]),
          ),
          if (_timeframe == 2) ...[
            const SizedBox(height: 6),
            SingleChildScrollView(
              scrollDirection: Axis.horizontal,
              child: Row(children: [
                for (final (index, range) in _dailyRanges.indexed) ...[
                  ChoiceChip(
                    key: ValueKey('stock-detail-range-${range.label}'),
                    label: Text(range.label),
                    selected: index == _dailyRange,
                    onSelected: (_) => _selectDailyRange(index),
                    visualDensity: VisualDensity.compact,
                  ),
                  const SizedBox(width: 6),
                ],
              ]),
            ),
          ],
          const SizedBox(height: 12),
          _chartBlock(theme),
          const SizedBox(height: 12),
          _companyBlock(theme),
          const SizedBox(height: 12),
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
    final isStored = quote?.status == 'LAST_CLOSE';
    final ltp = quote != null && (isStored || _isFreshLive(quote)) ? quote.ltp : null;
    final tickAt = quote?.exchangeAt;
    final previousClose = _detail?.previousClose;
    final String changeText;
    if (ltp != null && previousClose != null) {
      final diff = ltp - previousClose;
      final pct = diff / previousClose * 100;
      changeText = '${diff >= 0 ? '+' : ''}${diff.toStringAsFixed(2)} '
          '(${pct >= 0 ? '+' : ''}${pct.toStringAsFixed(2)}%) · vs prev. close';
    } else if (previousClose == null) {
      changeText = 'Change needs the previous close from day statistics.';
    } else {
      changeText = 'Change appears once a verified LIVE price is available.';
    }
    return Semantics(
      container: true,
      label: ltp == null
          ? 'No verified price. Status ${statusLabel.toLowerCase()}.'
          : 'Price ₹${ltp.toStringAsFixed(2)}. Status ${isStored ? 'stored last trade, not live' : 'live'}.',
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
            style: theme.textTheme.bodySmall?.copyWith(
              color: ltp != null && previousClose != null
                  ? (ltp >= previousClose ? scheme.primary : scheme.error)
                  : scheme.onSurfaceVariant,
              height: 1.3),
          ),
          if (tickAt != null) Tooltip(
            message: 'Exchange source: ${quote?.source ?? '—'}',
            child: Text(
              'Exchange trade · ${_istDate(tickAt)} '
              '${_clock(tickAt.toUtc().add(const Duration(hours: 5, minutes: 30)))} IST',
              style: theme.textTheme.bodySmall?.copyWith(
                  color: scheme.onSurfaceVariant, height: 1.3),
            ),
          ),
        ],
      ))),
    );
  }

  Widget _companyBlock(ThemeData theme) {
    final scheme = theme.colorScheme;
    final stock = widget.stock;
    final company = _company;
    final facts = <(String, String)>[
      if (company?.industry != null) ('Industry', company!.industry!),
      if (company?.sizeSegment != null)
        ('Index size', company!.sizeSegment!),
      ('Symbol', stock.tradingSymbol.replaceFirst(RegExp(r'-EQ$'), '')),
      if (stock.exchange.trim().isNotEmpty) ('Exchange', stock.exchange),
      if (stock.exchangeSegment.trim().isNotEmpty) ('Segment', stock.exchangeSegment),
      if (stock.instrumentType.trim().isNotEmpty)
        ('Security', stock.instrumentType == 'EQ' ? 'Equity' : stock.instrumentType),
      if (stock.lotSize > 0) ('Lot size', '${stock.lotSize}'),
      if (stock.tickSize != null && stock.tickSize! > 0)
        ('Tick size', '₹${stock.tickSize}'),
    ];
    return MintTickSurface(child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('Company information', key: const ValueKey('stock-detail-company-info'),
          style: theme.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w800)),
        if (stock.name.trim().isNotEmpty) ...[
          const SizedBox(height: 5),
          Text(stock.name, style: theme.textTheme.bodyMedium
              ?.copyWith(color: scheme.onSurfaceVariant)),
        ],
        const SizedBox(height: 12),
        LayoutBuilder(builder: (context, constraints) => Wrap(
          spacing: 12, runSpacing: 12,
          children: [
            for (final (label, value) in facts)
              SizedBox(width: (constraints.maxWidth - 12) / 2,
                child: Column(crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(label, style: theme.textTheme.labelSmall
                        ?.copyWith(color: scheme.onSurfaceVariant)),
                    const SizedBox(height: 2),
                    Text(value, maxLines: 2, overflow: TextOverflow.ellipsis,
                      style: theme.textTheme.bodyMedium?.copyWith(fontWeight: FontWeight.w700)),
                  ],
                ),
              ),
          ],
        )),
        if (company != null && company.indexMemberships.isNotEmpty) ...[
          const SizedBox(height: 10),
          Row(children: [
            Expanded(child: Wrap(spacing: 6, runSpacing: 4, children: [
              for (final membership in company.indexMemberships)
                Chip(
                  key: ValueKey('company-index-${membership.indexCode}'),
                  label: Text(membership.indexCode),
                  visualDensity: VisualDensity.compact,
                ),
            ])),
            IconButton(
              key: const ValueKey('company-source-details'),
              tooltip: 'Company classification sources',
              icon: const Icon(Icons.info_outline, size: 18),
              onPressed: () => showModalBottomSheet<void>(
                context: context,
                builder: (sheetContext) => SafeArea(child: Padding(
                  padding: const EdgeInsets.all(20),
                  child: ListView(shrinkWrap: true, children: [
                    Text('Official index membership',
                      style: theme.textTheme.titleMedium),
                    const SizedBox(height: 10),
                    for (final membership in company.indexMemberships)
                      ListTile(dense: true,
                        title: Text(membership.indexCode),
                        subtitle: Text(
                          '${membership.source} · ${_istDate(membership.sourceAsOf)}')),
                  ]),
                )),
              ),
            ),
          ]),
        ],
      ],
    ));
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
        child: Text('No verified stored candles for this interval and range. '
            '5m and 1H require separate intraday collection; daily history '
            'cannot be converted into intraday bars.', textAlign: TextAlign.center),
      ));
    } else if (candles.length < 2) {
      body = Center(child: Text('Only one stored bar for this range. Not enough to draw a line.',
          textAlign: TextAlign.center, style: theme.textTheme.bodySmall));
    } else {
      final quote = _quote;
      body = MintTickInteractiveCandleChart(
        key: ValueKey('chart-${widget.stock.id}-$_timeframe-$_dailyRange'),
        bars: candles,
        selectedIndex: _selectedCandle,
        onSelect: (index) => setState(() => _selectedCandle = index),
        lastTradedPrice: quote != null &&
                (quote.status == 'LAST_CLOSE' || _isFreshLive(quote))
            ? quote.ltp?.toDouble() : null,
        liveQuote: quote != null && _isFreshLive(quote),
      );
    }
    final last = candles == null || candles.isEmpty ? null : candles.last;
    final tradeAt = _quote?.exchangeAt;
    final alignedTrade = tradeAt != null && last != null &&
        _istDate(tradeAt) == _istDate(last.candleTime);
    final intervalLag = last != null && tradeAt != null && _timeframe < 2 &&
        tradeAt.toUtc().difference(last.candleTime.toUtc()) >
            (_timeframe == 0 ? const Duration(minutes: 15)
                : const Duration(hours: 3));
    final chartBehindTrade = last != null && tradeAt != null &&
        (_timeframe == 2 ? !alignedTrade : intervalLag);
    final selected = candles != null && candles.isNotEmpty
        ? candles[math.max(0, math.min(candles.length - 1, _selectedCandle ?? candles.length - 1))]
        : null;

    return MintTickSurface(child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        SizedBox(height: 300, child: Semantics(
          label: candles == null || candles.isEmpty
              ? 'Stored candle chart: no data for this range'
              : 'Stored candle chart with ${candles.length} bars',
          child: body,
        )),
        if (selected != null) ...[
          const SizedBox(height: 8),
          Text(_istDate(selected.candleTime),
            style: theme.textTheme.labelSmall?.copyWith(color: scheme.onSurfaceVariant)),
          const SizedBox(height: 5),
          Wrap(key: const ValueKey('stock-detail-chart-ohlc'),
            spacing: 14, runSpacing: 6, children: [
              _ohlcValue(theme, 'O', selected.open),
              _ohlcValue(theme, 'H', selected.high),
              _ohlcValue(theme, 'L', selected.low),
              _ohlcValue(theme, 'C', selected.close),
            ]),
          const SizedBox(height: 4),
          Text('Vol ${_compactQuantity(selected.volume)}',
            key: const ValueKey('stock-detail-chart-volume'),
            style: theme.textTheme.labelSmall?.copyWith(color: scheme.onSurfaceVariant)),
        ],
        Row(children: [
          Expanded(child: Text(
            last == null ? 'No verified history' : 'Historical · ${candles!.length} bars',
            maxLines: 1, overflow: TextOverflow.ellipsis,
            style: theme.textTheme.labelSmall?.copyWith(color: scheme.onSurfaceVariant),
          )),
          if (chartBehindTrade || _candlePageTruncated || _candleRefreshFailed)
            Tooltip(
              message: 'Historical data needs attention. Open chart details.',
              child: Icon(Icons.warning_amber_rounded, size: 18, color: scheme.error),
            ),
          IconButton(
            key: const ValueKey('stock-chart-provenance'),
            tooltip: 'Chart data details',
            visualDensity: VisualDensity.compact,
            icon: const Icon(Icons.info_outline, size: 18),
            onPressed: last == null ? null : () => showModalBottomSheet<void>(
              context: context, isScrollControlled: true,
              builder: (sheetContext) => SafeArea(child: SizedBox(
                height: MediaQuery.sizeOf(sheetContext).height * 0.68,
                child: Padding(
                  padding: const EdgeInsets.fromLTRB(20, 16, 20, 20),
                  child: ListView(children: [
                    Text('Chart data details', style: theme.textTheme.titleMedium
                        ?.copyWith(fontWeight: FontWeight.w800)),
                    const SizedBox(height: 14),
                    Text('Historical · ${candles!.length} bars'),
                    const SizedBox(height: 8),
                    if (_timeframe == 2 && _dailyRange >= 4)
                      Text('Monthly OHLCV aggregated from verified stored daily candles. '
                          'Missing historical sessions remain unfilled.',
                        key: const ValueKey('stock-detail-monthly-disclaimer')),
                    Text('Last stored bar · ${_istDate(last.candleTime)} '
                        '${_clock(last.candleTime.toUtc().add(const Duration(hours: 5, minutes: 30)))} IST',
                      key: const ValueKey('stock-chart-last-bar-time')),
                    if (tradeAt != null) Text('Latest exchange trade · ${_istDate(tradeAt)} '
                        '${_clock(tradeAt.toUtc().add(const Duration(hours: 5, minutes: 30)))} IST',
                      key: const ValueKey('stock-chart-last-trade-time')),
                    if (chartBehindTrade) Text(
                      'Historical chart is behind the latest exchange trade. '
                      'No missing candles have been invented.',
                      key: const ValueKey('stock-chart-coverage-warning'),
                      style: TextStyle(color: scheme.error)),
                    if (_candlePageTruncated) Text(
                      'History window is truncated by the source page limit.',
                      key: const ValueKey('stock-chart-truncated-warning'),
                      style: TextStyle(color: scheme.error)),
                    if (_candleRefreshFailed) Text(
                      'Candle refresh failed · displaying previously verified history',
                      key: const ValueKey('stock-chart-refresh-warning'),
                      style: TextStyle(color: scheme.error)),
                    const SizedBox(height: 12),
                    SelectableText('Source: ${last.source}'),
                    SelectableText('Fetched: ${last.fetchedAt.toLocal()}'),
                    const SizedBox(height: 8),
                    const Text('Stored candles are historical records, not live ticks. '
                        'Missing sessions are never filled or interpolated.'),
                  ]),
                ),
              )),
            ),
          ),
        ]),
      ],
    ));
  }

  Widget _ohlcValue(ThemeData theme, String label, num value) {
    final scheme = theme.colorScheme;
    return RichText(text: TextSpan(children: [
      TextSpan(text: '$label ', style: theme.textTheme.labelSmall
          ?.copyWith(color: scheme.onSurfaceVariant)),
      TextSpan(text: '₹${value.toStringAsFixed(2)}', style: theme.textTheme.labelSmall
          ?.copyWith(color: scheme.onSurface, fontWeight: FontWeight.w700)),
    ]));
  }

  String _compactQuantity(int count) {
    if (count >= 10000000) return '${(count / 10000000).toStringAsFixed(2)}Cr';
    if (count >= 100000) return '${(count / 100000).toStringAsFixed(2)}L';
    if (count >= 1000) return '${(count / 1000).toStringAsFixed(1)}K';
    return '$count';
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
      status = '';
    }
    return MintTickSurface(child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(children: [
          Expanded(child: Text('Key statistics', style: theme.textTheme.titleSmall
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
            SizedBox(width: 142, child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(label, style: theme.textTheme.labelSmall
                    ?.copyWith(color: scheme.onSurfaceVariant)),
                Text(text, style: theme.textTheme.bodyMedium
                    ?.copyWith(fontWeight: FontWeight.w700)),
              ],
            )),
        ]),
        if (status.isNotEmpty) ...[
          const SizedBox(height: 8),
          Text(status, style: theme.textTheme.bodySmall?.copyWith(color: scheme.onSurfaceVariant)),
        ],
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
  final age = DateTime.now().toUtc().difference(quote.exchangeAt!.toUtc());
  return age >= const Duration(seconds: -5) && age <= const Duration(minutes: 2);
}

String _staleLabel(CustomerLiveQuote quote) {
  if (quote.status == 'LIVE') return _isFreshLive(quote) ? 'LIVE' : 'STALE';
  return switch (quote.status) {
    'STALE' => 'STALE',
    'LAST_CLOSE' => 'LAST TRADED',
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

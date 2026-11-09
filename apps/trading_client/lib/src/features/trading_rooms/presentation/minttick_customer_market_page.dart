import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../../data/instrument_repository.dart';
import '../data/customer_market_repository.dart';
import 'minttick_customer_widgets.dart';
import 'minttick_equity_avatar.dart';
import 'minttick_market_preview.dart';
import 'minttick_stock_detail_page.dart';

const _maxSnapshotRows = 30;
const _activePoll = Duration(seconds: 3);
const _degradedPoll = Duration(seconds: 15);
const _closedPoll = Duration(seconds: 30);
const _failedPoll = Duration(seconds: 15);

const _overviewPoll = Duration(seconds: 30);
const _tabLabels = ['Overview', 'Movers', 'Sectors', 'Explore'];

/// Markets shell with four tabs. Only Overview and Explore show real data;
/// Movers and Sectors state what is missing instead of inventing values.
/// A price is shown only when a row's backend status is LIVE and fresh.
class MintTickCustomerMarketPage extends StatefulWidget {
  const MintTickCustomerMarketPage({
    required this.repository, this.onConnectBroker, super.key,
  });
  final CustomerMarketRepository repository;
  final VoidCallback? onConnectBroker;

  @override
  State<MintTickCustomerMarketPage> createState() => _MintTickCustomerMarketPageState();
}

class _MintTickCustomerMarketPageState extends State<MintTickCustomerMarketPage>
    with WidgetsBindingObserver, SingleTickerProviderStateMixin {
  final TextEditingController _query = TextEditingController();
  late final TabController _tabs = TabController(length: _tabLabels.length, vsync: this);
  final Map<int, CustomerLiveQuote> _rows = {};
  InstrumentCatalogueStatus? _catalogue;
  List<InstrumentRecord> _stocks = const [];
  bool _hasNext = false;
  int _totalStocks = 0;
  int _nextPage = 1;
  bool _loadingMore = false;
  String? _exchange;
  String? _error;
  bool _loading = false;
  bool _appActive = true;
  bool _polling = false;
  bool _feedFailed = false;
  DateTime? _generatedAt;
  Timer? _pollTimer;
  int _version = 0;
  CustomerMarketOverview? _overview;
  String? _overviewError;
  bool _overviewLoading = false;
  Timer? _overviewTimer;

  void _openDetail(InstrumentRecord stock) {
    Navigator.of(context).push(MaterialPageRoute<void>(
      builder: (_) => MintTickStockDetailPage(stock: stock, repository: widget.repository),
    ));
  }

  /// Movers carry no instrument id, so resolve the exact symbol through the active catalogue.
  Future<void> _openMover(CustomerMover mover) async {
    void notify(String message) {
      if (!mounted) return;
      ScaffoldMessenger.maybeOf(context)?.showSnackBar(SnackBar(content: Text(message)));
    }
    try {
      final page = await widget.repository.search(query: mover.tradingSymbol);
      if (!mounted) return;
      final matches = page.content.where((s) => s.tradingSymbol == mover.tradingSymbol);
      if (matches.isEmpty) {
        notify('${mover.tradingSymbol} is not in the active instrument catalogue, so its detail is unavailable.');
        return;
      }
      _openDetail(matches.first);
    } catch (_) {
      notify('Could not open ${mover.tradingSymbol}. Try again.');
    }
  }

  bool get _canPoll => widget.repository is CustomerSnapshotMarketRepository;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _tabs.addListener(_onTabChanged);
    _load();
    if (widget.repository is CustomerOverviewRepository) {
      unawaited(_loadOverview());
      _overviewTimer = Timer.periodic(_overviewPoll, (_) => unawaited(_loadOverview()));
    }
  }

  @override
  void dispose() {
    _version++;
    WidgetsBinding.instance.removeObserver(this);
    _pollTimer?.cancel();
    _overviewTimer?.cancel();
    _tabs.removeListener(_onTabChanged);
    _tabs.dispose();
    _query.dispose();
    super.dispose();
  }

  void _onTabChanged() {
    if (!_tabs.indexIsChanging) setState(() {});
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    final active = state == AppLifecycleState.resumed;
    if (_appActive == active) return;
    _appActive = active;
    _pollTimer?.cancel();
    _pollTimer = null;
    if (!active) {
      // Hidden app must never keep a LIVE price or index quote on screen.
      setState(() {
        _rows.clear();
        _overview = null;
      });
    } else {
      _schedulePoll(Duration.zero);
      unawaited(_loadOverview());
    }
  }

  Future<void> _loadOverview() async {
    final repo = widget.repository;
    if (repo is! CustomerOverviewRepository) return;
    if (_overviewLoading || !_appActive || !mounted) return;
    _overviewLoading = true;
    try {
      final overview = await (repo as CustomerOverviewRepository).overview();
      if (!mounted || !_appActive) return;
      setState(() {
        _overview = overview;
        _overviewError = null;
      });
    } catch (_) {
      if (mounted && _appActive) {
        setState(() {
          _overview = null;
          _overviewError = 'The Nifty 50 overview is unavailable right now. '
              'Retrying automatically; no index or breadth values are shown.';
        });
      }
    } finally {
      _overviewLoading = false;
    }
  }

  String? get _queryText => _query.text.trim().isEmpty ? null : _query.text.trim();

  Future<void> _load() async {
    final version = ++_version;
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final result = await Future.wait<Object>([
        if (_catalogue == null) widget.repository.catalogueStatus(),
        widget.repository.search(query: _queryText, exchange: _exchange),
      ]);
      if (!mounted || version != _version) return;
      setState(() {
        if (result.length == 2) _catalogue = result.first as InstrumentCatalogueStatus;
        final page = result.last as InstrumentSearchPage;
        _stocks = page.content;
        _hasNext = page.hasNext;
        _totalStocks = page.totalElements;
        _nextPage = page.page + 1;
      });
      _schedulePoll(Duration.zero);
    } catch (_) {
      if (mounted && version == _version) {
        setState(() {
          _stocks = const [];
          _hasNext = false;
          _totalStocks = 0;
          _error = 'Unable to load the customer market catalogue. '
              'Check the MintTick API and try again.';
        });
      }
    } finally {
      if (mounted && version == _version) setState(() => _loading = false);
    }
  }

  Future<void> _loadMore() async {
    if (_loadingMore || _loading || !_hasNext ||
        widget.repository is! CustomerPaginatedMarketRepository) {
      return;
    }
    final version = _version;
    setState(() => _loadingMore = true);
    try {
      final page = await (widget.repository as CustomerPaginatedMarketRepository)
          .searchPage(query: _queryText, exchange: _exchange, page: _nextPage);
      if (!mounted || version != _version) return;
      setState(() {
        final existing = _stocks.map((e) => e.id).toSet();
        _stocks = [
          ..._stocks,
          ...page.content.where((stock) => existing.add(stock.id)),
        ];
        _hasNext = page.hasNext;
        _totalStocks = page.totalElements;
        _nextPage = page.page + 1;
      });
      _schedulePoll(Duration.zero);
    } catch (_) {
      if (mounted && version == _version) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Could not load more equities. Retry.')));
      }
    } finally {
      if (mounted && version == _version) setState(() => _loadingMore = false);
    }
  }

  void _schedulePoll(Duration delay) {
    _pollTimer?.cancel();
    _pollTimer = null;
    if (!mounted || !_appActive || !_canPoll) return;
    _pollTimer = Timer(delay, () => unawaited(_pollOnce()));
  }

  Future<void> _pollOnce() async {
    _pollTimer = null;
    final CustomerSnapshotMarketRepository? repo =
        widget.repository is CustomerSnapshotMarketRepository
            ? widget.repository as CustomerSnapshotMarketRepository
            : null;
    if (_polling || !mounted || !_appActive || repo == null) {
      return;
    }
    final stocks = _stocks;
    if (stocks.isEmpty) return;
    _polling = true;
    var failed = false;
    DateTime? generatedAt;
    try {
      for (var start = 0; start < stocks.length; start += _maxSnapshotRows) {
        final batch = stocks.sublist(
            start, math.min(start + _maxSnapshotRows, stocks.length));
        final snapshot = await repo.snapshot([for (final s in batch) s.id]);
        if (!mounted || !_appActive) return;
        generatedAt = snapshot.generatedAt;
        for (var i = 0; i < batch.length; i++) {
          final stock = batch[i];
          final row = snapshot.rows[i];
          if (row.exchange != stock.exchange || row.tradingSymbol != stock.tradingSymbol) {
            failed = true;
            _rows.remove(stock.id);
            continue;
          }
          _rows[stock.id] = row;
        }
      }
    } catch (_) {
      failed = true;
    } finally {
      _polling = false;
    }
    if (!mounted || !_appActive) return;
    setState(() {
      _feedFailed = failed;
      if (generatedAt != null) _generatedAt = generatedAt;
    });
    _schedulePoll(failed ? _failedPoll : _nextPollInterval());
  }

  /// Backend status with a client-side freshness guard for LIVE rows.
  String _displayStatus(CustomerLiveQuote? row, DateTime now) {
    if (row == null) return _feedFailed ? 'UNAVAILABLE' : 'PENDING';
    if (row.status == 'LIVE') {
      final at = row.exchangeAt;
      if (at == null) return 'STALE';
      final age = now.toUtc().difference(at.toUtc());
      if (age < const Duration(seconds: -5)) return 'STALE';
    }
    return row.status;
  }

  Duration _nextPollInterval() {
    final now = DateTime.now();
    final statuses = {for (final s in _stocks) _displayStatus(_rows[s.id], now)};
    if (statuses.any(const {'LIVE', 'STALE', 'CONNECTING', 'RECONNECTING', 'PENDING'}.contains)) {
      return _activePoll;
    }
    if (statuses.every((s) => s == 'MARKET_CLOSED')) return _closedPoll;
    return _degradedPoll;
  }

  void _refreshNow() => _schedulePoll(Duration.zero);

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    return MintTickCustomerScaffold(showHeader: false, children: [
      const SizedBox(height: 18),
      const MintTickEyebrow('MintTick / Markets'),
      const SizedBox(height: 6),
      Text('Markets', style: theme.textTheme.headlineSmall
          ?.copyWith(fontWeight: FontWeight.w800)),
      const SizedBox(height: 6),
      Text('NSE and BSE cash equities from MintTick’s app-managed feed. '
          'No personal broker login is needed.',
        style: theme.textTheme.bodyMedium?.copyWith(
            color: scheme.onSurfaceVariant, height: 1.4)),
      const SizedBox(height: 14),
      TabBar(
        key: const ValueKey('customer-market-tabs'),
        controller: _tabs,
        isScrollable: true,
        tabAlignment: TabAlignment.start,
        dividerColor: scheme.outlineVariant,
        tabs: [for (final label in _tabLabels) Tab(text: label, height: 44)],
      ),
      const SizedBox(height: 16),
      ...switch (_tabs.index) {
        0 => _overviewTab(),
        1 => _moversTab(),
        2 => _sectorsTab(),
        _ => _exploreTab(context),
      },
    ]);
  }

  List<Widget> _overviewTab() => [
        MintTickMarketPreview(
          repository: widget.repository,
          onConnectBroker: widget.onConnectBroker,
        ),
        const SizedBox(height: 16),
        ..._overviewSection(),
      ];

  List<Widget> _overviewStatus() {
    final error = _overviewError;
    return [
      if (error != null)
        MintTickInfoText(error, icon: Icons.cloud_off_outlined)
      else
        const MintTickInfoText('Loading the Nifty 50 overview…',
            icon: Icons.hourglass_empty_rounded),
    ];
  }

  List<Widget> _overviewSection() {
    if (widget.repository is! CustomerOverviewRepository) {
      return const [
        _NotAvailableBlock(
          title: 'Market overview',
          reason: 'This build cannot load the Nifty 50 overview.',
        ),
      ];
    }
    final overview = _overview;
    if (overview == null) return _overviewStatus();
    return [
      Text('Major indices', style: Theme.of(context).textTheme.titleMedium
          ?.copyWith(fontWeight: FontWeight.w800)),
      const SizedBox(height: 8),
      if (overview.indices.isEmpty)
        const MintTickInfoText('Index quotes are not in this snapshot. None are shown.')
      else
        for (final index in overview.indices) ...[
          _IndexCard(index: index),
          const SizedBox(height: 8),
        ],
      const SizedBox(height: 12),
      _BreadthCard(overview: overview),
      const SizedBox(height: 12),
      _OverviewFooter(overview: overview),
    ];
  }

  List<Widget> _moversTab() {
    if (widget.repository is! CustomerOverviewRepository) {
      return const [
        _NotAvailableBlock(
          title: 'Movers unavailable',
          reason: 'This build cannot load the Nifty 50 overview.',
        ),
      ];
    }
    final overview = _overview;
    if (overview == null) return _overviewStatus();
    return [
      _UniverseNote(universe: overview.universe),
      const SizedBox(height: 14),
      _MoverSection(title: 'Top gainers', rows: overview.gainers,
          emptyText: 'No Nifty 50 stock is above its previous close in this snapshot.',
          pctOf: (m) => m.changePct, pctLabel: 'Change', onOpen: _openMover),
      const SizedBox(height: 16),
      _MoverSection(title: 'Top losers', rows: overview.losers,
          emptyText: 'No Nifty 50 stock is below its previous close in this snapshot.',
          pctOf: (m) => m.changePct, pctLabel: 'Change', onOpen: _openMover),
      const SizedBox(height: 16),
      _MoverSection(title: 'Gap up at open', rows: overview.gapUp,
          emptyText: 'No Nifty 50 stock opened above its previous close.',
          pctOf: (m) => m.gapPct, pctLabel: 'Gap', onOpen: _openMover),
      const SizedBox(height: 16),
      _MoverSection(title: 'Gap down at open', rows: overview.gapDown,
          emptyText: 'No Nifty 50 stock opened below its previous close.',
          pctOf: (m) => m.gapPct, pctLabel: 'Gap', onOpen: _openMover),
    ];
  }

  List<Widget> _sectorsTab() {
    if (widget.repository is! CustomerOverviewRepository) {
      return const [
        _NotAvailableBlock(
          title: 'Sector performance unavailable',
          reason: 'This build cannot load the Nifty 50 overview.',
        ),
      ];
    }
    final overview = _overview;
    if (overview == null) return _overviewStatus();
    return [
      _UniverseNote(universe: overview.universe),
      const SizedBox(height: 14),
      if (overview.sectors.isEmpty)
        const MintTickInfoText('No sector has a verified quote in this snapshot.')
      else
        for (final sector in overview.sectors) ...[
          _SectorCard(sector: sector),
          const SizedBox(height: 8),
        ],
    ];
  }

  List<Widget> _exploreTab(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final now = DateTime.now();
    final statuses = [for (final s in _stocks) _displayStatus(_rows[s.id], now)];
    final banner = _sessionBanner(statuses, _feedFailed);
    return [
      TextField(
        key: const ValueKey('customer-market-search'),
        controller: _query,
        textInputAction: TextInputAction.search,
        inputFormatters: [
          LengthLimitingTextInputFormatter(100),
          FilteringTextInputFormatter.allow(RegExp(r'[A-Za-z0-9 .&/_-]')),
        ],
        decoration: InputDecoration(
          hintText: 'Search by symbol or company',
          prefixIcon: const Icon(Icons.search_rounded),
          suffixIcon: IconButton(
            tooltip: 'Search',
            onPressed: _loading ? null : _load,
            icon: const Icon(Icons.arrow_forward_rounded),
          ),
        ),
        onSubmitted: (_) => _load(),
      ),
      const SizedBox(height: 12),
      Wrap(spacing: 8, children: [
        for (final (name, exchange) in [('All', null), ('NSE', 'NSE'), ('BSE', 'BSE')])
          ChoiceChip(
            label: Text(name),
            selected: _exchange == exchange,
            showCheckmark: false,
            onSelected: (_) {
              setState(() => _exchange = exchange);
              _load();
            },
          ),
      ]),
      const SizedBox(height: 14),
      if (_catalogue?.activeCount == 0) ...[
        const MintTickInfoText('The exchange instrument catalogue has not yet '
            'been activated on the backend. An authorized catalogue import is required.'),
        const SizedBox(height: 12),
      ],
      if (_error != null) ...[
        MintTickInfoText(_error!, icon: Icons.cloud_off_outlined),
        const SizedBox(height: 12),
      ],
      if (_loading) ...[
        const Center(child: CircularProgressIndicator()),
        const SizedBox(height: 12),
      ],
      if (_stocks.isNotEmpty) ...[
        MintTickInfoText(banner.text, icon: banner.icon),
        const SizedBox(height: 14),
      ],
      Row(children: [
        Expanded(child: Text('Cash equities', style: theme.textTheme.titleMedium
            ?.copyWith(fontWeight: FontWeight.w800))),
        Text(_totalStocks == 0 ? 'No results' : '${_stocks.length} of $_totalStocks',
          style: theme.textTheme.bodySmall?.copyWith(color: scheme.onSurfaceVariant)),
        if (_canPoll && _stocks.isNotEmpty)
          IconButton(
            key: const ValueKey('customer-market-refresh-prices'),
            tooltip: 'Refresh prices',
            onPressed: _refreshNow,
            icon: const Icon(Icons.refresh_rounded, size: 20),
          ),
      ]),
      if (_generatedAt != null)
        Text('Updated ${_clock(_generatedAt!.toLocal())} · exchange tick time shown per row',
          style: theme.textTheme.labelSmall?.copyWith(color: scheme.onSurfaceVariant)),
      const SizedBox(height: 8),
      if (!_loading && _stocks.isEmpty && _error == null)
        const MintTickInfoText('No stocks available for this search. '
            'Try another symbol or verify the active instrument catalogue.')
      else
        for (final (index, stock) in _stocks.indexed)
          Padding(
            padding: const EdgeInsets.only(bottom: 8),
            child: Material(
              color: Colors.transparent,
              child: Semantics(
                button: true,
                label: 'Open ${stock.name.trim().isEmpty ? stock.tradingSymbol : stock.name} details',
                child: InkWell(
                  borderRadius: BorderRadius.circular(14),
                  onTap: () => _openDetail(stock),
                  child: _StockRow(stock: stock, quote: _rows[stock.id], status: statuses[index]),
                ),
              ),
            ),
          ),
      if (_hasNext && widget.repository is CustomerPaginatedMarketRepository) ...[
        const SizedBox(height: 10),
        SizedBox(width: double.infinity, child: OutlinedButton.icon(
          key: const ValueKey('customer-market-load-more'),
          onPressed: _loadingMore ? null : _loadMore,
          icon: _loadingMore
              ? const SizedBox(width: 16, height: 16,
                  child: CircularProgressIndicator(strokeWidth: 2))
              : const Icon(Icons.expand_more_rounded),
          label: Text(_loadingMore ? 'Loading equities…' : 'Load more equities'),
        )),
      ],
      const SizedBox(height: 16),
      Text('Prices are shown only when verified LIVE by MintTick’s backend. '
          'Information only, not investment advice.',
        style: theme.textTheme.labelSmall?.copyWith(color: scheme.onSurfaceVariant)),
    ];
  }

  ({String text, IconData icon}) _sessionBanner(List<String> statuses, bool feedFailed) {
    if (statuses.isEmpty) {
      return (text: 'Loading market status…', icon: Icons.hourglass_empty_rounded);
    }
    if (statuses.contains('LIVE')) {
      return (text: 'Live feed connected. Each price carries its exchange tick time.',
        icon: Icons.sensors_rounded);
    }
    if (statuses.every((s) => s == 'MARKET_CLOSED')) {
      return (text: 'Market closed. Prices appear when the NSE and BSE cash session opens.',
        icon: Icons.bedtime_outlined);
    }
    if (feedFailed) {
      return (text: 'Market feed request failed. Retrying automatically; no prices are shown.',
        icon: Icons.cloud_off_outlined);
    }
    if (statuses.any((s) => s == 'CONNECTING' || s == 'RECONNECTING' || s == 'PENDING' || s == 'STALE')) {
      return (text: 'Connecting to the live feed. Prices stay hidden until verified.',
        icon: Icons.sync_rounded);
    }
    return (text: 'Live prices are unavailable right now. No prices are shown.',
      icon: Icons.cloud_off_outlined);
  }
}

/// Honest placeholder for market data MintTick does not serve yet.
class _NotAvailableBlock extends StatelessWidget {
  const _NotAvailableBlock({required this.title, required this.reason});
  final String title;
  final String reason;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    return Semantics(
      container: true,
      label: '$title. Not available yet. $reason',
      child: ExcludeSemantics(
        child: MintTickSurface(child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(children: [
              Icon(Icons.hourglass_empty_rounded, size: 16, color: scheme.onSurfaceVariant),
              const SizedBox(width: 8),
              Expanded(child: Text(title, style: theme.textTheme.titleSmall
                  ?.copyWith(fontWeight: FontWeight.w800))),
              Text('Not available yet', style: theme.textTheme.labelSmall
                  ?.copyWith(color: scheme.onSurfaceVariant, fontWeight: FontWeight.w700)),
            ]),
            const SizedBox(height: 6),
            Text(reason, style: theme.textTheme.bodySmall
                ?.copyWith(color: scheme.onSurfaceVariant, height: 1.4)),
          ],
        )),
      ),
    );
  }
}

({String label, IconData icon, Color color}) _statusStyle(String status, ColorScheme scheme) =>
    switch (status) {
      'LIVE' => (label: 'LIVE', icon: Icons.circle, color: scheme.primary),
      'STALE' => (label: 'STALE', icon: Icons.schedule_rounded, color: scheme.tertiary),
      'RECONNECTING' => (label: 'RECONNECTING', icon: Icons.sync_rounded, color: scheme.tertiary),
      'CONNECTING' => (label: 'CONNECTING', icon: Icons.sync_rounded, color: scheme.onSurfaceVariant),
      'MARKET_CLOSED' => (label: 'CLOSED', icon: Icons.bedtime_outlined, color: scheme.onSurfaceVariant),
      'PENDING' => (label: 'LOADING', icon: Icons.hourglass_empty_rounded, color: scheme.onSurfaceVariant),
      _ => (label: 'NO FEED', icon: Icons.cloud_off_outlined, color: scheme.error),
    };

String _clock(DateTime time) =>
    '${time.hour.toString().padLeft(2, '0')}:${time.minute.toString().padLeft(2, '0')}:'
    '${time.second.toString().padLeft(2, '0')}';

class _StockRow extends StatelessWidget {
  const _StockRow({required this.stock, required this.quote, required this.status});
  final InstrumentRecord stock;
  final CustomerLiveQuote? quote;
  final String status;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final style = _statusStyle(status, scheme);
    final name = stock.name.trim().isEmpty
        ? stock.tradingSymbol.replaceFirst(RegExp(r'-EQ$'), '')
        : stock.name;
    final price = status == 'LIVE' ? quote?.ltp : null;
    final tickAt = status == 'LIVE' ? quote?.exchangeAt : null;
    final priceText = price == null ? '—' : '₹${price.toStringAsFixed(2)}';
    final statusLine = tickAt == null
        ? style.label
        : '${style.label} · ${_clock(tickAt.toLocal())}';
    return Semantics(
      container: true,
      label: '$name, ${stock.tradingSymbol} ${stock.exchange}. '
          '${price == null ? 'No verified price' : 'Price $priceText'}. '
          'Status ${style.label.toLowerCase()}.',
      child: ExcludeSemantics(
        child: Container(
          key: ValueKey('customer-market-stock-${stock.id}'),
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
          decoration: BoxDecoration(
            color: scheme.surfaceContainerLow,
            borderRadius: BorderRadius.circular(14),
            border: Border.all(color: scheme.outlineVariant),
          ),
          child: Row(children: [
            MintTickEquityAvatar(
              tradingSymbol: stock.tradingSymbol,
              companyName: stock.name,
              size: 42,
            ),
            const SizedBox(width: 12),
            Expanded(child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(name, maxLines: 1, overflow: TextOverflow.ellipsis,
                  style: theme.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w700)),
                const SizedBox(height: 2),
                Text('${stock.tradingSymbol} · ${stock.exchange}',
                  maxLines: 1, overflow: TextOverflow.ellipsis,
                  style: theme.textTheme.bodySmall?.copyWith(color: scheme.onSurfaceVariant)),
                const SizedBox(height: 4),
                Row(children: [
                  Icon(style.icon, size: 12, color: style.color),
                  const SizedBox(width: 4),
                  Flexible(child: Text(statusLine, maxLines: 1, overflow: TextOverflow.ellipsis,
                    style: theme.textTheme.labelSmall?.copyWith(
                        color: style.color, fontWeight: FontWeight.w700))),
                ]),
              ],
            )),
            const SizedBox(width: 8),
            Text(priceText,
              key: ValueKey('customer-market-price-${stock.id}'),
              maxLines: 1,
              style: theme.textTheme.titleMedium?.copyWith(
                fontWeight: FontWeight.w800,
                fontFeatures: const [FontFeature.tabularFigures()],
                color: price == null ? scheme.onSurfaceVariant : scheme.onSurface,
              )),
          ]),
        ),
      ),
    );
  }
}

String _isoDate(DateTime d) =>
    '${d.year.toString().padLeft(4, '0')}-${d.month.toString().padLeft(2, '0')}-'
    '${d.day.toString().padLeft(2, '0')}';

String _signedPct(num value) =>
    '${value > 0 ? '+' : ''}${value.toStringAsFixed(2)}%';

/// Direction is carried by sign and arrow text, not by colour alone.
class _DeltaText extends StatelessWidget {
  const _DeltaText({required this.pct, this.label});
  final num pct;
  final String? label;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final color = pct > 0 ? scheme.primary : pct < 0 ? scheme.error : scheme.onSurfaceVariant;
    final icon = pct > 0
        ? Icons.arrow_upward_rounded
        : pct < 0 ? Icons.arrow_downward_rounded : Icons.remove_rounded;
    final text = label == null ? _signedPct(pct) : '${label!} ${_signedPct(pct)}';
    return Row(mainAxisSize: MainAxisSize.min, children: [
      Icon(icon, size: 14, color: color),
      const SizedBox(width: 2),
      Text(text, maxLines: 1, style: Theme.of(context).textTheme.labelMedium?.copyWith(
          color: color, fontWeight: FontWeight.w800,
          fontFeatures: const [FontFeature.tabularFigures()])),
    ]);
  }
}

BoxDecoration _cardDecoration(ColorScheme scheme) => BoxDecoration(
      color: scheme.surfaceContainerLow,
      borderRadius: BorderRadius.circular(14),
      border: Border.all(color: scheme.outlineVariant),
    );

class _IndexCard extends StatelessWidget {
  const _IndexCard({required this.index});
  final CustomerIndexRow index;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final time = _clock(index.exchangeAt.toLocal());
    final stale = index.freshness == 'STALE';
    final freshnessLabel = switch (index.freshness) {
      'LIVE' => 'Live · exchange time $time',
      'STALE' => 'Stale · last tick $time',
      _ => 'Last close · $time',
    };
    final value = stale ? '—' : index.ltp.toStringAsFixed(2);
    return Semantics(
      container: true,
      label: stale
          ? '${index.displayName}, stale, value hidden, last tick $time.'
          : '${index.displayName}, $value, change ${_signedPct(index.changePct)}, $freshnessLabel.',
      child: ExcludeSemantics(
        child: Container(
          key: ValueKey('customer-market-index-${index.indexCode}'),
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
          decoration: _cardDecoration(scheme),
          child: Row(children: [
            Expanded(child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(index.displayName, maxLines: 1, overflow: TextOverflow.ellipsis,
                  style: theme.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w700)),
                const SizedBox(height: 2),
                Text(freshnessLabel, style: theme.textTheme.labelSmall
                    ?.copyWith(color: scheme.onSurfaceVariant)),
              ],
            )),
            const SizedBox(width: 8),
            Column(crossAxisAlignment: CrossAxisAlignment.end, children: [
              Text(value, style: theme.textTheme.titleMedium?.copyWith(
                  fontWeight: FontWeight.w800,
                  fontFeatures: const [FontFeature.tabularFigures()])),
              if (stale)
                Text('Value hidden', style: theme.textTheme.labelSmall
                    ?.copyWith(color: scheme.onSurfaceVariant))
              else
                _DeltaText(pct: index.changePct),
            ]),
          ]),
        ),
      ),
    );
  }
}

class _BreadthCard extends StatelessWidget {
  const _BreadthCard({required this.overview});
  final CustomerMarketOverview overview;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final b = overview.breadth;
    final muted = theme.textTheme.labelSmall?.copyWith(color: scheme.onSurfaceVariant);
    return Container(
      key: const ValueKey('customer-market-breadth'),
      padding: const EdgeInsets.all(14),
      decoration: _cardDecoration(scheme),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text('Market breadth', style: theme.textTheme.titleSmall
            ?.copyWith(fontWeight: FontWeight.w800)),
        const SizedBox(height: 2),
        Text('Nifty 50 constituents only', style: muted),
        const SizedBox(height: 10),
        if (b.total == 0)
          const MintTickInfoText('No Nifty 50 stock has a verified quote in this snapshot.')
        else ...[
          ClipRRect(
            borderRadius: BorderRadius.circular(4),
            child: SizedBox(height: 8, child: Row(children: [
              if (b.advancers > 0)
                Expanded(flex: b.advancers, child: ColoredBox(color: scheme.primary)),
              if (b.decliners > 0)
                Expanded(flex: b.decliners, child: ColoredBox(color: scheme.error)),
              if (b.unchanged > 0)
                Expanded(flex: b.unchanged, child: ColoredBox(color: scheme.outline)),
            ])),
          ),
          const SizedBox(height: 10),
          Wrap(spacing: 14, runSpacing: 6, children: [
            Text('Advancing ${b.advancers}', style: theme.textTheme.labelMedium
                ?.copyWith(fontWeight: FontWeight.w700, color: scheme.primary)),
            Text('Declining ${b.decliners}', style: theme.textTheme.labelMedium
                ?.copyWith(fontWeight: FontWeight.w700, color: scheme.error)),
            Text('Unchanged ${b.unchanged}', style: theme.textTheme.labelMedium
                ?.copyWith(fontWeight: FontWeight.w700, color: scheme.onSurfaceVariant)),
          ]),
        ],
      ]),
    );
  }
}

class _OverviewFooter extends StatelessWidget {
  const _OverviewFooter({required this.overview});
  final CustomerMarketOverview overview;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final muted = theme.textTheme.labelSmall?.copyWith(color: scheme.onSurfaceVariant);
    final tick = overview.exchangeAsOf;
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      _UniverseNote(universe: overview.universe),
      const SizedBox(height: 6),
      Text(tick == null
              ? 'Updated ${_clock(overview.generatedAt.toLocal())}'
              : 'Updated ${_clock(overview.generatedAt.toLocal())} · latest exchange tick '
                '${_clock(tick.toLocal())}',
          style: muted),
    ]);
  }
}

class _UniverseNote extends StatelessWidget {
  const _UniverseNote({required this.universe});
  final CustomerUniverse universe;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Text(
      'Covers ${universe.returned} of ${universe.members} Nifty 50 stocks with a verified '
      'quote. Constituent list as of ${_isoDate(universe.sourceAsOf)}. Breadth, movers and '
      'sectors are not a whole-market scan.',
      style: theme.textTheme.labelSmall?.copyWith(color: theme.colorScheme.onSurfaceVariant,
          height: 1.4),
    );
  }
}

class _MoverSection extends StatelessWidget {
  const _MoverSection({required this.title, required this.rows, required this.emptyText,
    required this.pctOf, required this.pctLabel, required this.onOpen});
  final String title;
  final List<CustomerMover> rows;
  final String emptyText;
  final num Function(CustomerMover) pctOf;
  final String pctLabel;
  final void Function(CustomerMover) onOpen;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Text(title, style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w800)),
      const SizedBox(height: 8),
      if (rows.isEmpty)
        MintTickInfoText(emptyText)
      else
        for (final mover in rows)
          Padding(
            padding: const EdgeInsets.only(bottom: 8),
            child: Semantics(
              container: true,
              button: true,
              onTap: () => onOpen(mover),
              label: '${mover.companyName.isEmpty ? mover.tradingSymbol : mover.companyName}, '
                  'price ${mover.ltp.toStringAsFixed(2)}, $pctLabel ${_signedPct(pctOf(mover))}, '
                  'exchange time ${_clock(mover.exchangeAt.toLocal())}. Opens stock detail.',
              child: ExcludeSemantics(
                child: Material(
                  color: Colors.transparent,
                  borderRadius: BorderRadius.circular(16),
                  child: InkWell(
                    borderRadius: BorderRadius.circular(16),
                    onTap: () => onOpen(mover),
                    child: Container(
                  padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
                  decoration: _cardDecoration(scheme),
                  child: Row(children: [
                    Expanded(child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(mover.companyName.isEmpty ? mover.tradingSymbol : mover.companyName,
                          maxLines: 1, overflow: TextOverflow.ellipsis,
                          style: theme.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w700)),
                        const SizedBox(height: 2),
                        Text('${mover.tradingSymbol} · ${mover.sector} · '
                            '${_clock(mover.exchangeAt.toLocal())}',
                          maxLines: 1, overflow: TextOverflow.ellipsis,
                          style: theme.textTheme.labelSmall
                              ?.copyWith(color: scheme.onSurfaceVariant)),
                      ],
                    )),
                    const SizedBox(width: 8),
                    Column(crossAxisAlignment: CrossAxisAlignment.end, children: [
                      Text('₹${mover.ltp.toStringAsFixed(2)}',
                          style: theme.textTheme.titleSmall?.copyWith(
                              fontWeight: FontWeight.w800,
                              fontFeatures: const [FontFeature.tabularFigures()])),
                      _DeltaText(pct: pctOf(mover), label: pctLabel),
                    ]),
                  ]),
                    ),
                  ),
                ),
              ),
            ),
          ),
    ]);
  }
}

class _SectorCard extends StatelessWidget {
  const _SectorCard({required this.sector});
  final CustomerSectorSummary sector;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    return Semantics(
      container: true,
      label: '${sector.sector}, ${sector.members} stocks, ${sector.advancers} up, '
          '${sector.decliners} down, average change ${_signedPct(sector.avgChangePct)}.',
      child: ExcludeSemantics(
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
          decoration: _cardDecoration(scheme),
          child: Row(children: [
            Expanded(child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(sector.sector, maxLines: 1, overflow: TextOverflow.ellipsis,
                  style: theme.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w700)),
                const SizedBox(height: 2),
                Text('${sector.members} stocks · ${sector.advancers} up · '
                    '${sector.decliners} down',
                  style: theme.textTheme.labelSmall
                      ?.copyWith(color: scheme.onSurfaceVariant)),
              ],
            )),
            _DeltaText(pct: sector.avgChangePct, label: 'Avg'),
          ]),
        ),
      ),
    );
  }
}

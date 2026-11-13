import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../../data/instrument_repository.dart';
import '../data/customer_market_repository.dart';
import 'minttick_customer_widgets.dart';
import 'minttick_equity_avatar.dart';
import 'minttick_market_components.dart';
import 'minttick_stock_detail_page.dart';

const _maxSnapshotRows = 30;
const _activePoll = Duration(seconds: 3);
const _degradedPoll = Duration(seconds: 15);
const _closedPoll = Duration(seconds: 30);
const _failedPoll = Duration(seconds: 15);
const _overviewPoll = Duration(seconds: 30);
const _boardPoll = Duration(seconds: 60);

const _tabLabels = ['Explore', 'Movers', 'Sectors', 'Stocks'];
const _segments = ['ALL', 'LARGE', 'MID', 'SMALL', 'MICRO'];
const _segmentChips = ['All NSE', 'Large', 'Mid', 'Small', 'Micro'];
const _moverSorts = ['GAINERS', 'LOSERS', 'ACTIVE', 'VOLUME', 'HIGH52', 'LOW52', 'GAP_UP', 'GAP_DOWN'];
const _moverSortLabels = ['Gainers', 'Losers', 'Most active', 'Volume', '52W high', '52W low',
  'Gap up', 'Gap down'];

/// NSE Markets: index strip, whole-market breadth, large/mid/small/micro cap segments, movers,
/// most active, 52-week extremes, sector heat map and the full NSE stock list.
///
/// Every price shown is a source-verified quote with its own exchange time. Stored quotes are
/// labelled as such and are never presented as live; stale index values stay hidden.
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
  late final TabController _tabs = TabController(length: _tabLabels.length, vsync: this);
  final TextEditingController _query = TextEditingController();
  bool _appActive = true;

  // Nifty 50 overview: index strip, fallback movers/sectors and rule screens.
  CustomerMarketOverview? _overview;
  String? _overviewError;
  bool _overviewLoading = false;
  Timer? _overviewTimer;

  // Whole-market board.
  CustomerBoardSummary? _summary;
  String? _summaryError;
  bool _summaryLoading = false;
  int _exploreSegment = 0;
  int _explore52 = 0;
  final Map<String, CustomerBoard> _explore = {};
  bool _exploreLoading = false;
  int _exploreSeq = 0;
  int _moverSegment = 0;
  int _moverSort = 0;
  CustomerBoard? _moverBoard;
  String? _moverError;
  bool _moverLoading = false;
  int _moverSeq = 0;
  int _legacyMoverFilter = 0;
  Timer? _boardTimer;

  // Coverage metadata (info sheet).
  CustomerUniverseFacets? _facets;
  CustomerFullQuoteCoverage? _fullQuoteCoverage;

  // NSE stock list with batched snapshot polling.
  final Map<int, CustomerLiveQuote> _rows = {};
  InstrumentCatalogueStatus? _catalogue;
  List<InstrumentRecord> _stocks = const [];
  bool _hasNext = false;
  int _totalStocks = 0;
  int _nextPage = 1;
  bool _loadingMore = false;
  String? _error;
  bool _loading = false;
  bool _polling = false;
  bool _feedFailed = false;
  DateTime? _generatedAt;
  Timer? _pollTimer;
  int _version = 0;

  CustomerMarketBoardRepository? get _boardRepo {
    final repo = widget.repository;
    return repo is CustomerMarketBoardRepository ? repo as CustomerMarketBoardRepository : null;
  }

  bool get _hasBoard => _boardRepo != null && _summary != null;

  bool get _canPoll => widget.repository is CustomerSnapshotMarketRepository;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _tabs.addListener(_onTabChanged);
    _load();
    unawaited(_loadCoverage());
    if (widget.repository is CustomerOverviewRepository) {
      unawaited(_loadOverview());
      _overviewTimer = Timer.periodic(_overviewPoll, (_) => unawaited(_loadOverview()));
    }
    if (_boardRepo != null) {
      unawaited(_loadBoard());
      _boardTimer = Timer.periodic(_boardPoll, (_) {
        if (_appActive) unawaited(_loadBoard());
      });
    }
  }

  @override
  void dispose() {
    _version++;
    WidgetsBinding.instance.removeObserver(this);
    _pollTimer?.cancel();
    _overviewTimer?.cancel();
    _boardTimer?.cancel();
    _tabs.removeListener(_onTabChanged);
    _tabs.dispose();
    _query.dispose();
    super.dispose();
  }

  void _onTabChanged() {
    if (_tabs.indexIsChanging) return;
    setState(() {});
    if (_tabs.index == 1 && _moverBoard == null && _boardRepo != null) unawaited(_loadMovers());
  }

  // ---------------------------------------------------------------------------
  // Navigation
  // ---------------------------------------------------------------------------

  void _openDetail(InstrumentRecord stock) {
    Navigator.of(context).push(MaterialPageRoute<void>(
      builder: (_) => MintTickStockDetailPage(stock: stock, repository: widget.repository),
    ));
  }

  void _openBoardRow(CustomerBoardRow row) => _openDetail(row.toInstrument());

  /// Nifty 50 overview movers carry no instrument id, so resolve the exact symbol.
  Future<void> _openSymbol(String symbol) async {
    void notify(String message) {
      if (!mounted) return;
      ScaffoldMessenger.maybeOf(context)?.showSnackBar(SnackBar(content: Text(message)));
    }
    try {
      final page = await widget.repository.search(query: symbol, exchange: 'NSE');
      if (!mounted) return;
      final matches = page.content.where((s) => s.tradingSymbol == symbol);
      if (matches.isEmpty) {
        notify('$symbol is not in the active instrument catalogue, so its detail is unavailable.');
        return;
      }
      _openDetail(matches.first);
    } catch (_) {
      notify('Could not open $symbol. Try again.');
    }
  }

  void _openSearch() {
    Navigator.of(context).push(MaterialPageRoute<void>(
      builder: (_) => _MarketSearchPage(repository: widget.repository, onOpen: _openDetail),
    ));
  }

  void _openSector(String sector) {
    final repo = _boardRepo;
    if (repo == null) return;
    Navigator.of(context).push(MaterialPageRoute<void>(
      builder: (_) => _SectorStocksPage(repository: repo, sector: sector, onOpen: _openBoardRow),
    ));
  }

  void _showMovers({int? segment, int? sort}) {
    setState(() {
      if (segment != null) _moverSegment = segment;
      if (sort != null) _moverSort = sort;
    });
    _tabs.animateTo(1);
    if (_boardRepo != null) unawaited(_loadMovers());
  }

  // ---------------------------------------------------------------------------
  // Loading
  // ---------------------------------------------------------------------------

  Future<void> _loadCoverage() async {
    final repo = widget.repository;
    try {
      if (repo is CustomerUniverseFacetsRepository) {
        final facets = await (repo as CustomerUniverseFacetsRepository).universeFacets();
        if (mounted) setState(() => _facets = facets);
      }
    } catch (_) {/* coverage is informational only */}
    try {
      if (repo is CustomerFullQuoteCoverageRepository) {
        final coverage = await (repo as CustomerFullQuoteCoverageRepository).fullQuoteCoverage();
        if (mounted) setState(() => _fullQuoteCoverage = coverage);
      }
    } catch (_) {/* coverage is informational only */}
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
          _overview = _overview?.asLastRecorded();
          _overviewError = _overview == null
              ? 'The Nifty 50 overview is unavailable. No verified snapshot '
                  'has been saved yet; retrying automatically.'
              : 'Could not refresh. Showing the last verified snapshot, not live prices.';
        });
      }
    } finally {
      _overviewLoading = false;
    }
  }

  Future<void> _loadBoard() async {
    final repo = _boardRepo;
    if (repo == null || _summaryLoading || !mounted) return;
    _summaryLoading = true;
    try {
      final summary = await repo.boardSummary();
      if (!mounted) return;
      setState(() {
        _summary = summary;
        _summaryError = null;
      });
      unawaited(_loadExplore());
      if (_tabs.index == 1) unawaited(_loadMovers());
    } catch (_) {
      if (mounted) {
        setState(() => _summaryError = 'Whole-market data is unavailable right now. '
            'Showing the Nifty 50 overview instead.');
      }
    } finally {
      _summaryLoading = false;
    }
  }

  Future<void> _loadExplore() async {
    final repo = _boardRepo;
    if (repo == null) return;
    final seq = ++_exploreSeq;
    final segment = _segments[_exploreSegment];
    setState(() => _exploreLoading = true);
    try {
      const sorts = ['GAINERS', 'LOSERS', 'ACTIVE', 'HIGH52', 'LOW52'];
      final boards = await Future.wait([
        for (final sort in sorts) repo.board(segment: segment, sort: sort, limit: 6),
      ]);
      if (!mounted || seq != _exploreSeq) return;
      setState(() {
        _explore.clear();
        for (var i = 0; i < sorts.length; i++) {
          _explore[sorts[i]] = boards[i];
        }
      });
    } catch (_) {
      if (mounted && seq == _exploreSeq) setState(_explore.clear);
    } finally {
      if (mounted && seq == _exploreSeq) setState(() => _exploreLoading = false);
    }
  }

  Future<void> _loadMovers() async {
    final repo = _boardRepo;
    if (repo == null) return;
    final seq = ++_moverSeq;
    setState(() {
      _moverLoading = true;
      _moverError = null;
    });
    try {
      final board = await repo.board(segment: _segments[_moverSegment],
          sort: _moverSorts[_moverSort], limit: 50);
      if (!mounted || seq != _moverSeq) return;
      setState(() => _moverBoard = board);
    } catch (_) {
      if (mounted && seq == _moverSeq) {
        setState(() {
          _moverBoard = null;
          _moverError = 'Could not load movers. Pull to refresh or try again.';
        });
      }
    } finally {
      if (mounted && seq == _moverSeq) setState(() => _moverLoading = false);
    }
  }

  Future<void> _refreshAll() async {
    await Future.wait([
      _loadOverview(),
      _loadBoard(),
      _loadCoverage(),
      _load(),
    ]);
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    final active = state == AppLifecycleState.resumed;
    if (_appActive == active) return;
    _appActive = active;
    _pollTimer?.cancel();
    _pollTimer = null;
    if (!active) {
      // Preserve only last verified values, never a backgrounded LIVE quote.
      setState(() {
        final previous = Map<int, CustomerLiveQuote>.of(_rows);
        _rows.clear();
        for (final entry in previous.entries) {
          final saved = entry.value.asLastRecorded();
          if (saved != null) _rows[entry.key] = saved;
        }
        _overview = _overview?.asLastRecorded();
      });
    } else {
      _schedulePoll(Duration.zero);
      unawaited(_loadOverview());
      unawaited(_loadBoard());
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
        widget.repository.search(query: _queryText, exchange: 'NSE'),
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
          _error = 'Unable to load the NSE stock list. Check your connection and try again.';
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
          .searchPage(query: _queryText, exchange: 'NSE', page: _nextPage);
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
          const SnackBar(content: Text('Could not load more stocks. Retry.')));
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
    if (_polling || !mounted || !_appActive || repo == null) return;
    final stocks = _stocks;
    if (stocks.isEmpty) return;
    _polling = true;
    var failed = false;
    DateTime? generatedAt;
    try {
      for (var start = 0; start < stocks.length; start += _maxSnapshotRows) {
        final batch = stocks.sublist(start, math.min(start + _maxSnapshotRows, stocks.length));
        final snapshot = await repo.snapshot([for (final s in batch) s.id]);
        if (!mounted || !_appActive) return;
        generatedAt = snapshot.generatedAt;
        for (var i = 0; i < batch.length; i++) {
          final stock = batch[i];
          final row = snapshot.rows[i];
          if (row.instrumentId != stock.id || row.exchange != stock.exchange ||
              row.tradingSymbol != stock.tradingSymbol) {
            failed = true;
            final saved = _rows[stock.id]?.asLastRecorded();
            if (saved != null) {
              _rows[stock.id] = saved;
            } else {
              _rows.remove(stock.id);
            }
            continue;
          }
          if (row.status == 'LIVE' || row.status == 'LAST_CLOSE') {
            final previous = _rows[stock.id]?.asLastRecorded();
            if (row.status == 'LAST_CLOSE' && previous?.exchangeAt != null &&
                row.exchangeAt != null && previous!.exchangeAt!.isAfter(row.exchangeAt!)) {
              _rows[stock.id] = previous;
            } else {
              _rows[stock.id] = row;
            }
          } else {
            _rows[stock.id] = _rows[stock.id]?.asLastRecorded() ?? row;
          }
        }
      }
    } catch (_) {
      failed = true;
    } finally {
      _polling = false;
    }
    if (failed) {
      // A partly failed poll must not leave an old LIVE badge on screen.
      for (final id in _rows.keys.toList()) {
        final recorded = _rows[id]?.asLastRecorded();
        if (recorded != null) _rows[id] = recorded;
      }
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
    if (statuses.every((s) => s == 'MARKET_CLOSED' || s == 'LAST_CLOSE')) return _closedPoll;
    return _degradedPoll;
  }

  // ---------------------------------------------------------------------------
  // Build
  // ---------------------------------------------------------------------------

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    return PopScope<void>(
      // Within Markets, the first back gesture returns to Explore.
      canPop: _tabs.index == 0,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop && _tabs.index != 0) _tabs.animateTo(0);
      },
      child: MintTickCustomerScaffold(showHeader: false, children: [
        const SizedBox(height: 2),
        Row(children: [
          Expanded(child: Semantics(header: true, child: Text('Markets',
              style: theme.textTheme.headlineSmall?.copyWith(fontWeight: FontWeight.w800)))),
          if (_summary != null) _SessionChip(status: _summary!.market),
          IconButton(
            key: const ValueKey('market-search-open'),
            tooltip: 'Search NSE stocks',
            onPressed: _openSearch,
            icon: const Icon(Icons.search_rounded),
          ),
          IconButton(
            key: const ValueKey('market-universe-refresh'),
            tooltip: 'Refresh markets',
            onPressed: () => unawaited(_refreshAll()),
            icon: const Icon(Icons.refresh_rounded),
          ),
        ]),
        ..._indexStrip(),
        const SizedBox(height: 6),
        TabBar(
          key: const ValueKey('customer-market-tabs'),
          controller: _tabs,
          isScrollable: false,
          dividerColor: scheme.outlineVariant,
          labelPadding: EdgeInsets.zero,
          labelStyle: theme.textTheme.labelLarge?.copyWith(fontWeight: FontWeight.w800),
          tabs: [for (final label in _tabLabels) Tab(text: label, height: 40)],
        ),
        const SizedBox(height: 4),
        ...switch (_tabs.index) {
          0 => _exploreTab(),
          1 => _moversTab(),
          2 => _sectorsTab(),
          _ => _stocksTab(),
        },
      ]),
    );
  }

  List<Widget> _indexStrip() {
    final theme = Theme.of(context);
    final overview = _overview;
    if (widget.repository is! CustomerOverviewRepository) return const [];
    if (overview == null) {
      return [
        const SizedBox(height: 6),
        if (_overviewError != null)
          MintTickInfoText(_overviewError!, icon: Icons.cloud_off_outlined)
        else
          const MintTickInfoText('Loading indices…', icon: Icons.hourglass_empty_rounded),
      ];
    }
    final stored = overview.source == 'STORED_LAST_OVERVIEW';
    return [
      const SizedBox(height: 4),
      _MarketStatusPill(overview: overview, failed: _overviewError != null),
      if (stored)
        Padding(
          padding: const EdgeInsets.only(top: 2),
          child: Text(
            'Last verified snapshot · historical, not live',
            maxLines: 1, overflow: TextOverflow.ellipsis,
            style: theme.textTheme.labelSmall?.copyWith(color: theme.colorScheme.onSurfaceVariant),
          ),
        ),
      if (_overviewError != null) ...[
        const SizedBox(height: 6),
        MintTickInfoText(_overviewError!, icon: Icons.cloud_off_outlined),
      ],
      const SizedBox(height: 8),
      if (overview.indices.isEmpty)
        Text('No verified index quote in this snapshot', style: theme.textTheme.bodySmall
            ?.copyWith(color: theme.colorScheme.onSurfaceVariant))
      else
        SizedBox(
          height: 112,
          child: ListView.separated(
            key: const ValueKey('market-index-strip'),
            scrollDirection: Axis.horizontal,
            itemCount: overview.indices.length,
            separatorBuilder: (_, _) => const SizedBox(width: 8),
            itemBuilder: (context, i) => IndexTickerCard(index: overview.indices[i], stored: stored),
          ),
        ),
    ];
  }

  // ---------------------------------------------------------------------------
  // Explore
  // ---------------------------------------------------------------------------

  List<Widget> _exploreTab() {
    if (!_hasBoard) {
      return [
        if (_boardRepo != null && _summaryError == null && _summary == null)
          const Padding(
            padding: EdgeInsets.symmetric(vertical: 8),
            child: LinearProgressIndicator(minHeight: 2),
          ),
        if (_summaryError != null) ...[
          const SizedBox(height: 8),
          MintTickInfoText(_summaryError!, icon: Icons.cloud_off_outlined),
        ],
        ..._legacyExplore(),
      ];
    }
    final summary = _summary!;
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final all = summary.segment('ALL');
    final segmentCode = _segments[_exploreSegment];
    final caps = [for (final code in const ['LARGE', 'MID', 'SMALL', 'MICRO'])
      ?summary.segment(code)];
    final sectorScale = summary.sectors.fold<double>(0,
        (m, s) => math.max(m, s.avgChangePct.abs().toDouble()));
    final segmentEnabled = [
      for (final code in _segments) code == 'ALL' || (summary.segment(code)?.available ?? false),
    ];
    return [
      const SizedBox(height: 10),
      if (all != null) _MarketMoodCard(all: all, freshness: summary.freshness),
      MarketSectionHeader(
        title: 'Market cap',
        subtitle: 'Official Nifty 100 · Midcap 150 · Smallcap 250 · Microcap 250 lists',
      ),
      _SegmentGrid(segments: caps, onOpen: (code) => _showMovers(
          segment: _segments.indexOf(code), sort: 0)),
      MarketSectionHeader(
        title: 'Top movers',
        trailing: MarketSeeAll(onTap: () => _showMovers(segment: _exploreSegment, sort: 0)),
      ),
      MarketChipRow(
        keyPrefix: 'market-explore-segment',
        labels: _segmentChips,
        selected: _exploreSegment,
        enabled: segmentEnabled,
        onSelected: (i) {
          setState(() => _exploreSegment = i);
          unawaited(_loadExplore());
        },
      ),
      const SizedBox(height: 10),
      if (_exploreLoading && _explore.isEmpty)
        const LinearProgressIndicator(minHeight: 2)
      else ...[
        _MoverCarousel(title: 'Top gainers', board: _explore['GAINERS'], onOpen: _openBoardRow,
            empty: 'No ${segmentLabels[segmentCode]!.toLowerCase()} stock is up this session.'),
        const SizedBox(height: 12),
        _MoverCarousel(title: 'Top losers', board: _explore['LOSERS'], onOpen: _openBoardRow,
            empty: 'No ${segmentLabels[segmentCode]!.toLowerCase()} stock is down this session.'),
      ],
      MarketSectionHeader(
        title: 'Most active',
        subtitle: 'By traded value · ${segmentLabels[segmentCode]}',
        trailing: MarketSeeAll(onTap: () => _showMovers(segment: _exploreSegment, sort: 2)),
      ),
      ..._boardRows(_explore['ACTIVE'], 'ACTIVE', limit: 5,
          empty: 'No traded value recorded for this session yet.'),
      MarketSectionHeader(
        title: '52-week extremes',
        subtitle: 'Within 2% of the 52-week high or low',
        trailing: MarketSeeAll(onTap: () => _showMovers(
            segment: _exploreSegment, sort: _explore52 == 0 ? 4 : 5)),
      ),
      MarketChipRow(
        keyPrefix: 'market-explore-52w',
        labels: [
          'Near high · ${_explore['HIGH52']?.rows.length ?? 0}',
          'Near low · ${_explore['LOW52']?.rows.length ?? 0}',
        ],
        selected: _explore52,
        onSelected: (i) => setState(() => _explore52 = i),
      ),
      const SizedBox(height: 4),
      ..._boardRows(_explore[_explore52 == 0 ? 'HIGH52' : 'LOW52'],
          _explore52 == 0 ? 'HIGH52' : 'LOW52', limit: 5,
          empty: _explore52 == 0 ? 'No stock is near its 52-week high.'
              : 'No stock is near its 52-week low.'),
      if (summary.sectors.isNotEmpty) ...[
        MarketSectionHeader(
          title: 'Sectors',
          subtitle: 'Average move of stocks in official Nifty lists',
          trailing: MarketSeeAll(onTap: () => _tabs.animateTo(2)),
        ),
        _SectorGrid(
          children: [
            for (final s in summary.sectors.take(6))
              SectorHeatTile(sector: s.sector, name: shortSector(s.sector),
                  avgChangePct: s.avgChangePct, advancers: s.advancers,
                  decliners: s.decliners, scale: sectorScale,
                  onTap: () => _openSector(s.sector)),
          ],
        ),
      ],
      if (_overview case final overview?) ...[
        const SizedBox(height: 14),
        _IdeasSection(ideas: overview.ideas, onOpen: _openSymbol,
            enabled: _overviewHasCurrentIdeaEvidence(overview)),
      ],
      const SizedBox(height: 16),
      _BoardFooter(summary: summary, onCoverage: _showCoverage),
      if (scheme.brightness == Brightness.dark) const SizedBox(height: 4),
    ];
  }

  List<Widget> _boardRows(CustomerBoard? board, String metric,
      {required int limit, required String empty}) {
    if (board == null) {
      return [if (_exploreLoading) const LinearProgressIndicator(minHeight: 2)];
    }
    if (!board.available) {
      return [const MintTickInfoText('This segment\'s official list has not been imported yet.')];
    }
    if (board.rows.isEmpty) return [_EmptyLine(empty)];
    return [
      for (final row in board.rows.take(limit))
        BoardStockRow(row: row, metric: metric, onTap: () => _openBoardRow(row)),
    ];
  }

  /// Nifty 50 overview content used when the whole-market board is not reachable.
  List<Widget> _legacyExplore() {
    final overview = _overview;
    if (widget.repository is! CustomerOverviewRepository) {
      return const [
        SizedBox(height: 10),
        _NotAvailableBlock(title: 'Market overview',
            reason: 'This build cannot load market overview data.'),
      ];
    }
    if (overview == null) return const [];
    final theme = Theme.of(context);
    return [
      const SizedBox(height: 10),
      MintTickSurface(
        padding: const EdgeInsets.fromLTRB(14, 12, 14, 12),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text('Market breadth · Nifty 50 only', style: theme.textTheme.titleSmall
              ?.copyWith(fontWeight: FontWeight.w800)),
          const SizedBox(height: 10),
          if (overview.breadth.total == 0)
            const Text('No Nifty 50 stock has a verified quote in this snapshot.')
          else
            BreadthBar(advancers: overview.breadth.advancers,
                decliners: overview.breadth.decliners, unchanged: overview.breadth.unchanged),
        ]),
      ),
      MarketSectionHeader(title: 'Top gainers',
          trailing: MarketSeeAll(onTap: () => _showMovers(sort: 0))),
      _MoverSection(rows: overview.gainers.take(3).toList(),
          emptyText: 'No Nifty 50 stock is above its previous close in this snapshot.',
          pctOf: (m) => m.changePct, pctLabel: 'Change', onOpen: (m) => _openSymbol(m.tradingSymbol)),
      const MarketSectionHeader(title: 'Top losers'),
      _MoverSection(rows: overview.losers.take(3).toList(),
          emptyText: 'No Nifty 50 stock is below its previous close in this snapshot.',
          pctOf: (m) => m.changePct, pctLabel: 'Change', onOpen: (m) => _openSymbol(m.tradingSymbol)),
      if (overview.sectors.isNotEmpty) ...[
        MarketSectionHeader(title: 'Sectors · Nifty 50',
            trailing: MarketSeeAll(onTap: () => _tabs.animateTo(2))),
        _SectorGrid(children: [
          for (final s in overview.sectors.take(6))
            SectorHeatTile(sector: s.sector, name: shortSector(s.sector),
                avgChangePct: s.avgChangePct, advancers: s.advancers, decliners: s.decliners,
                scale: overview.sectors.fold<double>(0,
                    (m, x) => math.max(m, x.avgChangePct.abs().toDouble()))),
        ]),
      ],
      const SizedBox(height: 14),
      _IdeasSection(ideas: overview.ideas, onOpen: _openSymbol,
          enabled: _overviewHasCurrentIdeaEvidence(overview)),
      const SizedBox(height: 12),
      _UniverseNote(universe: overview.universe),
    ];
  }

  // ---------------------------------------------------------------------------
  // Movers
  // ---------------------------------------------------------------------------

  List<Widget> _moversTab() {
    if (!_hasBoard) return _legacyMovers();
    final summary = _summary!;
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final segmentCode = _segments[_moverSegment];
    final board = _moverBoard;
    final segment = summary.segment(segmentCode);
    return [
      const SizedBox(height: 10),
      MarketChipRow(
        keyPrefix: 'market-movers-segment',
        labels: _segmentChips,
        selected: _moverSegment,
        onSelected: (i) {
          setState(() => _moverSegment = i);
          unawaited(_loadMovers());
        },
      ),
      const SizedBox(height: 4),
      MarketChipRow(
        keyPrefix: 'market-movers-sort',
        labels: _moverSortLabels,
        selected: _moverSort,
        onSelected: (i) {
          setState(() => _moverSort = i);
          unawaited(_loadMovers());
        },
      ),
      MarketSectionHeader(
        title: '${_moverSortLabels[_moverSort]} · ${segmentLabels[segmentCode]}',
        subtitle: board == null ? null
            : '${board.rows.length} shown · ${board.quoted} quoted this session'
              '${segment != null && segmentCode != 'ALL' ? ' of ${segment.members}' : ''}',
      ),
      if (_moverLoading && board == null) const LinearProgressIndicator(minHeight: 2),
      if (_moverError != null) MintTickInfoText(_moverError!, icon: Icons.cloud_off_outlined),
      if (board != null && !board.available)
        MintTickInfoText('The official ${segmentLabels[segmentCode]} list has not been '
            'imported yet, so this segment cannot be shown. All NSE stocks remain available.')
      else if (board != null && board.rows.isEmpty)
        const _EmptyLine('No stock matches this view in the latest session.')
      else if (board != null)
        for (final (i, row) in board.rows.indexed)
          BoardStockRow(row: row, metric: board.sort, rank: i + 1,
              onTap: () => _openBoardRow(row)),
      const SizedBox(height: 10),
      Text(_freshnessText(summary.freshness), style: theme.textTheme.labelSmall
          ?.copyWith(color: scheme.onSurfaceVariant)),
    ];
  }

  List<Widget> _legacyMovers() {
    final overview = _overview;
    if (widget.repository is! CustomerOverviewRepository) {
      return const [
        SizedBox(height: 10),
        _NotAvailableBlock(title: 'Movers unavailable',
            reason: 'This build cannot load market overview data.'),
      ];
    }
    if (overview == null) return const [];
    const labels = ['Gainers', 'Losers', 'Gap up', 'Gap down'];
    const titles = ['Top gainers', 'Top losers', 'Gap up at open', 'Gap down at open'];
    final rows = switch (_legacyMoverFilter) {
      0 => overview.gainers,
      1 => overview.losers,
      2 => overview.gapUp,
      _ => overview.gapDown,
    };
    return [
      const SizedBox(height: 10),
      MarketChipRow(keyPrefix: 'market-movers-filter', labels: labels,
          selected: _legacyMoverFilter,
          onSelected: (i) => setState(() => _legacyMoverFilter = i)),
      MarketSectionHeader(title: titles[_legacyMoverFilter],
          subtitle: 'Nifty 50 · ${overview.universe.returned}/${overview.universe.members} quoted'),
      _MoverSection(
        rows: rows,
        emptyText: 'No Nifty 50 stock matches this view in the snapshot.',
        pctOf: (m) => _legacyMoverFilter < 2 ? m.changePct : m.gapPct,
        pctLabel: _legacyMoverFilter < 2 ? 'Change' : 'Gap',
        onOpen: (m) => _openSymbol(m.tradingSymbol),
      ),
      const SizedBox(height: 12),
      _UniverseNote(universe: overview.universe),
    ];
  }

  // ---------------------------------------------------------------------------
  // Sectors
  // ---------------------------------------------------------------------------

  List<Widget> _sectorsTab() {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    if (_hasBoard && _summary!.sectors.isNotEmpty) {
      final sectors = _summary!.sectors;
      final scale = sectors.fold<double>(0, (m, s) => math.max(m, s.avgChangePct.abs().toDouble()));
      final ups = sectors.where((s) => s.avgChangePct > 0).length;
      return [
        MarketSectionHeader(
          title: 'Sector heat map',
          subtitle: '$ups of ${sectors.length} sectors up · tap a sector for its stocks',
        ),
        _SectorGrid(children: [
          for (final s in sectors)
            SectorHeatTile(sector: s.sector, name: shortSector(s.sector),
                avgChangePct: s.avgChangePct, advancers: s.advancers,
                decliners: s.decliners, scale: scale, onTap: () => _openSector(s.sector)),
        ]),
        const MarketSectionHeader(title: 'Sector breadth'),
        for (final s in sectors)
          _SectorBreadthRow(sector: s, onTap: () => _openSector(s.sector)),
        const SizedBox(height: 10),
        Text('Sectors use the official NSE industry of each stock in the Nifty 100, '
            'Midcap 150, Smallcap 250 and Microcap 250 lists.',
            style: theme.textTheme.labelSmall?.copyWith(color: scheme.onSurfaceVariant)),
      ];
    }
    final overview = _overview;
    if (overview == null) {
      return [
        const SizedBox(height: 10),
        if (_hasBoard)
          const MintTickInfoText('Sector classification appears once the official Nifty '
              'index lists have been imported.')
        else
          const _NotAvailableBlock(title: 'Sector performance unavailable',
              reason: 'No verified market overview has loaded yet.'),
      ];
    }
    final scale = overview.sectors.fold<double>(0,
        (m, s) => math.max(m, s.avgChangePct.abs().toDouble()));
    return [
      MarketSectionHeader(title: 'Sectors · Nifty 50',
          subtitle: '${overview.universe.returned} of ${overview.universe.members} stocks quoted'),
      if (overview.sectors.isEmpty)
        const MintTickInfoText('No sector has a verified quote in this snapshot.')
      else
        for (final sector in overview.sectors)
          _SectorCard(sector: sector, scale: scale),
      const SizedBox(height: 10),
      _UniverseNote(universe: overview.universe),
    ];
  }

  // ---------------------------------------------------------------------------
  // Stocks
  // ---------------------------------------------------------------------------

  List<Widget> _stocksTab() {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final now = DateTime.now();
    final statuses = [for (final s in _stocks) _displayStatus(_rows[s.id], now)];
    final banner = _sessionBanner(statuses, _feedFailed);
    final nseListed = _facets?.exchanges['NSE'];
    return [
      const SizedBox(height: 10),
      TextField(
        key: const ValueKey('customer-market-search'),
        controller: _query,
        textInputAction: TextInputAction.search,
        inputFormatters: [
          LengthLimitingTextInputFormatter(100),
          FilteringTextInputFormatter.allow(RegExp(r'[A-Za-z0-9 .&/_-]')),
        ],
        decoration: InputDecoration(
          isDense: true,
          hintText: 'Filter by symbol or company',
          prefixIcon: const Icon(Icons.search_rounded),
          suffixIcon: IconButton(
            tooltip: 'Search',
            onPressed: _loading ? null : _load,
            icon: const Icon(Icons.arrow_forward_rounded),
          ),
        ),
        onSubmitted: (_) => _load(),
      ),
      const SizedBox(height: 8),
      if (_catalogue?.activeCount == 0) ...[
        const MintTickInfoText('The exchange instrument catalogue has not yet '
            'been activated on the backend. An authorized catalogue import is required.'),
        const SizedBox(height: 8),
      ],
      if (_error != null) ...[
        MintTickInfoText(_error!, icon: Icons.cloud_off_outlined),
        const SizedBox(height: 8),
      ],
      Row(children: [
        Expanded(child: Text('NSE stocks', style: theme.textTheme.titleMedium
            ?.copyWith(fontWeight: FontWeight.w800))),
        Text(_totalStocks == 0 ? 'No results'
            : '${_stocks.length} of ${nseListed != null && _queryText == null ? nseListed : _totalStocks}',
          style: theme.textTheme.bodySmall?.copyWith(color: scheme.onSurfaceVariant)),
        if (_canPoll && _stocks.isNotEmpty)
          IconButton(
            key: const ValueKey('customer-market-refresh-prices'),
            tooltip: 'Refresh prices',
            onPressed: () => _schedulePoll(Duration.zero),
            icon: const Icon(Icons.refresh_rounded, size: 20),
          ),
      ]),
      if (_stocks.isNotEmpty)
        _MarketFeedLine(
          text: _generatedAt == null ? banner.text
              : '${banner.text} Checked ${clockSeconds(_generatedAt!.toLocal())}.',
          icon: banner.icon),
      const SizedBox(height: 4),
      if (_loading) ...[
        const SizedBox(height: 8),
        const Center(child: CircularProgressIndicator()),
      ] else if (_stocks.isEmpty && _error == null)
        const MintTickInfoText('No NSE stock matches this search.')
      else
        for (final (index, stock) in _stocks.indexed)
          Material(
            color: Colors.transparent,
            child: Semantics(
              button: true,
              label: 'Open ${stock.name.trim().isEmpty ? stock.tradingSymbol : stock.name} details',
              child: InkWell(
                onTap: () => _openDetail(stock),
                child: _StockRow(stock: stock, quote: _rows[stock.id], status: statuses[index]),
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
          label: Text(_loadingMore ? 'Loading stocks…' : 'Load more stocks'),
        )),
      ],
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
      return (text: 'No verified last price has been saved for these stocks yet.',
        icon: Icons.history_rounded);
    }
    if (statuses.every((s) => s == 'LAST_CLOSE')) {
      // Saved ticks alone cannot establish the exchange's open/closed state.
      return (text: 'Showing last verified saved prices · not live.', icon: Icons.history_rounded);
    }
    if (statuses.every((s) => s == 'MARKET_CLOSED' || s == 'LAST_CLOSE')) {
      return (text: 'Showing the last verified price wherever available.',
        icon: Icons.history_rounded);
    }
    if (statuses.contains('LAST_CLOSE')) {
      return (text: 'Some saved prices are available; others have no verified quote.',
        icon: Icons.history_rounded);
    }
    if (feedFailed) {
      return (text: 'Market feed request failed. No verified saved prices yet; retrying.',
        icon: Icons.cloud_off_outlined);
    }
    if (statuses.any((s) => s == 'CONNECTING' || s == 'RECONNECTING' || s == 'PENDING' || s == 'STALE')) {
      return (text: 'Connecting to the live feed. Prices stay hidden until verified.',
        icon: Icons.sync_rounded);
    }
    return (text: 'Live prices are unavailable and no verified snapshot is saved.',
      icon: Icons.cloud_off_outlined);
  }

  void _showCoverage() {
    final facets = _facets;
    final coverage = _fullQuoteCoverage;
    final summary = _summary;
    showModalBottomSheet<void>(
      context: context,
      showDragHandle: true,
      builder: (ctx) {
        final theme = Theme.of(ctx);
        final muted = theme.textTheme.bodySmall?.copyWith(
            color: theme.colorScheme.onSurfaceVariant, height: 1.45);
        final nse = coverage?.exchanges.where((e) => e.exchange == 'NSE').firstOrNull;
        return SafeArea(child: Padding(
          padding: const EdgeInsets.fromLTRB(20, 0, 20, 20),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, mainAxisSize: MainAxisSize.min,
              children: [
            Text('Data coverage', style: theme.textTheme.titleLarge),
            const SizedBox(height: 10),
            if (facets != null)
              Text('${facets.exchanges['NSE'] ?? 0} active NSE cash-equity listings.', style: muted),
            if (nse != null)
              Text('Stored FULL quotes ${nse.storedFullQuotes}/${nse.activeListings} · not live',
                  key: const ValueKey('market-full-quote-coverage'), style: muted),
            if (summary != null) ...[
              Text('${summary.segment('ALL')?.quoted ?? 0} stocks have a verified quote from the '
                  'latest session${summary.freshness.sessionDate == null ? ''
                      : ' (${isoDate(summary.freshness.sessionDate!)})'}.', style: muted),
              for (final s in summary.segments.where((s) => s.segment != 'ALL'))
                Text('${s.label}: ${s.available ? '${s.quoted}/${s.members} quoted · list as of '
                    '${s.membershipAsOf == null ? 'unknown' : isoDate(s.membershipAsOf!)}'
                    : 'official list not imported'}', style: muted),
            ],
            const SizedBox(height: 10),
            Text('Prices are Angel One FULL quotes verified against the NSE trading calendar. '
                'MintTick lists NSE only; BSE duplicates are not shown.', style: muted),
          ]),
        ));
      },
    );
  }

  String _freshnessText(CustomerBoardFreshness f) {
    final at = f.asOf;
    return switch (f.state) {
      'LIVE' => 'Updated ${at == null ? '' : clockSeconds(at.toLocal())} · quotes refresh about every minute',
      'DELAYED' => 'Delayed · newest quote ${at == null ? '' : dayClock(at.toLocal())}',
      'LAST_SESSION' => 'Last session · as of ${at == null ? '' : dayClock(at.toLocal())} · not live',
      _ => 'No verified quotes stored yet',
    };
  }
}

/// Feed status from stored overview evidence; not an exchange session indicator.
bool _overviewHasCurrentIdeaEvidence(CustomerMarketOverview overview) {
  if (overview.source == 'STORED_LAST_OVERVIEW') return false;
  if (!overview.indices.any((index) => index.freshness == 'LIVE')) return false;
  final tick = overview.exchangeAsOf;
  if (tick == null) return false;
  final age = overview.generatedAt.difference(tick);
  return !age.isNegative && age <= const Duration(minutes: 2);
}

// -----------------------------------------------------------------------------
// Private widgets
// -----------------------------------------------------------------------------

/// Exchange session state from the backend trading calendar.
class _SessionChip extends StatelessWidget {
  const _SessionChip({required this.status});
  final CustomerMarketStatus status;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final (label, color) = switch (status.state) {
      'OPEN' => ('Market open', scheme.primary),
      'PRE_OPEN' => ('Pre-open', scheme.tertiary),
      'CLOSED' => ('Closed', scheme.onSurfaceVariant),
      _ => ('Session unknown', scheme.onSurfaceVariant),
    };
    return Semantics(
      label: 'NSE session: $label',
      child: ExcludeSemantics(
        child: Container(
          key: const ValueKey('market-session-chip'),
          padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 4),
          decoration: BoxDecoration(
            color: color.withValues(alpha: 0.1),
            borderRadius: BorderRadius.circular(999),
          ),
          child: Row(mainAxisSize: MainAxisSize.min, children: [
            Icon(Icons.circle, size: 7, color: color),
            const SizedBox(width: 5),
            Text(label, style: theme.textTheme.labelSmall?.copyWith(
                color: color, fontWeight: FontWeight.w800)),
          ]),
        ),
      ),
    );
  }
}

class _MarketStatusPill extends StatelessWidget {
  const _MarketStatusPill({required this.overview, required this.failed});
  final CustomerMarketOverview? overview;
  final bool failed;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final indices = overview?.indices ?? const <CustomerIndexRow>[];
    final label = switch ((overview, failed)) {
      (null, true) => 'Feed unavailable',
      (null, false) => 'Checking feed',
      _ when overview?.source == 'STORED_LAST_OVERVIEW' => 'Stored snapshot',
      _ when indices.any((i) => i.freshness == 'LIVE') => 'Live quotes',
      _ when indices.any((i) => i.freshness == 'STALE') => 'Stale quotes',
      _ when indices.isNotEmpty => 'Last recorded quotes',
      _ => 'Feed status unknown',
    };
    final live = label == 'Live quotes';
    final color = live ? scheme.primary : scheme.onSurfaceVariant;
    return Semantics(
      container: true,
      label: 'Verified quote status: $label; not an exchange session indicator',
      child: ExcludeSemantics(
        child: Row(
          key: const ValueKey('customer-market-status-pill'),
          mainAxisSize: MainAxisSize.min, children: [
          Icon(live ? Icons.sensors_rounded : Icons.history_rounded, size: 12, color: color),
          const SizedBox(width: 4),
          Flexible(child: Text('Market feed · $label', maxLines: 1, overflow: TextOverflow.ellipsis,
              style: theme.textTheme.labelSmall?.copyWith(color: color, fontWeight: FontWeight.w700))),
        ]),
      ),
    );
  }
}

class _MarketMoodCard extends StatelessWidget {
  const _MarketMoodCard({required this.all, required this.freshness});
  final CustomerSegmentSummary all;
  final CustomerBoardFreshness freshness;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final b = all.breadth;
    final ratio = b.decliners == 0 ? null : b.advancers / b.decliners;
    final mood = b.total == 0 ? 'No quotes yet'
        : ratio == null || ratio >= 1.5 ? 'Broad buying'
        : ratio >= 1.05 ? 'Mildly positive'
        : ratio > 0.95 ? 'Mixed'
        : ratio > 0.66 ? 'Mildly negative' : 'Broad selling';
    final moodColor = b.advancers > b.decliners ? scheme.primary
        : b.decliners > b.advancers ? scheme.error : scheme.onSurfaceVariant;
    final at = freshness.asOf;
    final when = switch (freshness.state) {
      'LIVE' => 'Live · ${at == null ? '' : clockText(at.toLocal())}',
      'DELAYED' => 'Delayed · ${at == null ? '' : clockText(at.toLocal())}',
      'LAST_SESSION' => at == null ? 'Last session' : 'Last session · ${dayClock(at.toLocal())}',
      _ => 'No verified quotes',
    };
    return Semantics(
      container: true,
      label: 'NSE market breadth: $mood. ${b.advancers} advancing, ${b.decliners} declining, '
          '${b.unchanged} unchanged of ${b.total} quoted stocks. $when.',
      child: ExcludeSemantics(
        child: Container(
          key: const ValueKey('customer-market-breadth'),
          padding: const EdgeInsets.fromLTRB(14, 12, 14, 12),
          decoration: BoxDecoration(
            color: scheme.surfaceContainerLow,
            borderRadius: BorderRadius.circular(16),
            border: Border.all(color: scheme.outlineVariant.withValues(alpha: 0.8)),
          ),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Row(children: [
              Expanded(child: Text('NSE market mood', style: theme.textTheme.labelMedium
                  ?.copyWith(color: scheme.onSurfaceVariant, fontWeight: FontWeight.w700))),
              Flexible(child: Text(when, maxLines: 1, overflow: TextOverflow.ellipsis,
                  style: theme.textTheme.labelSmall?.copyWith(color: scheme.onSurfaceVariant))),
            ]),
            const SizedBox(height: 4),
            Row(crossAxisAlignment: CrossAxisAlignment.end, children: [
              Expanded(child: Text(mood, maxLines: 1, overflow: TextOverflow.ellipsis,
                  style: theme.textTheme.titleLarge?.copyWith(
                  color: moodColor, fontWeight: FontWeight.w800, letterSpacing: -0.3))),
              if (all.medianChangePct != null)
                Text('Median ${signedPct(all.medianChangePct!)}', style: theme.textTheme.labelMedium
                    ?.copyWith(fontWeight: FontWeight.w700,
                        color: directionColor(scheme, all.medianChangePct!))),
            ]),
            const SizedBox(height: 10),
            BreadthBar(advancers: b.advancers, decliners: b.decliners, unchanged: b.unchanged),
            const SizedBox(height: 6),
            Text('${formatInr(b.total, decimals: 0)} of ${formatInr(all.members, decimals: 0)} '
                'NSE stocks quoted this session',
                style: theme.textTheme.labelSmall?.copyWith(color: scheme.onSurfaceVariant)),
          ]),
        ),
      ),
    );
  }
}

class _SegmentGrid extends StatelessWidget {
  const _SegmentGrid({required this.segments, required this.onOpen});
  final List<CustomerSegmentSummary> segments;
  final ValueChanged<String> onOpen;

  @override
  Widget build(BuildContext context) {
    final rows = <Widget>[];
    for (var i = 0; i < segments.length; i += 2) {
      rows.add(Padding(
        padding: const EdgeInsets.only(bottom: 8),
        child: IntrinsicHeight(child: Row(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          Expanded(child: SegmentTile(segment: segments[i],
              onTap: () => onOpen(segments[i].segment))),
          const SizedBox(width: 8),
          Expanded(child: i + 1 < segments.length
              ? SegmentTile(segment: segments[i + 1], onTap: () => onOpen(segments[i + 1].segment))
              : const SizedBox.shrink()),
        ])),
      ));
    }
    return Column(children: rows);
  }
}

class _SectorGrid extends StatelessWidget {
  const _SectorGrid({required this.children});
  final List<Widget> children;

  @override
  Widget build(BuildContext context) {
    final width = MediaQuery.sizeOf(context).width;
    final columns = width < 360 ? 2 : width < 600 ? 3 : 4;
    return GridView(
      gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
        crossAxisCount: columns, mainAxisSpacing: 8, crossAxisSpacing: 8, mainAxisExtent: 104),
      shrinkWrap: true,
      physics: const NeverScrollableScrollPhysics(),
      padding: EdgeInsets.zero,
      children: children,
    );
  }
}

class _SectorBreadthRow extends StatelessWidget {
  const _SectorBreadthRow({required this.sector, required this.onTap});
  final CustomerBoardSector sector;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    return InkWell(
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 8),
        child: Row(children: [
          Expanded(flex: 5, child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(sector.sector, maxLines: 1, overflow: TextOverflow.ellipsis,
                style: theme.textTheme.bodyMedium?.copyWith(fontWeight: FontWeight.w700)),
            Text('${sector.quoted} stocks'
                '${sector.leader == null ? '' : ' · best ${sector.leader!.replaceFirst(RegExp(r'-EQ$'), '')}'}',
                maxLines: 1, overflow: TextOverflow.ellipsis,
                style: theme.textTheme.labelSmall?.copyWith(color: scheme.onSurfaceVariant)),
          ])),
          const SizedBox(width: 10),
          Expanded(flex: 3, child: BreadthBar(advancers: sector.advancers,
              decliners: sector.decliners,
              unchanged: math.max(0, sector.quoted - sector.advancers - sector.decliners),
              height: 5, showCounts: false)),
          const SizedBox(width: 10),
          ChangePill(pct: sector.avgChangePct, dense: true),
        ]),
      ),
    );
  }
}

class _MoverCarousel extends StatelessWidget {
  const _MoverCarousel({required this.title, required this.board, required this.onOpen,
    required this.empty});
  final String title;
  final CustomerBoard? board;
  final ValueChanged<CustomerBoardRow> onOpen;
  final String empty;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final rows = board?.rows ?? const <CustomerBoardRow>[];
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Text(title, style: theme.textTheme.labelLarge?.copyWith(fontWeight: FontWeight.w800)),
      const SizedBox(height: 6),
      if (board != null && !board!.available)
        const _EmptyLine('This segment\'s official list has not been imported yet.')
      else if (rows.isEmpty)
        _EmptyLine(empty)
      else
        SizedBox(
          height: 120,
          child: ListView.separated(
            scrollDirection: Axis.horizontal,
            itemCount: rows.length,
            separatorBuilder: (_, _) => const SizedBox(width: 8),
            itemBuilder: (context, i) => BoardMoverCard(row: rows[i], onTap: () => onOpen(rows[i])),
          ),
        ),
    ]);
  }
}

class _BoardFooter extends StatelessWidget {
  const _BoardFooter({required this.summary, required this.onCoverage});
  final CustomerBoardSummary summary;
  final VoidCallback onCoverage;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final f = summary.freshness;
    final at = f.asOf;
    return InkWell(
      key: const ValueKey('market-coverage-info'),
      onTap: onCoverage,
      borderRadius: BorderRadius.circular(8),
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 6),
        child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Icon(Icons.info_outline_rounded, size: 14, color: scheme.onSurfaceVariant),
          const SizedBox(width: 6),
          Expanded(child: Text(
            'Verified Angel One quotes stored by MintTick'
            '${at == null ? '' : ' · as of ${dayClock(at.toLocal())}'}'
            '${f.state == 'LIVE' ? '' : ' · not live'}. Tap for data coverage.',
            style: theme.textTheme.labelSmall?.copyWith(color: scheme.onSurfaceVariant, height: 1.4),
          )),
        ]),
      ),
    );
  }
}

class _EmptyLine extends StatelessWidget {
  const _EmptyLine(this.text);
  final String text;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 10),
    child: Text(text, style: Theme.of(context).textTheme.bodySmall
        ?.copyWith(color: Theme.of(context).colorScheme.onSurfaceVariant)),
  );
}

/// Keep feed limitations readable without a full-width card.
class _MarketFeedLine extends StatelessWidget {
  const _MarketFeedLine({required this.text, required this.icon});
  final String text;
  final IconData icon;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Row(children: [
      Icon(icon, size: 14, color: scheme.onSurfaceVariant),
      const SizedBox(width: 6),
      Expanded(child: Text(text, maxLines: 2, overflow: TextOverflow.ellipsis,
        style: Theme.of(context).textTheme.labelSmall?.copyWith(color: scheme.onSurfaceVariant))),
    ]);
  }
}

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

({String label, Color color}) _statusStyle(String status, ColorScheme scheme) =>
    switch (status) {
      'LIVE' => (label: 'LIVE', color: scheme.primary),
      'STALE' => (label: 'STALE', color: scheme.tertiary),
      'RECONNECTING' => (label: 'RECONNECTING', color: scheme.tertiary),
      'CONNECTING' => (label: 'CONNECTING', color: scheme.onSurfaceVariant),
      'MARKET_CLOSED' => (label: 'NO PRICE', color: scheme.onSurfaceVariant),
      'LAST_CLOSE' => (label: 'LAST TRADED', color: scheme.onSurfaceVariant),
      'PENDING' => (label: 'LOADING', color: scheme.onSurfaceVariant),
      _ => (label: 'NO QUOTE', color: scheme.onSurfaceVariant),
    };

/// Dense NSE stock row; status and source timestamp remain visible.
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
    final symbol = stock.tradingSymbol.replaceFirst(RegExp(r'-EQ$'), '');
    final name = stock.name.trim().isEmpty ? symbol : stock.name;
    final isLast = status == 'LAST_CLOSE';
    final price = status == 'LIVE' || isLast ? quote?.ltp : null;
    final tickAt = status == 'LIVE' || isLast ? quote?.exchangeAt : null;
    final priceText = price == null ? '—' : formatPrice(price);
    final change = isLast ? quote?.changePercent : null;
    final statusLine = tickAt == null
        ? style.label
        : isLast
            ? '${style.label} · ${dayClock(tickAt.toLocal())}'
            : '${style.label} · ${clockSeconds(tickAt.toLocal())}';
    return Semantics(
      container: true,
      label: '$name, ${stock.tradingSymbol} ${stock.exchange}. '
          '${price == null ? 'No verified price' : '${isLast ? 'Last traded' : 'Price'} $priceText'}. '
          '${change == null ? '' : 'Change ${signedPct(change)}. '}'
          'Status ${style.label.toLowerCase()}.',
      child: ExcludeSemantics(
        child: Container(
          key: ValueKey('customer-market-stock-${stock.id}'),
          constraints: const BoxConstraints(minHeight: 58),
          padding: const EdgeInsets.symmetric(vertical: 8),
          decoration: BoxDecoration(border: Border(bottom: BorderSide(
              color: scheme.outlineVariant.withValues(alpha: 0.45)))),
          child: Row(children: [
            MintTickEquityAvatar(tradingSymbol: stock.tradingSymbol,
                companyName: stock.name, size: 36),
            const SizedBox(width: 10),
            Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(symbol, maxLines: 1, overflow: TextOverflow.ellipsis,
                  style: theme.textTheme.bodyMedium?.copyWith(fontWeight: FontWeight.w800)),
              const SizedBox(height: 1),
              Text(name, maxLines: 1, overflow: TextOverflow.ellipsis,
                  style: theme.textTheme.labelSmall?.copyWith(color: scheme.onSurfaceVariant)),
              const SizedBox(height: 1),
              Text(statusLine, maxLines: 1, overflow: TextOverflow.ellipsis,
                style: theme.textTheme.labelSmall?.copyWith(
                  color: style.color, fontWeight: FontWeight.w700, fontSize: 10.5)),
            ])),
            const SizedBox(width: 8),
            Column(crossAxisAlignment: CrossAxisAlignment.end, children: [
              Text(priceText,
                key: ValueKey('customer-market-price-${stock.id}'),
                maxLines: 1,
                style: theme.textTheme.bodyMedium?.copyWith(
                  fontWeight: FontWeight.w800,
                  fontFeatures: const [FontFeature.tabularFigures()],
                  color: price == null ? scheme.onSurfaceVariant : scheme.onSurface,
                )),
              if (change != null) ...[
                const SizedBox(height: 3),
                ChangePill(pct: change, dense: true),
              ],
            ]),
          ]),
        ),
      ),
    );
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
      'quote. Constituent list as of ${isoDate(universe.sourceAsOf)}. Breadth, movers and '
      'sectors here are not a whole-market scan.',
      style: theme.textTheme.labelSmall?.copyWith(color: theme.colorScheme.onSurfaceVariant,
          height: 1.4),
    );
  }
}

/// Nifty 50 overview mover list (fallback when the whole-market board is unavailable).
class _MoverSection extends StatelessWidget {
  const _MoverSection({required this.rows, required this.emptyText,
    required this.pctOf, required this.pctLabel, required this.onOpen});
  final List<CustomerMover> rows;
  final String emptyText;
  final num Function(CustomerMover) pctOf;
  final String pctLabel;
  final void Function(CustomerMover) onOpen;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    if (rows.isEmpty) return _EmptyLine(emptyText);
    return Column(children: [
      for (final mover in rows)
        Semantics(
          container: true,
          button: true,
          onTap: () => onOpen(mover),
          label: '${mover.companyName.isEmpty ? mover.tradingSymbol : mover.companyName}, '
              'price ${mover.ltp.toStringAsFixed(2)}, $pctLabel ${signedPct(pctOf(mover))}, '
              'exchange time ${clockText(mover.exchangeAt.toLocal())}. Opens stock detail.',
          child: ExcludeSemantics(
            child: InkWell(
              onTap: () => onOpen(mover),
              child: Container(
                padding: const EdgeInsets.symmetric(vertical: 8),
                decoration: BoxDecoration(border: Border(bottom: BorderSide(
                    color: scheme.outlineVariant.withValues(alpha: 0.45)))),
                child: Row(children: [
                  MintTickEquityAvatar(tradingSymbol: mover.tradingSymbol,
                      companyName: mover.companyName, size: 36),
                  const SizedBox(width: 10),
                  Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    Text(mover.companyName.isEmpty ? mover.tradingSymbol : mover.companyName,
                        maxLines: 1, overflow: TextOverflow.ellipsis,
                        style: theme.textTheme.bodyMedium?.copyWith(fontWeight: FontWeight.w700)),
                    Text('${mover.tradingSymbol.replaceFirst(RegExp(r'-EQ$'), '')} · '
                        '${shortSector(mover.sector)}',
                        maxLines: 1, overflow: TextOverflow.ellipsis,
                        style: theme.textTheme.labelSmall?.copyWith(color: scheme.onSurfaceVariant)),
                  ])),
                  const SizedBox(width: 8),
                  Column(crossAxisAlignment: CrossAxisAlignment.end, children: [
                    Text(formatPrice(mover.ltp), style: theme.textTheme.bodyMedium?.copyWith(
                        fontWeight: FontWeight.w800,
                        fontFeatures: const [FontFeature.tabularFigures()])),
                    Text('$pctLabel ${signedPct(pctOf(mover))}', style: theme.textTheme.labelSmall
                        ?.copyWith(color: directionColor(scheme, pctOf(mover)),
                            fontWeight: FontWeight.w800)),
                  ]),
                ]),
              ),
            ),
          ),
        ),
    ]);
  }
}

class _SectorCard extends StatelessWidget {
  const _SectorCard({required this.sector, required this.scale});
  final CustomerSectorSummary sector;
  final double scale;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final color = directionColor(scheme, sector.avgChangePct);
    final fraction = scale <= 0 ? 0.0
        : (sector.avgChangePct.abs() / scale).clamp(0.0, 1.0).toDouble();
    return Semantics(
      container: true,
      label: '${sector.sector}, ${sector.members} stocks, ${sector.advancers} up, '
          '${sector.decliners} down, average change ${signedPct(sector.avgChangePct)}.',
      child: ExcludeSemantics(
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: 7),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Row(children: [
              Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text(sector.sector, maxLines: 1, overflow: TextOverflow.ellipsis,
                    style: theme.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w700)),
                Text('${sector.members} stocks · ${sector.advancers} up · ${sector.decliners} down',
                    style: theme.textTheme.labelSmall?.copyWith(color: scheme.onSurfaceVariant)),
              ])),
              Text('Avg ${signedPct(sector.avgChangePct)}', style: theme.textTheme.labelMedium
                  ?.copyWith(color: color, fontWeight: FontWeight.w800)),
            ]),
            const SizedBox(height: 5),
            ClipRRect(
              borderRadius: BorderRadius.circular(4),
              child: LinearProgressIndicator(value: fraction, minHeight: 3, color: color,
                  backgroundColor: scheme.outlineVariant),
            ),
          ]),
        ),
      ),
    );
  }
}

/// Rule-screen hits from the backend. Labels describe why a stock matched; they are not advice.
class _IdeasSection extends StatelessWidget {
  const _IdeasSection({required this.ideas, required this.onOpen, required this.enabled});
  final List<CustomerMarketIdea> ideas;
  final Future<void> Function(String symbol) onOpen;
  final bool enabled;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Row(children: [
        Expanded(child: Text('Market ideas', style: theme.textTheme.titleMedium
            ?.copyWith(fontWeight: FontWeight.w800))),
        Flexible(child: Text('Rule screen · Nifty 50', maxLines: 1, overflow: TextOverflow.ellipsis,
            style: theme.textTheme.labelSmall?.copyWith(color: scheme.onSurfaceVariant))),
      ]),
      const SizedBox(height: 6),
      if (!enabled)
        Text('Current market ideas paused · verified live ticks required.',
            style: theme.textTheme.labelSmall?.copyWith(color: scheme.onSurfaceVariant))
      else if (ideas.isEmpty)
        Text('No Nifty 50 stock meets a screen rule right now. Screens are rules on '
            'verified quotes, not recommendations.',
            style: theme.textTheme.labelSmall?.copyWith(color: scheme.onSurfaceVariant))
      else
        for (final idea in ideas)
          InkWell(
            key: ValueKey('market-idea-${idea.tradingSymbol}'),
            onTap: () => unawaited(onOpen(idea.tradingSymbol)),
            child: Padding(
              padding: const EdgeInsets.symmetric(vertical: 8),
              child: Row(children: [
                MintTickEquityAvatar(tradingSymbol: idea.tradingSymbol,
                    companyName: idea.companyName, size: 32),
                const SizedBox(width: 10),
                Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Text(idea.tradingSymbol.replaceFirst(RegExp(r'-EQ$'), ''), maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: theme.textTheme.bodyMedium?.copyWith(fontWeight: FontWeight.w800)),
                  const SizedBox(height: 3),
                  _SignalChip(signal: idea.signal),
                ])),
                const SizedBox(width: 8),
                Column(crossAxisAlignment: CrossAxisAlignment.end, children: [
                  Text(formatPrice(idea.ltp), style: theme.textTheme.bodyMedium
                      ?.copyWith(fontWeight: FontWeight.w700)),
                  ChangePill(pct: idea.changePct, dense: true),
                ]),
              ]),
            ),
          ),
    ]);
  }
}

class _SignalChip extends StatelessWidget {
  const _SignalChip({required this.signal});
  final String signal;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final (label, icon) = switch (signal) {
      'BREAKOUT' => ('Breakout', Icons.flag_outlined),
      'MOMENTUM' => ('Momentum', Icons.bolt_rounded),
      _ => ('Gap up', Icons.north_east_rounded),
    };
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 3),
      decoration: BoxDecoration(borderRadius: BorderRadius.circular(999),
          border: Border.all(color: scheme.outlineVariant)),
      child: Row(mainAxisSize: MainAxisSize.min, children: [
        Icon(icon, size: 12, color: scheme.onSurfaceVariant),
        const SizedBox(width: 3),
        Text(label, style: theme.textTheme.labelSmall
            ?.copyWith(color: scheme.onSurfaceVariant, fontWeight: FontWeight.w700)),
      ]),
    );
  }
}

// -----------------------------------------------------------------------------
// Pushed pages
// -----------------------------------------------------------------------------

/// Full-screen NSE search with debounce.
class _MarketSearchPage extends StatefulWidget {
  const _MarketSearchPage({required this.repository, required this.onOpen});
  final CustomerMarketRepository repository;
  final ValueChanged<InstrumentRecord> onOpen;

  @override
  State<_MarketSearchPage> createState() => _MarketSearchPageState();
}

class _MarketSearchPageState extends State<_MarketSearchPage> {
  final _controller = TextEditingController();
  Timer? _debounce;
  int _seq = 0;
  bool _searching = false;
  String? _error;
  List<InstrumentRecord>? _hits;

  @override
  void dispose() {
    _debounce?.cancel();
    _controller.dispose();
    super.dispose();
  }

  void _changed(String _) {
    _debounce?.cancel();
    _debounce = Timer(const Duration(milliseconds: 300), () => unawaited(_search()));
  }

  Future<void> _search() async {
    _debounce?.cancel();
    final query = _controller.text.trim();
    final seq = ++_seq;
    if (query.isEmpty) {
      setState(() {
        _hits = null;
        _error = null;
        _searching = false;
      });
      return;
    }
    setState(() {
      _searching = true;
      _error = null;
    });
    try {
      final page = await widget.repository.search(query: query, exchange: 'NSE');
      if (!mounted || seq != _seq) return;
      setState(() {
        _hits = page.content;
        _searching = false;
      });
    } catch (_) {
      if (!mounted || seq != _seq) return;
      setState(() {
        _hits = null;
        _error = 'Search failed. Try again.';
        _searching = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final hits = _hits;
    return Scaffold(
      appBar: AppBar(
        titleSpacing: 0,
        title: TextField(
          key: const ValueKey('customer-market-overview-search'),
          controller: _controller,
          autofocus: true,
          textInputAction: TextInputAction.search,
          inputFormatters: [
            LengthLimitingTextInputFormatter(100),
            FilteringTextInputFormatter.allow(RegExp(r'[A-Za-z0-9 .&/_-]')),
          ],
          onChanged: _changed,
          onSubmitted: (_) => unawaited(_search()),
          decoration: const InputDecoration(
            hintText: 'Search NSE stocks',
            border: InputBorder.none,
            enabledBorder: InputBorder.none,
            focusedBorder: InputBorder.none,
            filled: false,
          ),
        ),
      ),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.fromLTRB(16, 4, 16, 24),
          children: [
            if (_searching) const LinearProgressIndicator(minHeight: 2),
            if (_error != null) MintTickInfoText(_error!, icon: Icons.cloud_off_outlined),
            if (hits == null && !_searching && _error == null)
              Padding(
                padding: const EdgeInsets.only(top: 24),
                child: Text('Type a symbol or company name, e.g. RELIANCE or Tata.',
                    textAlign: TextAlign.center,
                    style: theme.textTheme.bodySmall?.copyWith(color: scheme.onSurfaceVariant)),
              ),
            if (hits != null && hits.isEmpty)
              const MintTickInfoText('No NSE stock matches that search.'),
            if (hits != null)
              for (final stock in hits)
                InkWell(
                  key: ValueKey('overview-search-hit-${stock.id}'),
                  onTap: () => widget.onOpen(stock),
                  child: Padding(
                    padding: const EdgeInsets.symmetric(vertical: 9),
                    child: Row(children: [
                      MintTickEquityAvatar(tradingSymbol: stock.tradingSymbol,
                          companyName: stock.name, size: 34),
                      const SizedBox(width: 10),
                      Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                        Text(stock.tradingSymbol.replaceFirst(RegExp(r'-EQ$'), ''),
                            style: theme.textTheme.bodyMedium?.copyWith(fontWeight: FontWeight.w800)),
                        Text(stock.name, maxLines: 1, overflow: TextOverflow.ellipsis,
                            style: theme.textTheme.labelSmall?.copyWith(color: scheme.onSurfaceVariant)),
                      ])),
                      Text(stock.exchange, style: theme.textTheme.labelSmall
                          ?.copyWith(color: scheme.onSurfaceVariant)),
                      Icon(Icons.chevron_right_rounded, color: scheme.onSurfaceVariant),
                    ]),
                  ),
                ),
          ],
        ),
      ),
    );
  }
}

/// All stocks of one official industry, ranked by change.
class _SectorStocksPage extends StatefulWidget {
  const _SectorStocksPage({required this.repository, required this.sector, required this.onOpen});
  final CustomerMarketBoardRepository repository;
  final String sector;
  final ValueChanged<CustomerBoardRow> onOpen;

  @override
  State<_SectorStocksPage> createState() => _SectorStocksPageState();
}

class _SectorStocksPageState extends State<_SectorStocksPage> {
  CustomerBoard? _board;
  String? _error;

  @override
  void initState() {
    super.initState();
    unawaited(_load());
  }

  Future<void> _load() async {
    try {
      final board = await widget.repository.board(segment: 'ALL', sort: 'CHANGE',
          sector: widget.sector, limit: 100);
      if (mounted) setState(() => _board = board);
    } catch (_) {
      if (mounted) setState(() => _error = 'Could not load ${widget.sector} stocks.');
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final board = _board;
    final rows = board?.rows ?? const <CustomerBoardRow>[];
    final up = rows.where((r) => r.changePct > 0).length;
    final down = rows.where((r) => r.changePct < 0).length;
    return Scaffold(
      appBar: AppBar(title: Text(widget.sector)),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.fromLTRB(16, 4, 16, 24),
          children: [
            if (_error != null) MintTickInfoText(_error!, icon: Icons.cloud_off_outlined),
            if (board == null && _error == null) const LinearProgressIndicator(minHeight: 2),
            if (board != null) ...[
              Text('${rows.length} stocks', style: theme.textTheme.labelMedium),
              const SizedBox(height: 8),
              BreadthBar(advancers: up, decliners: down, unchanged: rows.length - up - down),
              const SizedBox(height: 8),
              for (final row in rows)
                BoardStockRow(row: row, onTap: () => widget.onOpen(row)),
            ],
          ],
        ),
      ),
    );
  }
}

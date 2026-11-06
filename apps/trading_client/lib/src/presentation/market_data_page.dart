import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../core/widgets/responsive_layout.dart';
import '../data/authenticated_api_client.dart';
import '../data/instrument_repository.dart';
import '../data/market_data_repository.dart';

/// A catalogue-first Markets experience. Quotes are not invented: the current
/// backend contract supplies instruments and historical candles, not live LTPs.
class MarketDataPage extends StatefulWidget {
  const MarketDataPage({required this.repository, this.isAuthenticated = true, super.key});

  final MarketDataRepository repository;
  final bool isAuthenticated;

  @override
  State<MarketDataPage> createState() => _MarketDataPageState();
}

class _MarketDataPageState extends State<MarketDataPage> {
  static const _pageSize = 25;

  final _instrumentQuery = TextEditingController();
  final _listController = ScrollController();
  Timer? _searchDebounce;
  InstrumentCatalogueStatus? _status;
  InstrumentSearchPage? _lastPage;
  final List<InstrumentRecord> _instruments = [];
  InstrumentRecord? _selected;
  String? _exchange;
  String? _error;
  bool _signInRequired = false;
  String? _nextPageError;
  bool _loading = false;
  bool _loadingMore = false;
  int _requestVersion = 0;

  @override
  void initState() {
    super.initState();
    _listController.addListener(_onScroll);
    if (widget.isAuthenticated) _loadInstruments();
  }

  @override
  void dispose() {
    _searchDebounce?.cancel();
    _requestVersion++;
    _listController.dispose();
    _instrumentQuery.dispose();
    super.dispose();
  }

  void _onScroll() {
    if (!_listController.hasClients || _loading || _loadingMore || _nextPageError != null) return;
    if (_listController.position.extentAfter < 260) _loadMore();
  }

  void _onQueryChanged(String _) {
    _searchDebounce?.cancel();
    _searchDebounce = Timer(const Duration(milliseconds: 350), _loadInstruments);
  }

  Future<void> _loadInstruments() async {
    _searchDebounce?.cancel();
    final version = ++_requestVersion;
    final query = _instrumentQuery.text.trim();
    final exchange = _exchange;

    if (_listController.hasClients) _listController.jumpTo(0);
    setState(() {
      _loading = true;
      _loadingMore = false;
      _error = null;
      _signInRequired = false;
      _nextPageError = null;
      _lastPage = null;
      _instruments.clear();
    });

    try {
      // Keep catalogue failures visible, rather than reporting a misleading zero.
      final results = await Future.wait<Object>([
        if (_status == null) widget.repository.instrumentStatus(),
        widget.repository.searchInstruments(
          query: query.isEmpty ? null : query,
          exchange: exchange,
          type: 'EQ',
          page: 0,
          size: _pageSize,
        ),
      ]);
      if (!mounted || version != _requestVersion) return;
      final page = results.last as InstrumentSearchPage;
      setState(() {
        if (results.length == 2) _status = results.first as InstrumentCatalogueStatus;
        _lastPage = page;
        _instruments.addAll(page.content);
      });
    } on ApiAuthenticationRequired {
      if (!mounted || version != _requestVersion) return;
      setState(() {
        _signInRequired = true;
        _error = 'Sign in to load the equity catalogue.';
      });
    } on ApiHttpFailure catch (failure) {
      if (!mounted || version != _requestVersion) return;
      setState(() {
        _signInRequired = failure.statusCode == 401 || failure.statusCode == 403;
        _error = _signInRequired
            ? 'Your account session is required to browse the catalogue.'
            : 'Market data is temporarily unavailable.';
      });
    } catch (_) {
      if (!mounted || version != _requestVersion) return;
      setState(() => _error = 'Market data is temporarily unavailable.');
    } finally {
      if (mounted && version == _requestVersion) setState(() => _loading = false);
    }
  }

  Future<void> _loadMore() async {
    final current = _lastPage;
    if (_loading || _loadingMore || current == null || !current.hasNext) return;
    final version = _requestVersion;
    setState(() {
      _loadingMore = true;
      _nextPageError = null;
    });
    try {
      final result = await widget.repository.searchInstruments(
        query: _instrumentQuery.text.trim().isEmpty ? null : _instrumentQuery.text.trim(),
        exchange: _exchange,
        type: 'EQ',
        page: current.page + 1,
        size: _pageSize,
      );
      if (!mounted || version != _requestVersion) return;
      final ids = _instruments.map((instrument) => instrument.id).toSet();
      setState(() {
        _instruments.addAll(result.content.where((record) => ids.add(record.id)));
        _lastPage = result;
      });
    } catch (_) {
      if (!mounted || version != _requestVersion) return;
      setState(() => _nextPageError = 'More stocks could not be loaded.');
    } finally {
      if (mounted && version == _requestVersion) setState(() => _loadingMore = false);
    }
  }

  void _setExchange(String? exchange) {
    if (_exchange == exchange) return;
    setState(() => _exchange = exchange);
    _loadInstruments();
  }

  void _selectInstrument(InstrumentRecord instrument, {required bool mobile}) {
    if (mobile) {
      Navigator.of(context).push(MaterialPageRoute<void>(
        builder: (_) => _MarketInstrumentDetailPage(
          instrument: instrument,
          repository: widget.repository,
        ),
      ));
    } else {
      setState(() => _selected = instrument);
    }
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Scaffold(
      appBar: AppBar(title: const Text('Markets')),
      body: SafeArea(
        child: !widget.isAuthenticated
            ? const _GuestMarketView()
            : LayoutBuilder(
          builder: (context, constraints) {
            final width = constraints.maxWidth;
            final mobile = width < 900;
            final padding = ResponsiveLayout.horizontalPadding(width);
            return Padding(
              padding: EdgeInsets.fromLTRB(padding, 4, padding, 8),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Semantics(
                    header: true,
                    child: Text(
                      'Explore the market',
                      style: Theme.of(context).textTheme.titleLarge?.copyWith(
                            fontSize: mobile ? 19 : 24,
                            fontWeight: FontWeight.w800,
                            letterSpacing: -0.4,
                          ),
                    ),
                  ),
                  const SizedBox(height: 3),
                  Text(
                    'Find Indian equities and review historical price action.',
                    style: Theme.of(context).textTheme.bodySmall?.copyWith(
                          fontSize: 13,
                          color: scheme.onSurfaceVariant,
                        ),
                    maxLines: 2,
                  ),
                  const SizedBox(height: 10),
                  _buildSearch(scheme),
                  const SizedBox(height: 10),
                  _buildFilters(scheme),
                  const SizedBox(height: 8),
                  Row(
                    children: [
                      Expanded(
                        child: Text(
                          _loading ? 'Loading stocks…' : 'Showing ${_instruments.length} of ${_lastPage?.totalElements ?? '—'} equities',
                          key: const ValueKey('market-list-count'),
                          style: TextStyle(fontSize: 12, color: scheme.onSurfaceVariant),
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                        ),
                      ),
                      const SizedBox(width: 8),
                      Tooltip(
                        message: 'This screen provides an instrument catalogue and historical candles; not live prices.',
                        child: Row(mainAxisSize: MainAxisSize.min, children: [
                          Icon(Icons.info_outline_rounded, size: 15, color: scheme.onSurfaceVariant),
                          const SizedBox(width: 4),
                          Text('No live quotes', style: TextStyle(fontSize: 11, color: scheme.onSurfaceVariant)),
                        ]),
                      ),
                    ],
                  ),
                  if (_error != null) ...[
                    const SizedBox(height: 8),
                    _errorPanel(scheme),
                  ],
                  const SizedBox(height: 5),
                  Text(
                    'Choose a stock to view recent price action.',
                    style: TextStyle(fontSize: 11, color: scheme.onSurfaceVariant),
                  ),
                  const SizedBox(height: 8),
                  Expanded(
                    child: mobile
                        ? _buildInstrumentPane(scheme, mobile: true)
                        : Row(
                            crossAxisAlignment: CrossAxisAlignment.stretch,
                            children: [
                              Expanded(flex: 5, child: _buildInstrumentPane(scheme, mobile: false)),
                              const SizedBox(width: 14),
                              Expanded(
                                flex: 6,
                                child: _selected == null
                                    ? _emptyDetail(scheme)
                                    : _MarketHistoryPane(
                                        key: ValueKey('market-detail-${_selected!.id}'),
                                        instrument: _selected!,
                                        repository: widget.repository,
                                      ),
                              ),
                            ],
                          ),
                  ),
                ],
              ),
            );
          },
        ),
      ),
    );
  }

  Widget _buildSearch(ColorScheme scheme) {
    return TextField(
      key: const ValueKey('market-instrument-search'),
      controller: _instrumentQuery,
      textInputAction: TextInputAction.search,
      inputFormatters: [
        LengthLimitingTextInputFormatter(100),
        FilteringTextInputFormatter.allow(RegExp(r'[A-Za-z0-9 .&/_-]')),
      ],
      onChanged: _onQueryChanged,
      onSubmitted: (_) => _loadInstruments(),
      style: const TextStyle(fontSize: 15),
      decoration: InputDecoration(
        hintText: 'Search symbol or company',
        prefixIcon: const Icon(Icons.search_rounded, size: 21),
        suffixIcon: IconButton(
          key: const ValueKey('market-instrument-search-button'),
          tooltip: 'Search market',
          onPressed: _loading ? null : _loadInstruments,
          icon: const Icon(Icons.arrow_forward_rounded, size: 21),
        ),
        filled: true,
        fillColor: scheme.surfaceContainer,
        contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 12),
      ),
    );
  }

  Widget _buildFilters(ColorScheme scheme) {
    return SingleChildScrollView(
      scrollDirection: Axis.horizontal,
      child: Row(
        children: [
          _filterChip('All stocks', null, scheme),
          const SizedBox(width: 8),
          _filterChip('NSE', 'NSE', scheme),
          const SizedBox(width: 8),
          _filterChip('BSE', 'BSE', scheme),
        ],
      ),
    );
  }

  Widget _filterChip(String label, String? exchange, ColorScheme scheme) {
    final selected = _exchange == exchange;
    return ChoiceChip(
      key: ValueKey('market-exchange-${exchange ?? 'all'}'),
      label: Text(label),
      selected: selected,
      onSelected: (_) => _setExchange(exchange),
      showCheckmark: false,
      backgroundColor: scheme.surfaceContainer,
      selectedColor: scheme.primary,
      labelStyle: TextStyle(
        fontSize: 12.5,
        fontWeight: FontWeight.w700,
        color: selected ? scheme.onPrimary : scheme.onSurface,
      ),
      materialTapTargetSize: MaterialTapTargetSize.padded,
      side: BorderSide(color: selected ? scheme.primary : scheme.outlineVariant),
    );
  }

  Widget _errorPanel(ColorScheme scheme) {
    return Container(
      key: const ValueKey('market-data-error'),
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      decoration: BoxDecoration(
        border: Border.all(color: scheme.error.withValues(alpha: 0.4)),
        borderRadius: BorderRadius.circular(12),
      ),
      child: Row(children: [
        Icon(Icons.cloud_off_rounded, size: 19, color: scheme.error),
        const SizedBox(width: 8),
        Expanded(child: Text(_error!, style: const TextStyle(fontSize: 12.5))),
        TextButton(
          onPressed: _signInRequired
              ? () => Navigator.of(context).pushNamed('/sign-in')
              : _loadInstruments,
          child: Text(_signInRequired ? 'Sign in' : 'Try again'),
        ),
      ]),
    );
  }

  Widget _buildInstrumentPane(ColorScheme scheme, {required bool mobile}) {
    final hasFooter = _lastPage?.hasNext == true || _nextPageError != null || _loadingMore;
    return Container(
      key: const ValueKey('market-data-instruments'),
      decoration: BoxDecoration(
        color: scheme.surface,
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: scheme.outlineVariant.withValues(alpha: 0.6)),
      ),
      clipBehavior: Clip.antiAlias,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(12, 9, 12, 7),
            child: Row(children: [
              Expanded(
                child: Text('Equity catalogue', style: TextStyle(
                  fontSize: 13, fontWeight: FontWeight.w800, color: scheme.onSurface,
                )),
              ),
              Text('PRICE', style: TextStyle(
                fontSize: 10, fontWeight: FontWeight.w700, letterSpacing: 0.6, color: scheme.onSurfaceVariant,
              )),
            ]),
          ),
          Divider(height: 1, color: scheme.outlineVariant),
          if (!_loading && _instruments.isEmpty && _error == null)
            Expanded(
              child: Center(child: Padding(
                padding: const EdgeInsets.all(24),
                child: Text('No matching stocks found.', textAlign: TextAlign.center,
                    style: TextStyle(color: scheme.onSurfaceVariant)),
              )),
            )
          else if (_loading && _instruments.isEmpty)
            const Expanded(child: Center(child: CircularProgressIndicator()))
          else if (_error != null && _instruments.isEmpty)
            Expanded(
              child: Center(
                child: Padding(
                  padding: const EdgeInsets.all(24),
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Icon(_signInRequired ? Icons.lock_outline_rounded : Icons.cloud_off_outlined,
                          size: 30, color: scheme.onSurfaceVariant),
                      const SizedBox(height: 10),
                      Text(
                        _signInRequired ? 'Your market catalogue awaits' : 'Unable to load stocks',
                        style: TextStyle(fontSize: 15, fontWeight: FontWeight.w700, color: scheme.onSurface),
                      ),
                      const SizedBox(height: 6),
                      Text(
                        _signInRequired
                            ? 'Sign in to browse NSE and BSE equities.'
                            : 'Check your connection or try again shortly.',
                        textAlign: TextAlign.center,
                        style: TextStyle(fontSize: 13, color: scheme.onSurfaceVariant),
                      ),
                    ],
                  ),
                ),
              ),
            )
          else
            Expanded(
              child: RefreshIndicator(
                onRefresh: _loadInstruments,
                child: ListView.builder(
                  key: const ValueKey('market-stock-list'),
                  controller: _listController,
                  physics: const AlwaysScrollableScrollPhysics(),
                  itemCount: _instruments.length + (hasFooter ? 1 : 0),
                  itemBuilder: (context, index) {
                    if (index == _instruments.length) return _paginationFooter(scheme);
                    final instrument = _instruments[index];
                    final selected = _selected?.id == instrument.id;
                    return Material(
                      color: selected ? scheme.primary.withValues(alpha: 0.07) : Colors.transparent,
                      child: InkWell(
                        key: ValueKey('market-instrument-${instrument.id}'),
                        onTap: () => _selectInstrument(instrument, mobile: mobile),
                        child: SizedBox(
                          height: 66,
                          child: Padding(
                            padding: const EdgeInsets.symmetric(horizontal: 12),
                            child: Row(children: [
                              Expanded(
                                child: Column(
                                  mainAxisAlignment: MainAxisAlignment.center,
                                  crossAxisAlignment: CrossAxisAlignment.start,
                                  children: [
                                    Text(
                                      instrument.tradingSymbol,
                                      maxLines: 1,
                                      overflow: TextOverflow.ellipsis,
                                      style: TextStyle(fontWeight: FontWeight.w700, fontSize: 14.5, color: scheme.onSurface),
                                    ),
                                    const SizedBox(height: 3),
                                    Text(
                                      '${instrument.name.isEmpty ? 'Company name unavailable' : instrument.name} · ${instrument.exchange}',
                                      maxLines: 1,
                                      overflow: TextOverflow.ellipsis,
                                      style: TextStyle(fontSize: 11.5, color: scheme.onSurfaceVariant),
                                    ),
                                  ],
                                ),
                              ),
                              const SizedBox(width: 8),
                              Text('—', style: TextStyle(
                                fontSize: 16, fontWeight: FontWeight.w600, color: scheme.onSurfaceVariant,
                              )),
                              const SizedBox(width: 5),
                              Icon(Icons.chevron_right_rounded, size: 19, color: scheme.onSurfaceVariant),
                            ]),
                          ),
                        ),
                      ),
                    );
                  },
                ),
              ),
            ),
        ],
      ),
    );
  }

  Widget _paginationFooter(ColorScheme scheme) {
    if (_loadingMore) {
      return const SizedBox(height: 58, child: Center(child: CircularProgressIndicator(strokeWidth: 2)));
    }
    return SizedBox(
      height: 62,
      child: Center(
        child: TextButton(
          key: const ValueKey('market-load-more'),
          onPressed: _loadMore,
          child: Text(_nextPageError ?? 'Load more stocks'),
        ),
      ),
    );
  }

  Widget _emptyDetail(ColorScheme scheme) {
    return Container(
      key: const ValueKey('market-data-candles'),
      alignment: Alignment.center,
      padding: const EdgeInsets.all(24),
      decoration: BoxDecoration(
        border: Border.all(color: scheme.outlineVariant),
        borderRadius: BorderRadius.circular(16),
      ),
      child: Text(
        'Choose a stock to view recent price action.',
        textAlign: TextAlign.center,
        style: TextStyle(color: scheme.onSurfaceVariant, fontSize: 14),
      ),
    );
  }
}

class _MarketInstrumentDetailPage extends StatelessWidget {
  const _MarketInstrumentDetailPage({required this.instrument, required this.repository});
  final InstrumentRecord instrument;
  final MarketDataRepository repository;

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(title: Text(instrument.tradingSymbol)),
        body: SafeArea(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(14),
            child: _MarketHistoryPane(instrument: instrument, repository: repository),
          ),
        ),
      );
}

class _MarketHistoryPane extends StatefulWidget {
  const _MarketHistoryPane({required this.instrument, required this.repository, super.key});
  final InstrumentRecord instrument;
  final MarketDataRepository repository;

  @override
  State<_MarketHistoryPane> createState() => _MarketHistoryPaneState();
}

class _MarketHistoryPaneState extends State<_MarketHistoryPane> {
  MarketCandlePage? _candles;
  String _interval = 'ONE_DAY';
  String? _error;
  bool _loading = true;
  int _generation = 0;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void didUpdateWidget(covariant _MarketHistoryPane oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.instrument.id != widget.instrument.id) {
      _candles = null;
      _load();
    }
  }

  @override
  void dispose() {
    _generation++;
    super.dispose();
  }

  Future<void> _load() async {
    final version = ++_generation;
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final today = DateTime.now();
      final result = await widget.repository.candles(
        instrumentId: widget.instrument.id,
        interval: _interval,
        fromDate: today.subtract(const Duration(days: 30)),
        toDate: today,
        size: 100,
      );
      if (!mounted || version != _generation) return;
      setState(() => _candles = result);
    } catch (_) {
      if (!mounted || version != _generation) return;
      setState(() => _error = 'Price history could not be loaded.');
    } finally {
      if (mounted && version == _generation) setState(() => _loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final candles = _candles;
    return Container(
      key: const ValueKey('market-data-candles'),
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: scheme.surface,
        border: Border.all(color: scheme.outlineVariant.withValues(alpha: 0.7)),
        borderRadius: BorderRadius.circular(16),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('Price action', style: TextStyle(fontSize: 19, fontWeight: FontWeight.w800, color: scheme.onSurface)),
          const SizedBox(height: 4),
          Text(
            '${widget.instrument.name} · ${widget.instrument.exchange}',
            maxLines: 2,
            style: TextStyle(fontSize: 13, color: scheme.onSurfaceVariant),
          ),
          const SizedBox(height: 14),
          DropdownButtonFormField<String>(
            key: const ValueKey('market-data-interval'),
            initialValue: _interval,
            decoration: const InputDecoration(labelText: 'Historical timeframe'),
            items: const [
              DropdownMenuItem(value: 'ONE_DAY', child: Text('1 day')),
              DropdownMenuItem(value: 'ONE_HOUR', child: Text('1 hour')),
              DropdownMenuItem(value: 'FIFTEEN_MINUTE', child: Text('15 minutes')),
              DropdownMenuItem(value: 'FIVE_MINUTE', child: Text('5 minutes')),
            ],
            onChanged: (value) {
              if (value == null) return;
              setState(() {
                _interval = value;
                _candles = null;
              });
              _load();
            },
          ),
          const SizedBox(height: 16),
          if (_loading && candles == null)
            const SizedBox(height: 180, child: Center(child: CircularProgressIndicator()))
          else if (_error != null)
            Row(children: [
              Expanded(child: Text(_error!, style: TextStyle(color: scheme.error))),
              TextButton(onPressed: _load, child: const Text('Retry')),
            ])
          else if (candles == null || candles.items.isEmpty)
            const Padding(
              padding: EdgeInsets.symmetric(vertical: 22),
              child: Text('No price history is available for this stock and timeframe.'),
            )
          else ...[
            Text(
              '${widget.instrument.tradingSymbol} • ${candles.totalItems} points',
              style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w700),
            ),
            const SizedBox(height: 6),
            Text('Historical close · Not a live price', style: TextStyle(
              fontSize: 12, color: scheme.onSurfaceVariant,
            )),
            const SizedBox(height: 8),
            Semantics(
              label: 'Historical close chart with ${candles.items.length} data points',
              image: true,
              child: SizedBox(
                width: double.infinity,
                height: 164,
                child: CustomPaint(
                  painter: _HistoricalClosePainter(
                    candles.items,
                    scheme.primary,
                    scheme.outlineVariant,
                  ),
                ),
              ),
            ),
            const SizedBox(height: 12),
            Text('Latest returned historical candle', style: TextStyle(
              fontSize: 12, color: scheme.onSurfaceVariant,
            )),
            const SizedBox(height: 5),
            Builder(builder: (context) {
              final latest = candles.items.reduce((a, b) =>
                  (a.candleTime ?? DateTime.fromMillisecondsSinceEpoch(0)).isAfter(
                    b.candleTime ?? DateTime.fromMillisecondsSinceEpoch(0)) ? a : b);
              return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text('O ${latest.open}  H ${latest.high}  L ${latest.low}  C ${latest.close}',
                    style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600)),
                const SizedBox(height: 5),
                Text('${latest.volume} vol · ${latest.source.isEmpty ? 'Source unavailable' : latest.source}',
                    style: TextStyle(fontSize: 12, color: scheme.onSurfaceVariant)),
                Text(
                  latest.candleTime == null ? 'Candle time unavailable' : 'Candle: ${latest.candleTime!.toLocal()}',
                  style: TextStyle(fontSize: 11, color: scheme.onSurfaceVariant),
                ),
              ]);
            }),
          ],
        ],
      ),
    );
  }
}

class _HistoricalClosePainter extends CustomPainter {
  const _HistoricalClosePainter(this.records, this.lineColor, this.gridColor);

  final List<MarketCandleRecord> records;
  final Color lineColor;
  final Color gridColor;

  @override
  void paint(Canvas canvas, Size size) {
    final data = records.toList()
      ..sort((a, b) => (a.candleTime ?? DateTime.fromMillisecondsSinceEpoch(0))
          .compareTo(b.candleTime ?? DateTime.fromMillisecondsSinceEpoch(0)));
    if (data.isEmpty) return;
    final closes = data.map((c) => c.close.toDouble()).where((c) => c.isFinite).toList();
    if (closes.isEmpty) return;
    final minValue = closes.reduce(math.min);
    final maxValue = closes.reduce(math.max);
    const inset = 8.0;
    final width = math.max(1.0, size.width - inset * 2);
    final height = math.max(1.0, size.height - inset * 2);
    final grid = Paint()..color = gridColor.withValues(alpha: 0.4)..strokeWidth = 0.75;
    for (var i = 1; i <= 3; i++) {
      final y = inset + height * i / 4;
      canvas.drawLine(Offset(inset, y), Offset(inset + width, y), grid);
    }
    final line = Path();
    for (var i = 0; i < closes.length; i++) {
      final x = inset + width * (closes.length == 1 ? 0.5 : i / (closes.length - 1));
      final y = inset + height * (maxValue == minValue ? 0.5 : (maxValue - closes[i]) / (maxValue - minValue));
      if (i == 0) { line.moveTo(x, y); } else { line.lineTo(x, y); }
    }
    final paint = Paint()..color = lineColor..strokeWidth = 2.25
      ..style = PaintingStyle.stroke..strokeCap = StrokeCap.round
      ..strokeJoin = StrokeJoin.round..isAntiAlias = true;
    canvas.drawPath(line, paint);
    if (closes.length == 1) {
      final pointPaint = Paint()..color = lineColor;
      canvas.drawCircle(Offset(inset + width / 2, inset + height / 2), 3.5, pointPaint);
    }
  }

  @override
  bool shouldRepaint(covariant _HistoricalClosePainter oldDelegate) =>
      oldDelegate.records != records || oldDelegate.lineColor != lineColor || oldDelegate.gridColor != gridColor;
}

class _GuestMarketView extends StatelessWidget {
  const _GuestMarketView();

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    return LayoutBuilder(
      builder: (context, bounds) => SingleChildScrollView(
        padding: const EdgeInsets.fromLTRB(16, 18, 16, 28),
        child: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 560),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Semantics(
                  header: true,
                  child: Text('Explore Indian equities', style: theme.textTheme.headlineSmall),
                ),
                const SizedBox(height: 7),
                Text('Search the NSE and BSE catalogue, then inspect historical price action.',
                    style: theme.textTheme.bodyMedium?.copyWith(color: scheme.onSurfaceVariant)),
                const SizedBox(height: 20),
                Card(
                  child: Padding(
                    padding: const EdgeInsets.all(18),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Icon(Icons.lock_outline_rounded, color: scheme.primary, size: 27),
                        const SizedBox(height: 13),
                        Text('Sign in to browse markets', style: theme.textTheme.titleLarge),
                        const SizedBox(height: 6),
                        Text(
                          'The stock catalogue is linked to your secure account. '
                          'No live prices or simulated quotes are shown while signed out.',
                          style: theme.textTheme.bodyMedium?.copyWith(color: scheme.onSurfaceVariant),
                        ),
                        const SizedBox(height: 16),
                        SizedBox(
                          width: double.infinity,
                          child: FilledButton(
                            key: const ValueKey('market-guest-sign-in'),
                            onPressed: () => Navigator.of(context).pushNamed('/sign-in'),
                            child: const Text('Sign in securely'),
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

import 'package:flutter/material.dart';

import '../core/widgets/responsive_layout.dart';
import '../data/authenticated_api_client.dart';
import '../data/instrument_repository.dart';
import '../data/market_data_repository.dart';
import '../theme/app_theme.dart';

class MarketDataPage extends StatefulWidget {
  const MarketDataPage({required this.repository, super.key});

  final MarketDataRepository repository;

  @override
  State<MarketDataPage> createState() => _MarketDataPageState();
}

class _MarketDataPageState extends State<MarketDataPage> {
  final _instrumentQuery = TextEditingController();
  InstrumentCatalogueStatus? _status;
  InstrumentSearchPage? _instruments;
  InstrumentRecord? _selected;
  MarketCandlePage? _candles;
  String _interval = 'ONE_DAY';
  String? _error;
  bool _loading = false;

  @override
  void initState() {
    super.initState();
    _loadInstruments();
  }

  @override
  void dispose() {
    _instrumentQuery.dispose();
    super.dispose();
  }

  Future<void> _loadInstruments() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final results = await Future.wait([
        widget.repository.instrumentStatus(),
        widget.repository.searchInstruments(
          query: _instrumentQuery.text.trim().isEmpty ? null : _instrumentQuery.text.trim(),
          size: 25,
        ),
      ]);
      if (!mounted) return;
      final page = results[1] as InstrumentSearchPage;
      setState(() {
        _status = results[0] as InstrumentCatalogueStatus;
        _instruments = page;
        if (_selected != null && !page.content.any((item) => item.id == _selected!.id)) {
          _selected = null;
          _candles = null;
        }
      });
    } on ApiFailure {
      if (!mounted) return;
      setState(() => _error = 'Market data is temporarily unavailable.');
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<void> _loadCandles(InstrumentRecord instrument) async {
    setState(() {
      _selected = instrument;
      _loading = true;
      _error = null;
    });
    try {
      final today = DateTime.now();
      final from = today.subtract(const Duration(days: 30));
      final page = await widget.repository.candles(
        instrumentId: instrument.id,
        interval: _interval,
        fromDate: from,
        toDate: today,
        size: 100,
      );
      if (!mounted) return;
      setState(() => _candles = page);
    } on ApiFailure {
      if (!mounted) return;
      setState(() => _error = 'Price history could not be loaded.');
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.extension<TradingColors>();
    final status = _status;
    final instruments = _instruments;

    return Scaffold(
      appBar: AppBar(title: const Text('Markets')),
      body: SafeArea(
        child: LayoutBuilder(
          builder: (context, constraints) {
            final width = constraints.maxWidth;
            final wide = width >= 900;
            final mobile = width < ResponsiveLayout.mobileBreakpoint;
            final pagePadding = ResponsiveLayout.horizontalPadding(width);
            final sectionGap = ResponsiveLayout.sectionGap(width);
            final searchPane = _buildInstrumentPane(context, status, instruments, mobile: mobile);
            final chartPane = _buildCandlePane(context, mobile: mobile);
            return ListView(
              padding: EdgeInsets.fromLTRB(pagePadding, ResponsiveLayout.topPadding(width), pagePadding, 24),
              children: [
                Container(
                  padding: EdgeInsets.all(ResponsiveLayout.heroPadding(width)),
                  decoration: BoxDecoration(
                    borderRadius: BorderRadius.circular(ResponsiveLayout.heroRadius(width)),
                    gradient: LinearGradient(
                      colors: [
                        theme.colorScheme.primaryContainer.withValues(alpha: 0.72),
                        theme.colorScheme.surfaceContainerHighest,
                      ],
                    ),
                    border: Border.all(color: colors?.neonCyan.withValues(alpha: 0.35) ?? theme.colorScheme.outlineVariant),
                  ),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Semantics(
                        header: true,
                        child: Text(
                          'Explore the market',
                          style: (mobile ? theme.textTheme.titleLarge : theme.textTheme.headlineSmall)?.copyWith(fontWeight: FontWeight.w900),
                        ),
                      ),
                      const SizedBox(height: 6),
                      Text(
                        'Search stocks, review price action, then move into AI research.',
                        style: (mobile ? theme.textTheme.bodyMedium : theme.textTheme.bodyLarge)?.copyWith(
                          color: theme.colorScheme.onSurfaceVariant,
                          height: mobile ? 1.3 : 1.45,
                        ),
                      ),
                    ],
                  ),
                ),
                if (_error != null) ...[
                  SizedBox(height: mobile ? 10 : 16),
                  Card(
                    key: const ValueKey('market-data-error'),
                    child: Padding(
                      padding: EdgeInsets.all(mobile ? 12 : 16),
                      child: Row(
                        children: [
                          const Icon(Icons.cloud_off_rounded, size: 20),
                          const SizedBox(width: 10),
                          Expanded(child: Text(_error!)),
                          TextButton(onPressed: _loadInstruments, child: const Text('Try again')),
                        ],
                      ),
                    ),
                  ),
                ],
                SizedBox(height: sectionGap),
                if (wide)
                  Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Expanded(child: searchPane),
                      const SizedBox(width: 16),
                      Expanded(child: chartPane),
                    ],
                  )
                else ...[
                  searchPane,
                  SizedBox(height: sectionGap),
                  chartPane,
                ],
              ],
            );
          },
        ),
      ),
    );
  }

  Widget _buildInstrumentPane(
    BuildContext context,
    InstrumentCatalogueStatus? status,
    InstrumentSearchPage? instruments, {
    required bool mobile,
  }) {
    final theme = Theme.of(context);
    return Card(
      key: const ValueKey('market-data-instruments'),
      child: Padding(
        padding: EdgeInsets.all(mobile ? 12 : 16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Find a stock', style: (mobile ? theme.textTheme.titleMedium : theme.textTheme.titleLarge)?.copyWith(fontWeight: FontWeight.w800)),
            if (status != null) ...[
              const SizedBox(height: 3),
              Text('${status.activeCount} tradable instruments', style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurfaceVariant)),
            ],
            SizedBox(height: mobile ? 8 : 12),
            TextField(
              key: const ValueKey('market-instrument-search'),
              controller: _instrumentQuery,
              textInputAction: TextInputAction.search,
              decoration: const InputDecoration(labelText: 'Symbol or company', prefixIcon: Icon(Icons.search_rounded)),
              onSubmitted: (_) => _loadInstruments(),
            ),
            const SizedBox(height: 8),
            SizedBox(
              width: mobile ? double.infinity : null,
              child: FilledButton.icon(
                key: const ValueKey('market-instrument-search-button'),
                onPressed: _loading ? null : _loadInstruments,
                icon: const Icon(Icons.search_rounded, size: 19),
                label: const Text('Search market'),
              ),
            ),
            SizedBox(height: mobile ? 8 : 12),
            if (_loading && instruments == null)
              const Center(child: Padding(padding: EdgeInsets.all(8), child: CircularProgressIndicator()))
            else if (instruments == null || instruments.content.isEmpty)
              Text('No matching stocks found.', style: theme.textTheme.bodyMedium?.copyWith(color: theme.colorScheme.onSurfaceVariant))
            else
              ...instruments.content.take(mobile ? 6 : 12).map(
                    (instrument) => ListTile(
                      key: ValueKey('market-instrument-${instrument.id}'),
                      dense: true,
                      selected: _selected?.id == instrument.id,
                      title: Text(instrument.tradingSymbol),
                      subtitle: Text('${instrument.exchange} • ${instrument.instrumentType}'),
                      trailing: const Icon(Icons.chevron_right_rounded),
                      onTap: () => _loadCandles(instrument),
                    ),
                  ),
          ],
        ),
      ),
    );
  }

  Widget _buildCandlePane(BuildContext context, {required bool mobile}) {
    final selected = _selected;
    final candles = _candles;
    final theme = Theme.of(context);
    return Card(
      key: const ValueKey('market-data-candles'),
      child: Padding(
        padding: EdgeInsets.all(mobile ? 12 : 16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Price action', style: (mobile ? theme.textTheme.titleMedium : theme.textTheme.titleLarge)?.copyWith(fontWeight: FontWeight.w800)),
            const SizedBox(height: 8),
            DropdownButtonFormField<String>(
              key: const ValueKey('market-data-interval'),
              initialValue: _interval,
              decoration: const InputDecoration(labelText: 'Timeframe'),
              items: const [
                DropdownMenuItem(value: 'ONE_DAY', child: Text('1 day')),
                DropdownMenuItem(value: 'ONE_HOUR', child: Text('1 hour')),
                DropdownMenuItem(value: 'FIFTEEN_MINUTE', child: Text('15 minutes')),
                DropdownMenuItem(value: 'FIVE_MINUTE', child: Text('5 minutes')),
              ],
              onChanged: (value) {
                if (value == null) return;
                setState(() => _interval = value);
                if (selected != null) _loadCandles(selected);
              },
            ),
            const SizedBox(height: 8),
            if (selected == null)
              Text('Choose a stock to view recent price action.', style: theme.textTheme.bodyMedium?.copyWith(color: theme.colorScheme.onSurfaceVariant))
            else if (_loading && candles == null)
              const Center(child: CircularProgressIndicator())
            else if (candles == null || candles.items.isEmpty)
              Text('No price history is available for ${selected.tradingSymbol} in this timeframe.')
            else ...[
              Text('${selected.tradingSymbol} • ${candles.totalItems} points', style: const TextStyle(fontWeight: FontWeight.w800)),
              const SizedBox(height: 8),
              ...candles.items.take(mobile ? 10 : 20).map(
                    (candle) => ListTile(
                      dense: true,
                      title: Text('${candle.candleTime?.toLocal() ?? '—'}'),
                      subtitle: Text('O ${candle.open}  H ${candle.high}  L ${candle.low}  C ${candle.close}'),
                      trailing: Text('${candle.volume} vol'),
                    ),
                  ),
            ],
          ],
        ),
      ),
    );
  }
}

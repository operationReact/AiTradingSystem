import 'package:flutter/material.dart';

import '../data/authenticated_api_client.dart';
import '../data/instrument_repository.dart';
import '../data/market_data_repository.dart';

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
  bool _syncing = false;

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
    } on ApiFailure catch (failure) {
      if (!mounted) return;
      setState(() => _error = failure.code);
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
    } on ApiFailure catch (failure) {
      if (!mounted) return;
      setState(() => _error = failure.code);
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<void> _sync() async {
    setState(() {
      _syncing = true;
      _error = null;
    });
    try {
      await widget.repository.syncInstruments();
      await _loadInstruments();
    } on ApiFailure catch (failure) {
      if (!mounted) return;
      setState(() => _error = failure.code);
    } finally {
      if (mounted) setState(() => _syncing = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final status = _status;
    final instruments = _instruments;

    return Scaffold(
      appBar: AppBar(title: const Text('Market data')),
      body: SafeArea(
        child: LayoutBuilder(
          builder: (context, constraints) {
            final wide = constraints.maxWidth >= 900;
            final searchPane = _buildInstrumentPane(context, status, instruments);
            final evidencePane = _buildCandlePane(context);
            return ListView(
              padding: const EdgeInsets.fromLTRB(20, 20, 20, 40),
              children: [
                Semantics(
                  header: true,
                  child: Text('Authoritative market data', style: theme.textTheme.headlineSmall?.copyWith(fontWeight: FontWeight.w800)),
                ),
                const SizedBox(height: 6),
                const Text('Search the active catalogue and inspect backend candle evidence. No client-generated prices are shown.'),
                if (_error != null) ...[
                  const SizedBox(height: 16),
                  Card(
                    key: const ValueKey('market-data-error'),
                    child: Padding(
                      padding: const EdgeInsets.all(16),
                      child: Text('Market data unavailable: $_error'),
                    ),
                  ),
                ],
                const SizedBox(height: 18),
                if (wide)
                  Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Expanded(child: searchPane),
                      const SizedBox(width: 16),
                      Expanded(child: evidencePane),
                    ],
                  )
                else ...[
                  searchPane,
                  const SizedBox(height: 16),
                  evidencePane,
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
    InstrumentSearchPage? instruments,
  ) {
    return Card(
      key: const ValueKey('market-data-instruments'),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Instrument source', style: Theme.of(context).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w700)),
            const SizedBox(height: 8),
            if (status != null)
              Text('${status.activeCount} active • catalogue ${status.version ?? 'NONE'}'),
            const SizedBox(height: 12),
            TextField(
              key: const ValueKey('market-instrument-search'),
              controller: _instrumentQuery,
              decoration: const InputDecoration(labelText: 'Symbol or name', prefixIcon: Icon(Icons.search)),
              onSubmitted: (_) => _loadInstruments(),
            ),
            const SizedBox(height: 10),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                FilledButton(
                  key: const ValueKey('market-instrument-search-button'),
                  onPressed: _loading ? null : _loadInstruments,
                  child: const Text('Search'),
                ),
                OutlinedButton.icon(
                  key: const ValueKey('market-instrument-sync'),
                  onPressed: _syncing ? null : _sync,
                  icon: _syncing
                      ? const SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2))
                      : const Icon(Icons.sync),
                  label: const Text('Sync catalogue'),
                ),
              ],
            ),
            const SizedBox(height: 12),
            if (_loading && instruments == null)
              const Center(child: CircularProgressIndicator())
            else if (instruments == null || instruments.content.isEmpty)
              const Text('No instruments available for the current search.')
            else
              ...instruments.content.take(12).map(
                    (instrument) => ListTile(
                      key: ValueKey('market-instrument-${instrument.id}'),
                      selected: _selected?.id == instrument.id,
                      title: Text(instrument.tradingSymbol),
                      subtitle: Text('${instrument.exchange} • ${instrument.instrumentType}'),
                      trailing: const Icon(Icons.chevron_right),
                      onTap: () => _loadCandles(instrument),
                    ),
                  ),
          ],
        ),
      ),
    );
  }

  Widget _buildCandlePane(BuildContext context) {
    final selected = _selected;
    final candles = _candles;
    return Card(
      key: const ValueKey('market-data-candles'),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Candle evidence', style: Theme.of(context).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w700)),
            const SizedBox(height: 8),
            DropdownButtonFormField<String>(
              key: const ValueKey('market-data-interval'),
              initialValue: _interval,
              decoration: const InputDecoration(labelText: 'Interval'),
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
            const SizedBox(height: 12),
            if (selected == null)
              const Text('Select an instrument to request candle evidence.')
            else if (_loading && candles == null)
              const Center(child: CircularProgressIndicator())
            else if (candles == null || candles.items.isEmpty)
              Text('No ${_interval.toLowerCase()} candles returned for ${selected.tradingSymbol} in the last 30 days.')
            else ...[
              Text('${selected.tradingSymbol} • ${candles.totalItems} candles', style: const TextStyle(fontWeight: FontWeight.w700)),
              const SizedBox(height: 10),
              ...candles.items.take(20).map(
                    (candle) => ListTile(
                      dense: true,
                      title: Text('${candle.candleTime?.toLocal() ?? 'UNKNOWN'}'),
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

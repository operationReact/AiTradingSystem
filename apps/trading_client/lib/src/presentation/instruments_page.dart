import 'package:flutter/material.dart';

import '../data/authenticated_api_client.dart';
import '../data/instrument_repository.dart';

class InstrumentsPage extends StatefulWidget {
  const InstrumentsPage({required this.repository, super.key});

  final InstrumentRepository repository;

  @override
  State<InstrumentsPage> createState() => _InstrumentsPageState();
}

class _InstrumentsPageState extends State<InstrumentsPage> {
  final _search = TextEditingController();
  InstrumentCatalogueStatus? _status;
  InstrumentSearchPage? _page;
  String? _error;
  bool _loading = false;
  bool _importing = false;
  String? _exchange;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _search.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final results = await Future.wait([
        widget.repository.status(),
        widget.repository.search(
          query: _search.text.trim().isEmpty ? null : _search.text.trim(),
          exchange: _exchange,
        ),
      ]);
      if (!mounted) return;
      setState(() {
        _status = results[0] as InstrumentCatalogueStatus;
        _page = results[1] as InstrumentSearchPage;
      });
    } on ApiFailure catch (failure) {
      if (!mounted) return;
      setState(() => _error = failure.code);
    } catch (_) {
      if (!mounted) return;
      setState(() => _error = 'INSTRUMENT_READ_FAILED');
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<void> _import() async {
    setState(() {
      _importing = true;
      _error = null;
    });
    try {
      await widget.repository.importLatest();
      await _load();
    } on ApiFailure catch (failure) {
      if (!mounted) return;
      setState(() => _error = failure.code);
    } finally {
      if (mounted) setState(() => _importing = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final status = _status;
    final page = _page;

    return Scaffold(
      appBar: AppBar(title: const Text('Instruments')),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.fromLTRB(20, 20, 20, 40),
          children: [
            Semantics(
              header: true,
              child: Text('Instrument catalogue', style: theme.textTheme.headlineSmall?.copyWith(fontWeight: FontWeight.w800)),
            ),
            const SizedBox(height: 6),
            const Text('Search the active backend instrument catalogue. Server state is authoritative.'),
            const SizedBox(height: 18),
            if (_error != null)
              Card(
                key: const ValueKey('instruments-error'),
                child: Padding(
                  padding: const EdgeInsets.all(16),
                  child: Text('Instrument evidence unavailable: $_error'),
                ),
              ),
            if (status != null)
              Card(
                key: const ValueKey('instruments-status'),
                child: Padding(
                  padding: const EdgeInsets.all(16),
                  child: Wrap(
                    spacing: 24,
                    runSpacing: 12,
                    children: [
                      _Metric(label: 'Active instruments', value: '${status.activeCount}'),
                      _Metric(label: 'Catalogue version', value: status.version ?? 'NONE'),
                      _Metric(label: 'Activated', value: status.activatedAt?.toLocal().toString() ?? 'UNKNOWN'),
                    ],
                  ),
                ),
              ),
            const SizedBox(height: 12),
            Wrap(
              spacing: 12,
              runSpacing: 12,
              crossAxisAlignment: WrapCrossAlignment.center,
              children: [
                SizedBox(
                  width: 320,
                  child: TextField(
                    key: const ValueKey('instrument-search-field'),
                    controller: _search,
                    decoration: const InputDecoration(labelText: 'Symbol or name', prefixIcon: Icon(Icons.search)),
                    onSubmitted: (_) => _load(),
                  ),
                ),
                SizedBox(
                  width: 150,
                  child: DropdownButtonFormField<String?>(
                    key: const ValueKey('instrument-exchange-filter'),
                    value: _exchange,
                    decoration: const InputDecoration(labelText: 'Exchange'),
                    items: const [
                      DropdownMenuItem(value: null, child: Text('All')),
                      DropdownMenuItem(value: 'NSE', child: Text('NSE')),
                      DropdownMenuItem(value: 'BSE', child: Text('BSE')),
                      DropdownMenuItem(value: 'NFO', child: Text('NFO')),
                      DropdownMenuItem(value: 'MCX', child: Text('MCX')),
                    ],
                    onChanged: (value) {
                      setState(() => _exchange = value);
                      _load();
                    },
                  ),
                ),
                FilledButton.icon(
                  key: const ValueKey('instrument-search-button'),
                  onPressed: _loading ? null : _load,
                  icon: const Icon(Icons.search),
                  label: const Text('Search'),
                ),
                OutlinedButton.icon(
                  key: const ValueKey('instrument-import-button'),
                  onPressed: _importing ? null : _import,
                  icon: _importing
                      ? const SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2))
                      : const Icon(Icons.sync),
                  label: const Text('Import latest'),
                ),
              ],
            ),
            const SizedBox(height: 18),
            if (_loading && page == null)
              const Center(child: CircularProgressIndicator())
            else if (page != null && page.content.isEmpty)
              const Card(
                key: ValueKey('instruments-empty'),
                child: Padding(padding: EdgeInsets.all(24), child: Text('No instruments matched the current filters.')),
              )
            else if (page != null) ...[
              Text('${page.totalElements} instruments', style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w700)),
              const SizedBox(height: 10),
              ...page.content.map(
                (instrument) => Card(
                  key: ValueKey('instrument-${instrument.id}'),
                  child: ListTile(
                    title: Text(instrument.tradingSymbol.isEmpty ? instrument.name : instrument.tradingSymbol),
                    subtitle: Text(
                      [
                        instrument.name,
                        instrument.exchange,
                        instrument.instrumentType,
                        if (instrument.expiry != null) 'Expiry ${instrument.expiry!.toIso8601String().split('T').first}',
                      ].where((value) => value.isNotEmpty).join(' • '),
                    ),
                    trailing: Text('Lot ${instrument.lotSize}'),
                  ),
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

class _Metric extends StatelessWidget {
  const _Metric({required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) => Semantics(
        label: '$label: $value',
        child: SizedBox(
          width: 220,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(label, style: Theme.of(context).textTheme.labelMedium),
              const SizedBox(height: 4),
              Text(value, maxLines: 2, overflow: TextOverflow.ellipsis, style: const TextStyle(fontWeight: FontWeight.w700)),
            ],
          ),
        ),
      );
}

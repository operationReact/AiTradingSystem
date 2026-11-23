import 'dart:async';

import 'package:flutter/material.dart';

import '../data/customer_fno_universe_repository.dart';

/// Complete exchange-listed NSE_FO identities, NOT 45k simultaneous quotes.
/// On-demand pages and local-master symbol search do not create orders or
/// additional Upstox network calls; all trading is controlled elsewhere.
class MintTickFnoUniversePanel extends StatefulWidget {
  const MintTickFnoUniversePanel({
    required this.repository,
    super.key,
  });
  final CustomerFnoUniverseRepository repository;

  @override
  State<MintTickFnoUniversePanel> createState() =>
      _MintTickFnoUniversePanelState();
}

class _MintTickFnoUniversePanelState extends State<MintTickFnoUniversePanel> {
  String _type = 'All';
  String _query = '';
  int _page = 0;
  int _generation = 0;
  bool _loading = false;
  bool _failed = false;
  bool _invalidResponse = false;
  Timer? _searchDebounce;
  CustomerFnoUniversePage? _result;
  CustomerFnoUniverseStatus? _status;
  CustomerFnoRealtimeCoverage? _coverage;
  DateTime? _coverageFetchedAt;

  @override
  void initState() {
    super.initState();
    unawaited(_load());
  }

  @override
  void dispose() {
    _generation++;
    _searchDebounce?.cancel();
    super.dispose();
  }

  String? get _sourceType => _type == 'All' ? null : _type;

  Future<void> _load({bool refreshCoverage = false}) async {
    final generation = ++_generation;
    setState(() {
      _loading = true;
      _failed = false;
      _invalidResponse = false;
    });
    try {
      final status = await widget.repository.status();
      if (!mounted || generation != _generation) return;
      final CustomerFnoUniversePage? page;
      if (status.usable) {
        page = _query.isEmpty
            ? await widget.repository.contracts(
                type: _sourceType, page: _page)
            : await widget.repository.search(_query,
                type: _sourceType, page: _page);
      } else {
        page = null;
      }
      if (!mounted || generation != _generation) return;
      CustomerFnoRealtimeCoverage? live = _coverage;
      final lastCheck = _coverageFetchedAt;
      if (refreshCoverage || lastCheck == null ||
          DateTime.now().difference(lastCheck) >= const Duration(seconds:10)) {
        try {
          live = await widget.repository.realtimeCoverage();
          if (!mounted || generation != _generation) return;
        } catch (_) {
          // Do not fabricate real time counts or hide a valid BOD catalog
          // when the separately protected feed-coverage path is unavailable.
          live = null;
        }
        _coverageFetchedAt = DateTime.now();
      }
      if (!mounted || generation != _generation) return;
      setState(() {
        _status = status;
        _result = page;
        _coverage = live;
      });
    } catch (error) {
      if (mounted && generation == _generation) {
        setState(() {
          _failed = true;
          _invalidResponse = error is FormatException;
          _result = null;
        });
      }
    } finally {
      if (mounted && generation == _generation) {
        setState(() => _loading = false);
      }
    }
  }

  void _changeType(String next) {
    if (_type == next) return;
    setState(() {
      _type = next;
      _page = 0;
    });
    unawaited(_load());
  }

  void _searchChanged(String input) {
    _searchDebounce?.cancel();
    final normalized = input
        .replaceAll(RegExp(r'[^A-Za-z0-9 ._-]'), '').trim();
    _searchDebounce = Timer(const Duration(milliseconds: 350), () {
      if (!mounted || normalized == _query) return;
      setState(() {
        _query = normalized;
        _page = 0;
      });
      unawaited(_load());
    });
  }

  void _goToPage(int page) {
    if (page < 0 || _loading || page == _page) return;
    setState(() => _page = page);
    unawaited(_load());
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    final status = _status;
    final result = _result;
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      Row(children: [
        Expanded(child: Text('F&O Universe',
          style: text.titleMedium?.copyWith(fontWeight: FontWeight.w800))),
        IconButton(
          key: const ValueKey('fno-universe-refresh'),
          onPressed: _loading ? null
              : () => unawaited(_load(refreshCoverage: true)),
          tooltip: 'Refresh listed instrument catalogue',
          icon: const Icon(Icons.refresh_rounded, size: 19),
        ),
      ]),
      Text('Upstox listed instruments · read only',
        style: text.labelSmall?.copyWith(color: scheme.onSurfaceVariant)),
      const SizedBox(height: 10),
      if (status != null) ...[
        Wrap(spacing: 14, runSpacing: 6, children: [
          _metric('Listed', status.contracts.toString()),
          _metric('CE', status.calls.toString()),
          _metric('PE', status.puts.toString()),
          _metric('FUT', status.futures.toString()),
          _metric('Underlyings', status.underlyings.toString()),
        ]),
        const SizedBox(height: 7),
        Text(status.usable
            ? 'BOD catalog · ${status.state} · no live quote implied'
            : 'Catalog unavailable · ${status.state}',
          style: text.labelSmall?.copyWith(
            color: status.usable ? scheme.onSurfaceVariant : scheme.error)),
        const SizedBox(height: 7),
        if (_coverage case final live?) ...[
          Wrap(spacing: 14, runSpacing: 5, children: [
            _metric('V3 sub', live.subscribedNseFno.toString()),
            _metric('Fresh LTP', live.freshExchangeLtp.toString()),
            _metric('Depth', live.freshTwoSidedDepth.toString()),
            _metric('OI', live.freshWithOi.toString()),
            _metric('Vol', live.freshWithVolume.toString()),
            _metric('Greeks', live.freshWithGreeks.toString()),
          ]),
          const SizedBox(height: 5),
          Text(
            live.connected && live.catalogContracts > 0
                ? '${live.subscribedNseFno} / ${live.catalogContracts} subscribed'
                    ' · ${live.subscribedWithoutFreshLtp} without fresh LTP'
                : 'V3 ${live.realtimeState} · catalog ${live.catalogState}',
            key: const ValueKey('fno-universe-realtime-coverage'),
            style: text.labelSmall?.copyWith(
              color: live.connected ? scheme.onSurfaceVariant : scheme.error)),
        ] else
          Text('V3 coverage unavailable · not verified',
            key: const ValueKey('fno-universe-realtime-coverage'),
            style: text.labelSmall?.copyWith(
              color: scheme.onSurfaceVariant)),
        const SizedBox(height: 10),
      ],
      TextField(
        key: const ValueKey('fno-universe-search'),
        onChanged: _searchChanged,
        decoration: InputDecoration(
          isDense: true,
          prefixIcon: const Icon(Icons.search_rounded, size: 19),
          hintText: 'Search stock, index or contract',
          border: OutlineInputBorder(
              borderRadius: BorderRadius.circular(10)),
          contentPadding: const EdgeInsets.symmetric(
              horizontal: 10, vertical: 9)),
      ),
      const SizedBox(height: 8),
      Wrap(spacing: 6, runSpacing: 0,
        children: [for (final type in const ['All', 'CE', 'PE', 'FUT'])
          ChoiceChip(
            key: ValueKey('fno-universe-type-${type.toLowerCase()}'),
            selected: _type == type,
            label: Text(type == 'FUT' ? 'Futures'
                : type == 'All' ? 'All' : type),
            labelStyle: const TextStyle(fontSize: 12),
            onSelected: (_) => _changeType(type),
          ),
        ]),
      if (_loading)
        const Padding(padding: EdgeInsets.symmetric(vertical: 15),
          child: LinearProgressIndicator(minHeight: 2)),
      if (_failed)
        Padding(
          padding: const EdgeInsets.symmetric(vertical: 9),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(_invalidResponse
                  ? 'Catalogue response could not be verified.'
                  : 'Instrument catalogue is unavailable.',
                style: text.bodySmall?.copyWith(color: scheme.error)),
              TextButton.icon(
                key: const ValueKey('fno-universe-retry'),
                onPressed: () => unawaited(_load(refreshCoverage: true)),
                icon: const Icon(Icons.refresh_rounded, size: 17),
                label: const Text('Retry catalogue'),
              ),
            ],
          )),

      if (!_loading && !_failed && status != null && !status.usable)
        Padding(padding: const EdgeInsets.symmetric(vertical: 12),
          child: Text('No verified current instrument master is loaded.',
            style: text.bodySmall)),
      if (result != null && status != null && status.usable) ...[
        Padding(padding: const EdgeInsets.symmetric(vertical: 8),
          child: Text('${result.totalMatches} matching listed contracts',
            key: const ValueKey('fno-universe-result-count'),
            style: text.labelSmall?.copyWith(
              color: scheme.onSurfaceVariant))),
        for (final contract in result.contracts)
          _contractTile(context, contract),
        if (result.contracts.isEmpty && !_loading)
          Padding(padding: const EdgeInsets.symmetric(vertical: 14),
            child: Text('No source-listed contracts match the filters.',
              style: text.bodySmall)),
        const SizedBox(height: 8),
        Row(children: [
          IconButton(
            key: const ValueKey('fno-universe-previous'),
            onPressed: _loading || _page == 0
                ? null : () => _goToPage(_page - 1),
            icon: const Icon(Icons.chevron_left_rounded),
          ),
          Expanded(child: Text(
            '${result.totalMatches == 0 ? 0 : _page * result.pageSize + 1}'
            '–${(_page * result.pageSize + result.contracts.length)}'
            ' of ${result.totalMatches}',
            textAlign: TextAlign.center,
            style: text.labelSmall)),
          IconButton(
            key: const ValueKey('fno-universe-next'),
            onPressed: _loading ||
                    (_page + 1) * result.pageSize >= result.totalMatches
                ? null : () => _goToPage(_page + 1),
            icon: const Icon(Icons.chevron_right_rounded),
          ),
        ]),
      ],
    ]);
  }

  Widget _metric(String name, String value) =>
      Text('$name $value', style: const TextStyle(
        fontWeight: FontWeight.w700, fontSize: 11,
        fontFeatures: [FontFeature.tabularFigures()]));

  Widget _contractTile(BuildContext context,
      CustomerFnoUniverseInstrument c) {
    final scheme = Theme.of(context).colorScheme;
    final t = Theme.of(context).textTheme;
    final expiry = c.expiry.toIso8601String().substring(0, 10);
    return Padding(
      key: ValueKey('fno-universe-contract-${c.instrumentKey}'),
      padding: const EdgeInsets.symmetric(vertical: 7),
      child: Row(children: [
        SizedBox(width: 46,
          child: Text(c.type, style: t.labelSmall?.copyWith(
            fontWeight: FontWeight.w800,
            color: c.type == 'FUT' ? scheme.primary
                : scheme.onSurfaceVariant))),
        Expanded(child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(c.tradingSymbol, maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: t.bodySmall?.copyWith(fontWeight: FontWeight.w700)),
            Text('${c.underlyingSymbol} · $expiry · lot ${c.lotSize}'
                ' · tick ₹${c.tickSizeRupees}',
              maxLines: 1, overflow: TextOverflow.ellipsis,
              style: t.labelSmall?.copyWith(color: scheme.onSurfaceVariant)),
          ])),
      ]),
    );
  }
}

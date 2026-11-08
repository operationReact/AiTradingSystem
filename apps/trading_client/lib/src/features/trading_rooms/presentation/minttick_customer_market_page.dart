import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../../data/authenticated_api_client.dart';
import '../../../data/instrument_repository.dart';
import '../data/customer_market_repository.dart';
import 'minttick_customer_widgets.dart';
import 'minttick_market_preview.dart';

/// Verified customer equity catalogue and owner-only, on-demand broker quotes.
/// Nothing on this page is simulated or advertised as a streaming exchange tick.
class MintTickCustomerMarketPage extends StatefulWidget {
  const MintTickCustomerMarketPage({required this.repository, super.key});
  final CustomerMarketRepository repository;

  @override
  State<MintTickCustomerMarketPage> createState() => _MintTickCustomerMarketPageState();
}

class _MintTickCustomerMarketPageState extends State<MintTickCustomerMarketPage> {
  final TextEditingController _query = TextEditingController();
  InstrumentCatalogueStatus? _catalogue;
  List<InstrumentRecord> _stocks = const [];
  CustomerMarketQuote? _quote;
  CustomerLiveQuote? _liveQuote;
  String _liveStatus = 'UNAVAILABLE';
  Timer? _liveTimer;
  bool _liveLoading = false;
  InstrumentRecord? _selected;
  String? _exchange;
  String? _error;
  String? _quoteError;
  bool _loading = false;
  bool _loadingQuote = false;
  int _version = 0;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _version++;
    _liveTimer?.cancel();
    _query.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    _liveTimer?.cancel();
    _liveQuote = null;
    _liveStatus = 'UNAVAILABLE';
    final version = ++_version;
    setState(() {
      _loading = true;
      _error = null;
      _quote = null;
      _quoteError = null;
      _selected = null;
    });
    try {
      final result = await Future.wait<Object>([
        if (_catalogue == null) widget.repository.catalogueStatus(),
        widget.repository.search(query: _query.text.trim().isEmpty ? null : _query.text.trim(),
            exchange: _exchange),
      ]);
      if (!mounted || version != _version) return;
      setState(() {
        if (result.length == 2) _catalogue = result.first as InstrumentCatalogueStatus;
        _stocks = (result.last as InstrumentSearchPage).content;
      });
    } catch (_) {
      if (mounted && version == _version) {
        setState(() {
          _stocks = const [];
          _error = 'Unable to load the customer market catalogue. '
              'Check the MintTick API and try again.';
        });
      }
    } finally {
      if (mounted && version == _version) setState(() => _loading = false);
    }
  }

  Future<void> _select(InstrumentRecord stock) async {
    if (_loadingQuote) return;
    _liveTimer?.cancel();
    _liveQuote = null;
    _liveStatus = 'CONNECTING';
    setState(() {
      _selected = stock;
      _quote = null;
      _quoteError = null;
      _loadingQuote = true;
    });
    try {
      final quote = await widget.repository.quote(stock.id);
      if (!mounted || _selected?.id != stock.id) return;
      setState(() => _quote = quote);
    } on ApiHttpFailure catch (error) {
      if (!mounted || _selected?.id != stock.id) return;
      setState(() {
        _quoteError = switch (error.statusCode) {
          403 => 'Angel One quotes are restricted to the verified account owner.',
          429 => 'The broker has limited quote requests. Try again later.',
          503 => 'The private Angel One market feed is not connected yet.',
          _ => 'No verified broker quote is available for this stock.',
        };
      });
    } catch (_) {
      if (mounted && _selected?.id == stock.id) {
        setState(() => _quoteError = 'Unable to retrieve an authenticated quote.');
      }
    } finally {
      if (mounted && _selected?.id == stock.id) {
        setState(() => _loadingQuote = false);
        _startLivePolling(stock);
      }
    }
  }

  void _startLivePolling(InstrumentRecord stock) {
    if (widget.repository is! CustomerLiveMarketRepository) {
      if (mounted) setState(() => _liveStatus = 'UNAVAILABLE');
      return;
    }
    unawaited(_pollLive(stock));
    _liveTimer = Timer.periodic(const Duration(seconds: 2), (_) {
      unawaited(_pollLive(stock));
    });
  }

  Future<void> _pollLive(InstrumentRecord stock) async {
    if (_liveLoading || !mounted || _selected?.id != stock.id) return;
    _liveLoading = true;
    try {
      final reply = await (widget.repository as CustomerLiveMarketRepository).liveQuote(stock.id);
      if (!mounted || _selected?.id != stock.id) return;
      if (reply.instrumentId != stock.id ||
          reply.exchange != stock.exchange ||
          reply.tradingSymbol != stock.tradingSymbol) {
        throw const FormatException('Quote identity mismatch');
      }
      final age = reply.exchangeAt == null
          ? null : DateTime.now().toUtc().difference(reply.exchangeAt!.toUtc());
      final stale = reply.status == 'LIVE' &&
          (age == null || age.isNegative || age > const Duration(seconds: 12));
      setState(() {
        _liveQuote = stale ? null : reply;
        _liveStatus = stale ? 'STALE' : reply.status;
      });
      if (reply.status == 'MARKET_CLOSED' ||
          reply.status == 'BROKER_CONNECTION_REQUIRED') {
        _liveTimer?.cancel();
      }
    } catch (_) {
      if (mounted && _selected?.id == stock.id) {
        setState(() {
          _liveQuote = null;
          _liveStatus = 'UNAVAILABLE';
        });
      }
    } finally {
      _liveLoading = false;
    }
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final theme = Theme.of(context);
    return MintTickCustomerScaffold(showHeader: false, children: [
      const SizedBox(height: 18),
      const MintTickEyebrow('MintTick / Markets'),
      const SizedBox(height: 6),
      Text('Markets', style: theme.textTheme.headlineSmall
          ?.copyWith(fontWeight: FontWeight.w800)),
      const SizedBox(height: 6),
      Text('Explore verified equities. LIVE requires your connected Angel One session.',
        style: theme.textTheme.bodyMedium?.copyWith(
            color: scheme.onSurfaceVariant, height: 1.4)),
      const SizedBox(height: 18),
      TextField(
        key: const ValueKey('customer-market-search'),
        controller: _query,
        textInputAction: TextInputAction.search,
        inputFormatters: [
          LengthLimitingTextInputFormatter(100),
          FilteringTextInputFormatter.allow(RegExp(r'[A-Za-z0-9 .&/_-]')),
        ],
        decoration: InputDecoration(
          hintText: 'Search stocks (e.g., RELIANCE)',
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
      const SizedBox(height: 12),
      MintTickMarketPreview(repository: widget.repository),
      const SizedBox(height: 12),
      if (_catalogue?.activeCount == 0) ...[
        const SizedBox(height: 12),
        const MintTickInfoText('The exchange instrument catalogue has not yet '
            'been activated on the backend. An authorized catalogue import is required.'),
      ],
      if (_error != null) ...[
        const SizedBox(height: 12),
        MintTickInfoText(_error!, icon: Icons.cloud_off_outlined),
      ],
      if (_loading) ...[
        const SizedBox(height: 15),
        const Center(child: CircularProgressIndicator()),
      ],
      if (_selected != null) ...[
        const SizedBox(height: 16),
        MintTickSurface(accent: _quote != null, child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(_selected!.tradingSymbol, style: theme.textTheme.titleMedium
                ?.copyWith(fontWeight: FontWeight.w800)),
            const SizedBox(height: 8),
            if (_loadingQuote)
              const LinearProgressIndicator(minHeight: 2)
            else if (_liveStatus == 'LIVE' && _liveQuote?.ltp != null) ...[
              Text('LIVE · ANGEL ONE', key: const ValueKey('customer-market-live-status'),
                  style: theme.textTheme.labelMedium?.copyWith(color: scheme.primary,
                      fontWeight: FontWeight.w800)),
              Text('₹${_liveQuote!.ltp!.toStringAsFixed(2)}',
                  key: const ValueKey('customer-market-live-price'),
                  style: theme.textTheme.headlineMedium?.copyWith(fontWeight: FontWeight.w800)),
              Text('Exchange tick: ${_liveQuote!.exchangeAt!.toLocal()}',
                  style: theme.textTheme.bodySmall),
            ] else if (_quote != null) ...[
              Text('NOT LIVE · $_liveStatus', key: const ValueKey('customer-market-live-status'),
                  style: theme.textTheme.labelMedium?.copyWith(color: scheme.error)),
              Text('₹${_quote!.ltp.toStringAsFixed(2)}',
                key: const ValueKey('customer-market-verified-price'),
                style: theme.textTheme.headlineMedium?.copyWith(
                    fontWeight: FontWeight.w800)),
              const SizedBox(height: 6),
              Text('${_quote!.source} · Retrieved ${_quote!.fetchedAt.toLocal()}',
                style: theme.textTheme.bodySmall
                    ?.copyWith(color: scheme.onSurfaceVariant)),
              const SizedBox(height: 5),
              const Text('Retrieved time is not the exchange tick time. NOT LIVE unless verified.',
                style: TextStyle(fontSize: 11)),
            ] else
              Text(_quoteError ?? 'No quote available.',
                style: theme.textTheme.bodySmall?.copyWith(
                    color: scheme.onSurfaceVariant)),
            const SizedBox(height: 9),
            OutlinedButton.icon(
              onPressed: _loadingQuote ? null : () => _select(_selected!),
              icon: const Icon(Icons.refresh_rounded),
              label: const Text('Refresh quote'),
            ),
          ],
        )),
      ],
      const SizedBox(height: 20),
      Row(children: [
        Expanded(child: Text('Cash equities', style: theme.textTheme.titleMedium
            ?.copyWith(fontWeight: FontWeight.w800))),
        Text('${_stocks.length} results',
          style: theme.textTheme.bodySmall?.copyWith(
              color: scheme.onSurfaceVariant)),
      ]),
      const SizedBox(height: 10),
      if (!_loading && _stocks.isEmpty && _error == null)
        const MintTickInfoText('No stocks available for this search. '
            'Try another symbol or verify the active instrument catalogue.')
      else
        for (final stock in _stocks) ...[
          Padding(
            padding: const EdgeInsets.only(bottom: 8),
            child: Material(
              color: scheme.surfaceContainerLow,
              borderRadius: BorderRadius.circular(14),
              child: InkWell(
                key: ValueKey('customer-market-stock-${stock.id}'),
                borderRadius: BorderRadius.circular(14),
                onTap: _loadingQuote ? null : () => _select(stock),
                child: Container(
                  padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 13),
                  decoration: BoxDecoration(
                    borderRadius: BorderRadius.circular(14),
                    border: Border.all(color: scheme.outlineVariant),
                  ),
                  child: Row(children: [
                    Expanded(child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(stock.tradingSymbol, maxLines: 1,
                          style: theme.textTheme.titleSmall
                              ?.copyWith(fontWeight: FontWeight.w700)),
                        const SizedBox(height: 4),
                        Text('${stock.name} · ${stock.exchange}',
                          maxLines: 1, overflow: TextOverflow.ellipsis,
                          style: theme.textTheme.bodySmall?.copyWith(
                              color: scheme.onSurfaceVariant)),
                      ],
                    )),
                    const SizedBox(width: 8),
                    Icon(Icons.chevron_right_rounded, color: scheme.onSurfaceVariant),
                  ]),
                ),
              ),
            ),
          ),
        ],
    ]);
  }
}

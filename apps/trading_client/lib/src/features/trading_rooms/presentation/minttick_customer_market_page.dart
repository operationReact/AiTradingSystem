import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../../data/authenticated_api_client.dart';
import '../../../data/instrument_repository.dart';
import '../data/customer_market_repository.dart';
import 'minttick_customer_widgets.dart';

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
    _query.dispose();
    super.dispose();
  }

  Future<void> _load() async {
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
      if (mounted && _selected?.id == stock.id) setState(() => _loadingQuote = false);
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
      Text('Indian equities', style: theme.textTheme.headlineSmall
          ?.copyWith(fontWeight: FontWeight.w800)),
      const SizedBox(height: 6),
      Text('Search the exchange instrument catalogue. '
          'Request a verified Angel One price for your own account.',
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
      MintTickSurface(
        child: Row(children: [
          const MintTickIconTile(Icons.show_chart_rounded),
          const SizedBox(width: 12),
          Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text('Read-only · On-demand', style: theme.textTheme.titleSmall
                ?.copyWith(fontWeight: FontWeight.w700)),
            const SizedBox(height: 4),
            Text('Quotes are fetched from Angel One when requested. '
                'They are not a live streaming feed or an execution signal.',
              style: theme.textTheme.bodySmall?.copyWith(
                  color: scheme.onSurfaceVariant, height: 1.4)),
          ])),
        ]),
      ),
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
            else if (_quote != null) ...[
              Text('₹${_quote!.ltp.toStringAsFixed(2)}',
                key: const ValueKey('customer-market-verified-price'),
                style: theme.textTheme.headlineMedium?.copyWith(
                    fontWeight: FontWeight.w800)),
              const SizedBox(height: 6),
              Text('${_quote!.source} · Retrieved ${_quote!.fetchedAt.toLocal()}',
                style: theme.textTheme.bodySmall
                    ?.copyWith(color: scheme.onSurfaceVariant)),
              const SizedBox(height: 5),
              const Text('Retrieved time is not the exchange tick time.',
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

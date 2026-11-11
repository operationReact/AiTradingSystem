import 'dart:math';

import 'package:flutter/material.dart';

import '../../../data/instrument_repository.dart';

import '../data/customer_market_repository.dart';
import '../data/customer_paper_trading_repository.dart';
import '../data/customer_room_repository.dart';
import 'minttick_customer_widgets.dart';

/// Explicit MANUAL Paper trading only. This screen never invokes a broker
/// order API and cannot upgrade a LIVE Room into a virtual one.
class MintTickManualPaperTradingPanel extends StatefulWidget {
  const MintTickManualPaperTradingPanel({
    required this.roomId,
    required this.wallet,
    required this.orders,
    required this.market,
    required this.onWalletChanged,
    super.key,
  });

  final String roomId;
  final CustomerPaperWallet wallet;
  final HttpCustomerPaperTradingRepository orders;
  final HttpCustomerMarketRepository market;
  final Future<void> Function() onWalletChanged;

  @override
  State<MintTickManualPaperTradingPanel> createState() =>
      _MintTickManualPaperTradingPanelState();
}

class _MintTickManualPaperTradingPanelState
    extends State<MintTickManualPaperTradingPanel> {
  final _search = TextEditingController();
  final _quantity = TextEditingController(text: '1');
  final _limit = TextEditingController();
  bool _busy = false;
  bool _searching = false;
  String? _error;
  String _side = 'BUY';
  InstrumentRecord? _instrument;
  CustomerLiveQuote? _quote;
  List<InstrumentRecord> _results = const [];
  List<CustomerPaperOrder> _fills = const [];
  List<CustomerPaperPosition> _positions = const [];
  String? _pendingKey;

  @override
  void initState() {
    super.initState();
    _loadHistory();
  }

  @override
  void dispose() {
    _search.dispose();
    _quantity.dispose();
    _limit.dispose();
    super.dispose();
  }

  String _newOrderKey() {
    final rand = Random.secure();
    final data = List<int>.generate(16, (_) => rand.nextInt(256));
    return 'paper_' + data.map((n) => n.toRadixString(16).padLeft(2, '0')).join();
  }

  Future<void> _loadHistory() async {
    try {
      final all = await Future.wait<Object>([
        widget.orders.orders(widget.roomId),
        widget.orders.positions(widget.roomId),
      ]);
      if (!mounted) return;
      setState(() {
        _fills = all[0] as List<CustomerPaperOrder>;
        _positions = all[1] as List<CustomerPaperPosition>;
      });
    } catch (_) {
      if (mounted) setState(() => _error =
          'Paper activity could not be verified; refresh when connected.');
    }
  }

  Future<void> _setPaused(bool paused) async {
    if (_busy) return;
    setState(() { _busy = true; _error = null; });
    try {
      if (paused) {
        await widget.orders.pause(widget.roomId);
      } else {
        final confirm = await showDialog<bool>(
          context: context,
          builder: (context) => AlertDialog(
            title: const Text('Enable manual Paper orders?'),
            content: const Text(
              'Only virtual funds in this Paper Room are used. '
              'There is no real broker execution or automatic strategy. '
              'You must confirm each simulated order. '
              'The exchange must be open and the verified market tick fresh.'),
            actions: [
              TextButton(onPressed: () => Navigator.pop(context, false),
                child: const Text('Cancel')),
              FilledButton(
                key: const ValueKey('paper-resume-confirm'),
                onPressed: () => Navigator.pop(context, true),
                child: const Text('Enable Paper orders')),
            ],
          ),
        );
        if (confirm != true || !mounted) return;
        await widget.orders.resume(widget.roomId);
      }
      await widget.onWalletChanged();
      if (mounted) setState(() => _pendingKey = null);
    } catch (_) {
      if (mounted) setState(() => _error =
          'Paper trading state was not verified. No order has been sent.');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _findInstruments() async {
    final query = _search.text.trim();
    if (_searching || query.length < 2) {
      setState(() => _error = 'Enter at least two letters of a stock name.');
      return;
    }
    setState(() { _searching = true; _error = null; });
    try {
      final page = await widget.market.searchPage(query: query, page: 0);
      final items = page.content.where((item) =>
        (item.exchange == 'NSE' || item.exchange == 'BSE') &&
        const {'EQ', 'EQUITY', 'STOCK'}.contains(item.instrumentType.toUpperCase())
      ).toList(growable: false);
      if (mounted) {
        setState(() {
          _results = items;
          _instrument = null;
          _quote = null;
          _pendingKey = null;
        });
      }
    } catch (_) {
      if (mounted) setState(() => _error =
          'Unable to verify tradable instruments. Try again.');
    } finally {
      if (mounted) setState(() => _searching = false);
    }
  }

  Future<void> _previewQuote() async {
    final instrument = _instrument;
    if (instrument == null || _busy) return;
    setState(() { _busy = true; _quote = null; _error = null; });
    try {
      // This preview is informative. Backend rechecks the independently
      // recorded verified LIVE tick under its own timestamp and limits.
      final quote = await widget.market.liveQuote(instrument.id);
      if (!mounted) return;
      setState(() {
        _quote = quote.status == 'LIVE' &&
            quote.instrumentId == instrument.id ? quote : null;
        if (_quote == null) _error = 'A verified LIVE quote is not available.';
      });
    } catch (_) {
      if (mounted) setState(() => _error =
          'A fresh verified broker quote is unavailable.');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _submit() async {
    final instrument = _instrument;
    final quantity = int.tryParse(_quantity.text.trim());
    final price = num.tryParse(_limit.text.trim());
    final quote = _quote;
    if (_busy || widget.wallet.halted || instrument == null || quote == null ||
        quote.status != 'LIVE' || quantity == null || quantity < 1 ||
        quantity > 10000 || price == null || !price.isFinite || price <= 0) {
      setState(() => _error =
          'Select a verified stock, LIVE price, valid quantity and limit.');
      return;
    }
    // Do not show the submit button as ready if a quote may have expired.
    final at = quote.exchangeAt;
    if (at == null || DateTime.now().difference(at).inSeconds > 15 ||
        at.isAfter(DateTime.now().add(const Duration(seconds: 2)))) {
      setState(() => _error =
          'Broker quote is stale. Refresh price before placing Paper order.');
      return;
    }
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text('Simulate $_side: ${instrument.tradingSymbol}?'),
        content: Text(
          '$quantity shares · limit ₹${_limit.text.trim()}\n'
          'Live quote ₹${quote.ltp} · ${instrument.exchange}\n\n'
          'Virtual cash only. Backend applies price, market-hours, '
          'fees, exposure and position checks. No real order is sent.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false),
              child: const Text('Cancel')),
          FilledButton(
            key: const ValueKey('paper-submit-confirm'),
            onPressed: () => Navigator.pop(context, true),
            child: const Text('Confirm Paper order')),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;
    setState(() { _busy = true; _error = null; });
    // Keep exactly the same idempotency key across uncertain network retries.
    final key = _pendingKey ??= _newOrderKey();
    try {
      final receipt = await widget.orders.submit(
        roomId: widget.roomId, idempotencyKey: key,
        instrumentId: instrument.id, side: _side,
        quantity: quantity, limitPrice: _limit.text.trim(),
      );
      if (!mounted) return;
      _pendingKey = null;
      await _loadHistory();
      await widget.onWalletChanged();
      if (mounted) setState(() => _error =
        'Paper ${receipt.side} fill confirmed at ₹${receipt.fillPrice}. '
        'No broker order placed.');
    } catch (_) {
      if (mounted) setState(() => _error =
        'Paper order was not confirmed. Refresh the order history before '
        'retrying. A retry uses the same idempotency key.');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final styles = Theme.of(context).textTheme;
    final running = !widget.wallet.halted;
    return MintTickSurface(
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        const MintTickSectionHeading('Manual Paper trading'),
        const SizedBox(height: 5),
        Text('Virtual funds only · No broker order execution',
          style: styles.bodySmall),
        const SizedBox(height: 12),
        FilledButton.icon(
          key: ValueKey(running ? 'paper-pause-manual' : 'paper-resume-manual'),
          onPressed: _busy ? null : () => _setPaused(running),
          icon: Icon(running ? Icons.pause_circle_outline :
              Icons.play_circle_outline),
          label: Text(running ? 'Pause Paper orders' : 'Enable manual Paper orders'),
        ),
        if (_busy || _searching) ...[
          const SizedBox(height: 11),
          const LinearProgressIndicator(minHeight: 2),
        ],
        if (_error != null) ...[
          const SizedBox(height: 10),
          MintTickInfoText(_error!, icon: Icons.info_outline),
        ],
        if (running) ...[
          const SizedBox(height: 17),
          TextField(
            controller: _search,
            key: const ValueKey('paper-search-instrument'),
            onChanged: (_) => setState(() {
              _instrument = null;
              _quote = null;
              _pendingKey = null;
            }),
            decoration: InputDecoration(
              labelText: 'Search NSE/BSE equity',
              hintText: 'RELIANCE, SBIN, ICICI...',
              suffixIcon: IconButton(
                key: const ValueKey('paper-search-submit'),
                onPressed: _searching ? null : _findInstruments,
                icon: const Icon(Icons.search),
              ),
            ),
          ),
          const SizedBox(height: 12),
          if (_results.isNotEmpty) DropdownButtonFormField<int>(
            key: const ValueKey('paper-instrument-picker'),
            initialValue: _instrument?.id,
            isExpanded: true,
            decoration: const InputDecoration(labelText: 'Verified stock'),
            items: [
              for (final item in _results)
                DropdownMenuItem(
                  value: item.id,
                  child: Text('${item.tradingSymbol} · ${item.exchange}',
                    overflow: TextOverflow.ellipsis),
                ),
            ],
            onChanged: (id) => setState(() {
              _instrument = _results.where((item) => item.id == id)
                  .firstOrNull;
              _quote = null;
              _pendingKey = null;
            }),
          ),
          if (_instrument != null) ...[
            const SizedBox(height: 11),
            OutlinedButton.icon(
              key: const ValueKey('paper-load-live-quote'),
              onPressed: _busy ? null : _previewQuote,
              icon: const Icon(Icons.refresh),
              label: const Text('Verify live quote'),
            ),
            if (_quote?.ltp != null) ...[
              const SizedBox(height: 7),
              Text('Broker LTP ₹${_quote!.ltp} · exchange ${_quote!.exchangeAt?.toLocal()}',
                style: styles.bodySmall),
            ],
            const SizedBox(height: 10),
            SegmentedButton<String>(
              key: const ValueKey('paper-buy-sell-toggle'),
              segments: const [
                ButtonSegment(value: 'BUY', label: Text('BUY')),
                ButtonSegment(value: 'SELL', label: Text('SELL')),
              ],
              selected: {_side},
              onSelectionChanged: (selected) => setState(() {
                _side = selected.first;
                _pendingKey = null;
              }),
            ),
            const SizedBox(height: 10),
            Row(children: [
              Expanded(child: TextField(
                key: const ValueKey('paper-order-quantity'),
                controller: _quantity,
                keyboardType: TextInputType.number,
                onChanged: (_) => _pendingKey = null,
                decoration: const InputDecoration(labelText: 'Shares'),
              )),
              const SizedBox(width: 8),
              Expanded(child: TextField(
                key: const ValueKey('paper-order-limit'),
                controller: _limit,
                keyboardType: const TextInputType.numberWithOptions(decimal: true),
                onChanged: (_) => _pendingKey = null,
                decoration: const InputDecoration(labelText: 'Limit ₹'),
              )),
            ]),
            const SizedBox(height: 13),
            FilledButton.icon(
              key: const ValueKey('paper-open-confirmation'),
              onPressed: _busy || _quote == null ? null : _submit,
              icon: const Icon(Icons.receipt_long_outlined),
              label: Text('Review virtual $_side order'),
            ),
          ],
        ],
        const SizedBox(height: 16),
        Row(children: [
          Expanded(child: Text('Paper positions', style: styles.titleSmall)),
          IconButton(onPressed: _busy ? null : _loadHistory,
            icon: const Icon(Icons.refresh), tooltip: 'Refresh Paper activity'),
        ]),
        for (final item in _positions.take(20))
          ListTile(
            dense: true,
            title: Text('Instrument #${item.instrumentId}'),
            subtitle: Text('${item.quantity} shares · avg cost ₹${(item.avgCostPaise/100).toStringAsFixed(2)}'),
          ),
        const SizedBox(height: 8),
        Text('Recent virtual fills', style: styles.titleSmall),
        for (final fill in _fills.take(20))
          ListTile(
            dense: true,
            title: Text('${fill.side} · ${fill.tradingSymbol} · ${fill.quantity}'),
            subtitle: Text('₹${fill.fillPrice} · fees ₹${(fill.feesPaise/100).toStringAsFixed(2)}'),
          ),
        const SizedBox(height: 8),
        const MintTickInfoText(
          'Paper fills are immediate simulations against a verified LIVE '
          'tick; unmarketable orders, stale prices and off-hours orders are '
          'rejected. Estimated fees are not a full tax/slippage simulation.',
          icon: Icons.shield_outlined),
      ]),
    );
  }
}

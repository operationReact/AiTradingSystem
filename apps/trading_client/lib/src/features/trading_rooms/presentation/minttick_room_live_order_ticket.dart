
import 'package:flutter/material.dart';

import '../../../data/instrument_repository.dart';
import '../data/customer_execution_repository.dart';
import '../data/customer_market_repository.dart';
import '../data/customer_trade_intent_repository.dart';

/// LIVE order drafting only. The backend has no customer LIVE submit route,
/// and this widget never calls a broker placement API.
class MintTickRoomLiveOrderTicket extends StatefulWidget {
  const MintTickRoomLiveOrderTicket({
    required this.roomId, required this.market,
    required this.execution, required this.intents, super.key,
  });
  final String roomId;
  final HttpCustomerMarketRepository market;
  final HttpCustomerExecutionRepository execution;
  final HttpCustomerTradeIntentRepository intents;

  @override
  State<MintTickRoomLiveOrderTicket> createState() => _MintTickRoomLiveOrderTicketState();
}

class _MintTickRoomLiveOrderTicketState extends State<MintTickRoomLiveOrderTicket> {
  final _query = TextEditingController();
  final _quantity = TextEditingController(text: '1');
  final _price = TextEditingController();
  List<InstrumentRecord> _matches = const [];
  InstrumentRecord? _stock;
  List<CustomerBlockedOrderIntent> _history = const [];
  String _side = 'BUY', _product = 'DELIVERY';
  bool _busy = false;
  bool _preflightPassed = false;
  bool _uncertain = false;
  String? _message;
  String? _pendingKey;

  @override
  void initState() {
    super.initState();
    _loadIntents();
  }

  @override
  void dispose() {
    _query.dispose();
    _quantity.dispose();
    _price.dispose();
    super.dispose();
  }

  void _changed() {
    setState(() {
      _preflightPassed = false;
      _pendingKey = null;
      _message = null;
    });
  }

  Future<void> _loadIntents() async {
    try {
      final rows = await widget.intents.list(widget.roomId);
      if (mounted) setState(() => _history = rows);
    } catch (_) {
      if (mounted) setState(() => _message =
          'Saved intent history cannot be verified. No LIVE order was placed.');
    }
  }

  Future<void> _find() async {
    if (_busy) return;
    if (_query.text.trim().length < 2) {
      setState(() => _message = 'Enter at least two letters of the stock.');
      return;
    }
    setState(() { _busy = true; _message = null; _matches = []; _stock = null; });
    try {
      final result = await widget.market.searchPage(query: _query.text.trim(), page: 0);
      if (!mounted) return;
      setState(() => _matches = result.content.where((stock) =>
        (stock.exchange == 'NSE' || stock.exchange == 'BSE') &&
        const {'EQ', 'EQUITY', 'STOCK'}.contains(stock.instrumentType.toUpperCase())
      ).toList(growable: false));
    } catch (_) {
      if (mounted) setState(() => _message =
          'Instrument catalogue unavailable. Order preparation stopped.');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  bool _valid() {
    final qty = int.tryParse(_quantity.text.trim());
    final text = _price.text.trim();
    final price = num.tryParse(text);
    return _stock != null && qty != null && qty >= 1 && qty <= 10000 &&
        RegExp(r'^\d{1,8}(\.\d{1,2})?$').hasMatch(text) &&
        price != null && price > 0 && price <= 10000000;
  }

  Future<void> _preflight() async {
    if (_busy || !_valid()) {
      setState(() => _message = 'Select a verified cash equity, quantity and limit price.');
      return;
    }
    setState(() { _busy = true; _message = null; _preflightPassed = false; });
    try {
      final raw = await widget.execution.preflight(
        roomId: widget.roomId, instrumentId: _stock!.id, side: _side,
        quantity: int.parse(_quantity.text.trim()),
        limitPrice: num.parse(_price.text.trim()),
      );
      if (raw is! Map<String, dynamic> ||
          raw['roomId'] != widget.roomId || raw['mode'] != 'LIVE' ||
          raw['instrumentId'] != _stock!.id || raw['executable'] != false ||
          raw['blockers'] is! List || (raw['blockers'] as List).isEmpty) {
        throw const FormatException('Unverified order preflight');
      }
      if (mounted) setState(() {
        _preflightPassed = true;
        _message = 'Preflight confirmed: LIVE execution blocked by server. '
          'You may save this as a non-executing intent.';
      });
    } catch (_) {
      if (mounted) setState(() => _message =
          'Order preflight could not be verified. No LIVE order was sent.');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _saveIntent() async {
    if (_busy || !_preflightPassed || !_valid() || _uncertain) return;
    final confirm = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Save blocked LIVE intent?'),
        content: Text(
          '$_side ${_quantity.text.trim()} ${_stock!.tradingSymbol} at '
          '₹${_price.text.trim()} · $_product.\n\n'
          'This stores a draft audit record only. It does not '
          'submit an order to Angel One, Upstox or any exchange.',
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false),
            child: const Text('Cancel')),
          FilledButton(
            key: const ValueKey('live-intent-confirm'),
            onPressed: () => Navigator.pop(context, true),
            child: const Text('Save intent, no trade')),
        ],
      ),
    );
    if (confirm != true || !mounted) return;
    setState(() { _busy = true; _message = null; });
    final key = _pendingKey ??= HttpCustomerTradeIntentRepository.newKey();
    try {
      final saved = await widget.intents.create(
        roomId: widget.roomId, idempotencyKey: key,
        instrumentId: _stock!.id, side: _side,
        quantity: int.parse(_quantity.text.trim()),
        productType: _product, limitPrice: _price.text.trim(),
      );
      if (saved.mode != 'LIVE' || saved.state != 'PRECHECK_BLOCKED' ||
          saved.roomId != widget.roomId) {
        throw const FormatException('Unexpected order intent receipt');
      }
      if (!mounted) return;
      setState(() {
        _pendingKey = null;
        _preflightPassed = false;
        _message = 'Blocked LIVE intent saved. No broker order was placed.';
      });
      await _loadIntents();
    } catch (_) {
      if (mounted) {
        setState(() {
          _uncertain = true;
          _message = 'Intent save result UNKNOWN. Review saved intents before '
              'starting a new order. Nothing was sent to the broker.';
        });
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final type = Theme.of(context).textTheme;
    final scheme = Theme.of(context).colorScheme;
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Text('LIVE ORDER TICKET · PREPARATION ONLY', style: type.labelSmall?.copyWith(
        color: scheme.onSurfaceVariant, fontWeight: FontWeight.w700)),
      const SizedBox(height: 7),
      Row(children: [
        Expanded(child: TextField(
          key: const ValueKey('live-order-search'),
          controller: _query,
          onChanged: (_) => _changed(),
          onSubmitted: (_) => _find(),
          decoration: InputDecoration(hintText: 'Search NSE / BSE equity',
            isDense: true,
            suffixIcon: IconButton(onPressed: _busy ? null : _find,
              tooltip: 'Search instrument',
              icon: const Icon(Icons.search_rounded, size: 18))),
        )),
      ]),
      if (_matches.isNotEmpty) ...[
        const SizedBox(height: 7),
        DropdownButtonFormField<int>(
          key: const ValueKey('live-order-symbol'),
          initialValue: _stock?.id,
          isExpanded: true,
          decoration: const InputDecoration(labelText: 'Stock', isDense: true),
          items: [
            for (final stock in _matches)
              DropdownMenuItem(value: stock.id,
                child: Text('${stock.tradingSymbol} · ${stock.exchange}',
                  overflow: TextOverflow.ellipsis)),
          ],
          onChanged: (id) {
            _changed();
            setState(() => _stock = _matches.where((s) => s.id == id).firstOrNull);
          },
        ),
      ],
      const SizedBox(height: 7),
      Row(children: [
        Expanded(child: SegmentedButton<String>(
          key: const ValueKey('live-order-side'),
          showSelectedIcon: false,
          segments: const [
            ButtonSegment(value: 'BUY', label: Text('BUY')),
            ButtonSegment(value: 'SELL', label: Text('SELL')),
          ],
          selected: {_side},
          onSelectionChanged: (v) { _changed(); setState(() => _side = v.first); },
        )),
        const SizedBox(width: 8),
        DropdownButton<String>(
          value: _product, underline: const SizedBox.shrink(),
          items: const [
            DropdownMenuItem(value: 'DELIVERY', child: Text('Delivery')),
            DropdownMenuItem(value: 'INTRADAY', child: Text('Intraday')),
          ],
          onChanged: (v) {
            if (v == null) return;
            _changed(); setState(() => _product = v);
          },
        ),
      ]),
      const SizedBox(height: 7),
      Row(children: [
        Expanded(child: TextField(
          key: const ValueKey('live-order-quantity'),
          controller: _quantity, onChanged: (_) => _changed(),
          keyboardType: TextInputType.number,
          decoration: const InputDecoration(labelText: 'Qty', isDense: true))),
        const SizedBox(width: 7),
        Expanded(child: TextField(
          key: const ValueKey('live-order-price'),
          controller: _price, onChanged: (_) => _changed(),
          keyboardType: const TextInputType.numberWithOptions(decimal: true),
          decoration: const InputDecoration(labelText: 'Limit ₹', isDense: true))),
      ]),
      const SizedBox(height: 7),
      Row(children: [
        Expanded(child: OutlinedButton(
          key: const ValueKey('live-order-preflight'),
          onPressed: _busy || _uncertain || !_valid() ? null : _preflight,
          child: const Text('Validate order'))),
        const SizedBox(width: 8),
        Expanded(child: FilledButton(
          key: const ValueKey('live-order-save-intent'),
          onPressed: _busy || _uncertain || !_preflightPassed ? null : _saveIntent,
          child: const Text('Save blocked intent'))),
      ]),
      if (_busy) const LinearProgressIndicator(minHeight: 2),
      if (_message != null) Padding(
        padding: const EdgeInsets.only(top: 8),
        child: Text(_message!, style: type.bodySmall?.copyWith(
          color: scheme.onSurfaceVariant))),
      const SizedBox(height: 13),
      Row(children: [
        Expanded(child: Text('Saved non-executing intents',
          style: type.titleSmall?.copyWith(fontWeight: FontWeight.w700))),
        IconButton(onPressed: _busy ? null : _loadIntents,
          tooltip: 'Refresh intent history', icon: const Icon(Icons.refresh_rounded, size: 18)),
      ]),
      for (final item in _history.take(8))
        Padding(padding: const EdgeInsets.symmetric(vertical: 5),
          child: Row(children: [
            Expanded(child: Text('${item.side} ${item.tradingSymbol} · ${item.quantity}',
              style: type.bodySmall, overflow: TextOverflow.ellipsis)),
            Text('BLOCKED', style: type.labelSmall?.copyWith(color: scheme.onSurfaceVariant)),
          ])),
      const SizedBox(height: 5),
      Text('Real-money order placement will only appear after backend broker '
        'execution, authorization, risk and reconciliation gates are enabled.',
        style: type.labelSmall?.copyWith(color: scheme.onSurfaceVariant)),
    ]);
  }
}


import 'dart:math';
import 'package:flutter/material.dart';
import '../../../data/instrument_repository.dart';
import '../data/customer_execution_repository.dart';
import '../data/customer_market_repository.dart';
import '../data/customer_paper_trading_repository.dart';
import '../data/customer_room_repository.dart';
import '../data/customer_trade_intent_repository.dart';

/// The Buy/Sell bottom sheet matches the interactive MintTick HTML blueprint.
/// PAPER submits only virtual fills. LIVE stores PRECHECK_BLOCKED intents only.
class MintTickRoomOrderSheet extends StatefulWidget {
  const MintTickRoomOrderSheet({
    required this.roomId,
    required this.mode,
    required this.side,
    required this.market,
    required this.onChanged,
    this.initialInstrument,
    this.wallet,
    this.paper,
    this.execution,
    this.intents,
    super.key,
  });
  final String roomId, mode, side;
  final InstrumentRecord? initialInstrument;
  final HttpCustomerMarketRepository market;
  final CustomerPaperWallet? wallet;
  final HttpCustomerPaperTradingRepository? paper;
  final HttpCustomerExecutionRepository? execution;
  final HttpCustomerTradeIntentRepository? intents;
  final Future<void> Function() onChanged;

  @override
  State<MintTickRoomOrderSheet> createState() => _MintTickRoomOrderSheetState();
}

class _MintTickRoomOrderSheetState extends State<MintTickRoomOrderSheet> {
  late String _side;
  String _product = 'DELIVERY';
  late InstrumentRecord? _stock;
  final _qty = TextEditingController(text: '1');
  final _limit = TextEditingController();
  final _search = TextEditingController();
  List<InstrumentRecord> _matches = const [];
  CustomerLiveQuote? _quote;
  bool _busy = false, _unknown = false;
  late bool _paperEnabled;
  String? _error;
  String? _pendingKey;

  @override
  void initState() {
    super.initState();
    _side = widget.side;
    _stock = widget.initialInstrument;
    _paperEnabled = widget.wallet?.halted == false;
    if (_stock != null) {
      _search.text = _stock!.tradingSymbol;
      _verifyQuote();
    }
  }

  @override
  void dispose() {
    _qty.dispose();
    _limit.dispose();
    _search.dispose();
    super.dispose();
  }

  static String _key() {
    final random = Random.secure();
    final bytes = List.generate(16, (_) => random.nextInt(256));
    return 'paper_${bytes.map((n) => n.toRadixString(16).padLeft(2, '0')).join()}';
  }

  void _invalidate() {
    _pendingKey = null;
    _error = null;
  }

  Future<void> _findStock() async {
    if (_search.text.trim().length < 2 || _busy) return;
    setState(() { _busy = true; _error = null; });
    try {
      final page = await widget.market.searchPage(
          query: _search.text.trim(), page: 0);
      if (!mounted) return;
      setState(() => _matches = page.content.where((stock) =>
        (stock.exchange == 'NSE' || stock.exchange == 'BSE') &&
        const {'EQ', 'EQUITY', 'STOCK'}
            .contains(stock.instrumentType.toUpperCase())).toList());
    } catch (_) {
      if (mounted) setState(() => _error = 'Unable to verify the instrument.');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _verifyQuote() async {
    final stock = _stock;
    if (stock == null || _busy) return;
    setState(() { _busy = true; _quote = null; _error = null; });
    try {
      final value = await widget.market.liveQuote(stock.id);
      if (!mounted || _stock?.id != stock.id) return;
      if (value.status != 'LIVE' || value.ltp == null ||
          value.exchangeAt == null) {
        throw const FormatException('Broker tick not verified');
      }
      setState(() {
        _quote = value;
        if (_limit.text.trim().isEmpty) {
          _limit.text = value.ltp!.toStringAsFixed(2);
        }
      });
    } catch (_) {
      if (mounted) { setState(() =>
        _error = 'Verified live quote unavailable. Order cannot be submitted.'); }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _resume() async {
    if (widget.mode != 'PAPER' || widget.paper == null || _busy) return;
    final proceed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Enable manual Paper orders?'),
        content: const Text(
          'This enables virtual orders in this room only. '
          'The exchange tick must be fresh and each order requires confirmation. '
          'It does not enable MintTick Mind automation or real-money orders.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false),
            child: const Text('Cancel')),
          FilledButton(onPressed: () => Navigator.pop(context, true),
            child: const Text('Enable Paper orders')),
        ],
      ),
    );
    if (proceed != true || !mounted) return;
    setState(() { _busy = true; _error = null; });
    try {
      final updated = await widget.paper!.resume(widget.roomId);
      if (!mounted) return;
      setState(() => _paperEnabled = !updated.halted);
      await widget.onChanged();
    } catch (_) {
      if (mounted) { setState(() =>
          _error = 'Paper order state could not be verified.'); }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  bool _valid() {
    final quantity = int.tryParse(_qty.text.trim());
    final priceText = _limit.text.trim();
    final allowed = widget.mode == 'PAPER'
      ? RegExp(r'^\d{1,7}(\.\d{1,4})?$')
      : RegExp(r'^\d{1,8}(\.\d{1,2})?$');
    final price = num.tryParse(priceText);
    return _stock != null && quantity != null &&
      quantity >= 1 && quantity <= 10000 &&
      allowed.hasMatch(priceText) && price != null &&
      price.isFinite && price > 0 &&
      price <= (widget.mode == 'PAPER' ? 1000000 : 10000000);
  }

  Future<bool> _confirm(String message, String action) async {
    final result = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Confirm order review'),
        content: Text(message),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false),
            child: const Text('Cancel')),
          FilledButton(
            key: const ValueKey('room-order-final-confirm'),
            onPressed: () => Navigator.pop(context, true),
            child: Text(action)),
        ],
      ),
    );
    return result == true && mounted;
  }

  Future<void> _place() async {
    if (_busy || _unknown || !_valid()) {
      setState(() => _error = 'Select a verified stock, quantity and limit price.');
      return;
    }
    final stock = _stock!;
    final quantity = int.parse(_qty.text.trim());
    final limitPrice = _limit.text.trim();
    final price = num.parse(limitPrice);
    setState(() { _busy = true; _error = null; });
    var requestSent = false;
    try {
      if (widget.mode == 'PAPER') {
        if (!_paperEnabled || widget.paper == null || widget.wallet == null) {
          throw StateError('Manual Paper orders not enabled');
        }
        // Refresh immediately before confirmation. Backend independently
        // verifies exchange time, price bounds, cash and exposure on submit.
        final quote = await widget.market.liveQuote(stock.id);
        final exchangeAt = quote.exchangeAt;
        final now = DateTime.now();
        if (quote.status != 'LIVE' || quote.ltp == null ||
            quote.instrumentId != stock.id || exchangeAt == null ||
            exchangeAt.isAfter(now.add(const Duration(seconds: 2))) ||
            now.difference(exchangeAt).inSeconds > 15) {
          throw StateError('Fresh broker quote unavailable');
        }
        if (!mounted) return;
        final approved = await _confirm(
          '$_side $quantity ${stock.tradingSymbol} at ₹$limitPrice.\n'
          'Verified broker tick ₹${quote.ltp}.\n\n'
          'Virtual cash only. The backend validates price, market hours, '
          'fees and position limits. No real broker order will be sent.',
          'Confirm Paper order',
        );
        if (!approved) return;
        final key = _pendingKey ??= _key();
        requestSent = true;
        final receipt = await widget.paper!.submit(
          roomId: widget.roomId,
          idempotencyKey: key,
          instrumentId: stock.id,
          side: _side,
          quantity: quantity,
          limitPrice: limitPrice,
        );
        if (receipt.roomId != widget.roomId ||
            receipt.instrumentId != stock.id || receipt.side != _side) {
          throw const FormatException('Unexpected Paper order receipt');
        }
      } else {
        if (widget.execution == null || widget.intents == null) {
          throw StateError('LIVE intent service unavailable');
        }
        final preview = await widget.execution!.preflight(
          roomId: widget.roomId,
          instrumentId: stock.id,
          side: _side,
          quantity: quantity,
          limitPrice: price,
        );
        if (preview is! Map<String, dynamic> ||
            preview['roomId'] != widget.roomId ||
            preview['mode'] != 'LIVE' ||
            preview['instrumentId'] != stock.id ||
            preview['executable'] != false ||
            preview['blockers'] is! List ||
            (preview['blockers'] as List).isEmpty) {
          throw const FormatException('Unverified LIVE preflight');
        }
        if (!mounted) return;
        final approved = await _confirm(
          '$_side $quantity ${stock.tradingSymbol} at ₹$limitPrice.\n\n'
          'This saves a PRECHECK_BLOCKED intent only. It does NOT place '
          'an order with Angel One, Upstox or the exchange.',
          'Save blocked intent',
        );
        if (!approved) return;
        final key = _pendingKey ??=
            HttpCustomerTradeIntentRepository.newKey();
        requestSent = true;
        final saved = await widget.intents!.create(
          roomId: widget.roomId, idempotencyKey: key,
          instrumentId: stock.id, side: _side,
          quantity: quantity, productType: _product,
          limitPrice: limitPrice,
        );
        if (saved.roomId != widget.roomId || saved.mode != 'LIVE' ||
            saved.state != 'PRECHECK_BLOCKED' ||
            saved.instrumentId != stock.id) {
          throw const FormatException('Unexpected LIVE intent receipt');
        }
      }
      if (!mounted) return;
      _pendingKey = null;
      await widget.onChanged();
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
        content: Text(widget.mode == 'PAPER'
          ? 'Virtual Paper fill confirmed. No brokerage order placed.'
          : 'LIVE intent saved as blocked. No brokerage order placed.'),
      ));
      Navigator.of(context).pop();
    } catch (_) {
      if (!mounted) return;
      setState(() {
        _unknown = requestSent;
        _error = requestSent
          ? 'Order outcome UNKNOWN. Reconcile room order history '
            'before placing another order. Automatic resubmission is disabled.'
          : 'Order could not be verified. Check stock, quote, price and '
            'trading availability before retrying.';
      });
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final type = Theme.of(context).textTheme;
    final isPaper = widget.mode == 'PAPER';
    return SafeArea(
      top: false,
      child: Padding(
        padding: EdgeInsets.fromLTRB(20, 18, 20,
          MediaQuery.viewInsetsOf(context).bottom + 20),
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 480),
          child: SingleChildScrollView(
            child: Column(crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min, children: [
              Text(isPaper ? 'Paper order ticket' : 'Live order preparation',
                style: type.titleLarge?.copyWith(fontWeight: FontWeight.w800)),
              const SizedBox(height: 13),
              Row(children: [
                Expanded(child: _SideButton(
                  side: 'BUY', selected: _side == 'BUY',
                  onPressed: () => setState(() { _side = 'BUY'; _invalidate(); }))),
                const SizedBox(width: 6),
                Expanded(child: _SideButton(
                  side: 'SELL', selected: _side == 'SELL',
                  onPressed: () => setState(() { _side = 'SELL'; _invalidate(); }))),
              ]),
              Row(children: [
                Expanded(child: _TicketField(
                  label: 'Instrument',
                  child: TextField(
                    key: const ValueKey('room-ticket-stock'),
                    controller: _search,
                    onChanged: (_) => setState(() {
                      _stock = null; _quote = null; _invalidate();
                    }),
                    onSubmitted: (_) => _findStock(),
                    decoration: InputDecoration(
                      hintText: 'Search stock',
                      suffixIcon: IconButton(
                        onPressed: _busy ? null : _findStock,
                        icon: const Icon(Icons.search, size: 18)),
                    ),
                  ),
                )),
                const SizedBox(width: 8),
                Expanded(child: _TicketField(
                  label: 'Product',
                  child: DropdownButtonFormField<String>(
                    initialValue: _product,
                    items: const [
                      DropdownMenuItem(value: 'DELIVERY', child: Text('Delivery')),
                      DropdownMenuItem(value: 'INTRADAY', child: Text('Intraday')),
                    ],
                    onChanged: (value) {
                      if (value != null) setState(() { _product = value; _invalidate(); });
                    }),
                )),
              ]),
              if (_matches.isNotEmpty)
                SizedBox(height: 100, child: ListView(
                  children: [
                    for (final item in _matches.take(5))
                      ListTile(
                        dense: true,
                        title: Text(item.tradingSymbol),
                        subtitle: Text(item.exchange),
                        onTap: () {
                          setState(() {
                            _stock = item; _quote = null;
                            _search.text = item.tradingSymbol;
                            _matches = const [];
                            _limit.clear();
                            _invalidate();
                          });
                          _verifyQuote();
                        },
                      ),
                  ],
                )),
              Row(children: [
                Expanded(child: _TicketField(
                  label: 'Quantity',
                  child: TextField(
                    key: const ValueKey('room-ticket-quantity'),
                    controller: _qty, keyboardType: TextInputType.number,
                    onChanged: (_) => setState(_invalidate)),
                )),
                const SizedBox(width: 8),
                Expanded(child: _TicketField(
                  label: 'Limit ₹',
                  child: TextField(
                    key: const ValueKey('room-ticket-limit'),
                    controller: _limit,
                    keyboardType: const TextInputType.numberWithOptions(decimal: true),
                    onChanged: (_) => _invalidate()),
                )),
              ]),
              _TicketValue('Price source', _quote == null
                ? 'Not verified' : '${_quote!.source ?? 'Broker'} · ${_quote!.ltp}'),
              const SizedBox(height: 9),
              Container(
                decoration: BoxDecoration(
                  color: colors.surfaceContainerLow,
                  border: Border(left: BorderSide(color: colors.primary, width: 2))),
                padding: const EdgeInsets.all(10),
                child: Text(isPaper
                  ? 'Virtual orders only. The server independently checks a fresh '
                    'broker tick, exchange hours and available cash.'
                  : 'No real-money execution. The server can save only a blocked '
                    'LIVE intent until separate authorization gates are active.',
                  style: type.bodySmall?.copyWith(color: colors.onSurfaceVariant)),
              ),
              if (isPaper && !_paperEnabled) ...[
                const SizedBox(height: 9),
                OutlinedButton(
                  key: const ValueKey('room-enable-paper-manual'),
                  onPressed: _busy ? null : _resume,
                  child: const Text('Enable manual Paper orders')),
              ],
              if (_busy) const LinearProgressIndicator(minHeight: 2),
              if (_error != null) Padding(padding: const EdgeInsets.only(top: 7),
                child: Text(_error!, style: type.bodySmall?.copyWith(
                  color: colors.error))),
              const SizedBox(height: 13),
              Row(children: [
                Expanded(child: OutlinedButton(
                  onPressed: () => Navigator.of(context).pop(),
                  child: const Text('Cancel'))),
                const SizedBox(width: 9),
                Expanded(child: FilledButton(
                  key: const ValueKey('room-ticket-review'),
                  onPressed: _busy || _unknown || !_valid() ||
                    (isPaper && !_paperEnabled) ? null : _place,
                  child: Text(isPaper ? 'Review Paper order' : 'Validate intent'))),
              ]),
            ]),
          ),
        ),
      ),
    );
  }
}

class _TicketField extends StatelessWidget {
  const _TicketField({required this.label, required this.child});
  final String label;
  final Widget child;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(top: 9),
    child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Text(label.toUpperCase(), style: Theme.of(context).textTheme.labelSmall
        ?.copyWith(color: Theme.of(context).colorScheme.onSurfaceVariant)),
      const SizedBox(height: 4),
      child,
    ]),
  );
}

class _SideButton extends StatelessWidget {
  const _SideButton({required this.side, required this.selected,
    required this.onPressed});
  final String side;
  final bool selected;
  final VoidCallback onPressed;
  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return OutlinedButton(
      onPressed: onPressed,
      style: OutlinedButton.styleFrom(
        backgroundColor: selected ? colors.primaryContainer
          : colors.surfaceContainerLow,
        foregroundColor: selected ? colors.primary : colors.onSurfaceVariant,
        side: BorderSide(color: selected ? colors.primary : colors.outlineVariant),
        minimumSize: const Size(0, 36),
        padding: const EdgeInsets.all(6),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(6)),
      ),
      child: Text(side, style: const TextStyle(fontWeight: FontWeight.w700)),
    );
  }
}

class _TicketValue extends StatelessWidget {
  const _TicketValue(this.label, this.value);
  final String label, value;
  @override
  Widget build(BuildContext context) {
    final type = Theme.of(context).textTheme;
    return Padding(padding: const EdgeInsets.symmetric(vertical: 8),
      child: Row(children: [
        Expanded(child: Text(label, style: type.bodySmall)),
        Text(value, style: type.bodySmall?.copyWith(fontWeight: FontWeight.w700)),
      ]));
  }
}

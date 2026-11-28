
import 'dart:math';
import 'package:flutter/material.dart';
import '../../../data/authenticated_api_client.dart';
import '../../../data/instrument_repository.dart';
import '../data/customer_execution_repository.dart';
import '../data/customer_market_repository.dart';
import '../data/customer_paper_trading_repository.dart';
import '../data/customer_room_repository.dart';
import '../data/customer_trade_intent_repository.dart';
import 'minttick_room_stock_picker.dart';

/// The Buy/Sell bottom sheet matches the interactive MintTick HTML blueprint.
/// PAPER submits only virtual fills. LIVE stores PRECHECK_BLOCKED intents only.
class MintTickRoomOrderSheet extends StatefulWidget {
  const MintTickRoomOrderSheet({
    required this.roomId,
    required this.mode,
    required this.side,
    required this.market,
    required this.onChanged,
    this.onInstrumentSelected,
    this.initialInstrument,
    this.wallet,
    this.paper,
    this.execution,
    this.intents,
    this.liveCapability,
    this.liveEquityCapability,
    this.initialProduct = 'DELIVERY',
    super.key,
  });
  final String roomId, mode, side;
  /// Server capability for LIVE cash equities. Only when the server reports it
  /// executable (deployment gate on, broker connected, zero blockers) does the
  /// ticket use the server's submit route; otherwise it keeps the blocked-intent
  /// path unchanged.
  final LiveEquityCapability? liveCapability;
  /// Alias retained for workspace callers using the explicit capability name.
  final LiveEquityCapability? liveEquityCapability;
  /// Default LIVE product type ('DELIVERY' or 'INTRADAY'); an Intraday room
  /// pre-selects INTRADAY. PAPER cash-equity fills ignore this.
  final String initialProduct;
  final InstrumentRecord? initialInstrument;
  final HttpCustomerMarketRepository market;
  final CustomerPaperWallet? wallet;
  final HttpCustomerPaperTradingRepository? paper;
  final HttpCustomerExecutionRepository? execution;
  final HttpCustomerTradeIntentRepository? intents;
  final Future<void> Function() onChanged;
  final ValueChanged<InstrumentRecord>? onInstrumentSelected;

  @override
  State<MintTickRoomOrderSheet> createState() => _MintTickRoomOrderSheetState();
}

class _MintTickRoomOrderSheetState extends State<MintTickRoomOrderSheet> {
  LiveEquityCapability? get _capability =>
      widget.liveCapability ?? widget.liveEquityCapability;
  late String _side;
  late String _product;
  late InstrumentRecord? _stock;
  final _qty = TextEditingController(text: '1');
  final _limit = TextEditingController();
  CustomerLiveQuote? _quote;
  bool _busy = false, _unknown = false, _awaitingConfirmation = false;
  late bool _paperEnabled;
  String? _error;
  /// Server-reported outcome text for a LIVE submission. Never implies a fill
  /// unless the server state is FILLED.
  String? _outcome;
  String? _pendingKey;

  /// Server-owned LIVE order history, reloaded on every open so unresolved
  /// state survives closing and reopening the ticket.
  List<LiveEquityOrder> _liveOrders = const [];
  bool _liveOrdersVerified = false;
  bool _reconciling = false;
  String? _reconcileMessage;

  bool get _liveExecutable {
    final live = _capability;
    return live != null && live.executionEnabled &&
        live.orderRouteImplemented && live.blockers.isEmpty;
  }

  /// Orders the server has not yet proven against the broker order book.
  List<LiveEquityOrder> get _unresolved => [
        for (final order in _liveOrders)
          if (order.status.needsReconciliation) order,
      ];

  /// While LIVE dispatch is executable, a new order is allowed only with
  /// verified history that contains no unresolved order. Unverified history
  /// blocks too: an unknown history is never treated as an empty one.
  bool get _liveGateBlocked => widget.mode == 'LIVE' && _liveExecutable &&
      (!_liveOrdersVerified || _unresolved.isNotEmpty);

  @override
  void initState() {
    super.initState();
    _side = widget.side;
    _product = widget.initialProduct == 'INTRADAY' ? 'INTRADAY' : 'DELIVERY';
    _stock = widget.initialInstrument;
    _paperEnabled = widget.wallet?.halted == false;
    if (_stock != null) {
      _verifyQuote();
    }
    if (widget.mode == 'LIVE') {
      _loadLiveOrders();
    }
  }

  /// Reads the owner-scoped server history. A failure leaves history
  /// unverified, which blocks new LIVE orders until a later read succeeds.
  Future<void> _loadLiveOrders() async {
    final repo = widget.execution;
    if (widget.mode != 'LIVE' || repo == null) return;
    try {
      final orders = await repo.listLiveEquityOrders(widget.roomId);
      if (!mounted) return;
      setState(() {
        _liveOrders = orders;
        _liveOrdersVerified = true;
      });
    } catch (_) {
      if (!mounted) return;
      setState(() => _liveOrdersVerified = false);
    }
  }

  /// Asks the server to reconcile one order with the broker's order book.
  /// The result is accepted only as the server reports it. Unresolved stays
  /// unresolved, and nothing here can place an order.
  Future<void> _reconcile(LiveEquityOrder order) async {
    final repo = widget.execution;
    if (_reconciling || repo == null) return;
    setState(() { _reconciling = true; _reconcileMessage = null; });
    try {
      final updated =
          await repo.reconcileLiveEquityOrder(widget.roomId, order.id);
      if (updated.id != order.id) {
        throw const FormatException('Unexpected reconcile response');
      }
      if (!mounted) return;
      setState(() {
        _liveOrders = [
          for (final o in _liveOrders) o.id == updated.id ? updated : o,
        ];
        _reconcileMessage = updated.status.needsReconciliation
            ? 'Still unresolved. The broker has not confirmed this order yet.'
            : _statusText(updated);
        if (_unresolved.isEmpty) _pendingKey = null;
      });
      await widget.onChanged();
    } catch (_) {
      if (!mounted) return;
      setState(() => _reconcileMessage =
          'Reconciliation could not be verified. Orders stay blocked.');
    } finally {
      if (mounted) setState(() => _reconciling = false);
    }
  }

  @override
  void dispose() {
    _qty.dispose();
    _limit.dispose();
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

  Future<void> _chooseStock() async {
    if (_busy) return;
    final chosen = await showModalBottomSheet<InstrumentRecord>(
      context: context,
      useSafeArea: true,
      isScrollControlled: true,
      backgroundColor: Theme.of(context).colorScheme.surfaceContainerHigh,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(16))),
      builder: (_) => MintTickRoomStockPicker(
        market: widget.market,
        initialQuery: _stock?.tradingSymbol ?? '',
      ),
    );
    if (chosen == null || !mounted) return;
    setState(() {
      _stock = chosen;
      _quote = null;
      _limit.clear();
      _invalidate();
    });
    widget.onInstrumentSelected?.call(chosen);
    await _verifyQuote();
  }

  Future<void> _verifyQuote() async {
    final stock = _stock;
    if (stock == null || _busy) return;
    setState(() { _busy = true; _quote = null; _error = null; });
    try {
      final value = await widget.market.liveQuote(stock.id);
      if (!mounted || _stock?.id != stock.id) return;
      if (!const {'LIVE', 'LAST_CLOSE'}.contains(value.status) ||
          value.ltp == null || value.exchangeAt == null) {
        throw const FormatException('Broker price not verified');
      }
      setState(() {
        _quote = value;
        // Auto-populate a limit from verified LIVE quotes only, never
        // an off-hours last-recorded price.
        if (value.status == 'LIVE' && _limit.text.trim().isEmpty) {
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
    // Keep duplicate submission disabled while allowing the confirmation
    // sheet to settle; an infinite spinner behind a dialog would prevent
    // deterministic widget validation and wastes rendering resources.
    setState(() => _awaitingConfirmation = true);
    try {
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
    } finally {
      if (mounted) setState(() => _awaitingConfirmation = false);
    }
  }

  Future<void> _place() async {
    if (_liveGateBlocked) {
      setState(() => _error = 'Reconcile unresolved LIVE orders, and wait for the '
          'order history to verify, before placing another order.');
      return;
    }
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
        if (widget.execution == null) {
          throw StateError('LIVE execution service unavailable');
        }
        final live = _capability;
        // Executable only when the server says so on BOTH surfaces. The client
        // never infers executability from its own state.
        final executable = live != null && live.executionEnabled &&
            live.orderRouteImplemented && live.blockers.isEmpty;
        final preview = await widget.execution!.preflight(
          roomId: widget.roomId,
          instrumentId: stock.id,
          side: _side,
          quantity: quantity,
          limitPrice: price,
        );
        final previewBlockers =
            preview is Map<String, dynamic> ? preview['blockers'] : null;
        if (preview is! Map<String, dynamic> ||
            preview['roomId'] != widget.roomId ||
            preview['mode'] != 'LIVE' ||
            preview['instrumentId'] != stock.id ||
            previewBlockers is! List ||
            (executable
                ? preview['executable'] != true || previewBlockers.isNotEmpty
                : preview['executable'] != false || previewBlockers.isEmpty)) {
          throw const FormatException('Unverified LIVE preflight');
        }
        if (executable) {
          await _submitLiveEquity(stock, quantity, price, limitPrice);
          return;
        }
        if (widget.intents == null) {
          throw StateError('LIVE intent service unavailable');
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

  /// Server-executable LIVE cash-equity submission. Requires explicit final
  /// confirmation for this one order. One request, no retry. A transport error
  /// or ambiguous reply is UNKNOWN and blocks further orders until reconciled.
  /// A definite server 4xx means nothing was placed.
  Future<void> _submitLiveEquity(InstrumentRecord stock, int quantity,
      num price, String limitPrice) async {
    final approved = await _confirm(
      'LIVE ORDER · REAL MONEY · ${_capability?.selectedBroker ?? 'broker'}\n'
      '$_side $quantity ${stock.tradingSymbol} at ₹$limitPrice · intraday · limit · day.\n\n'
      'This sends a real order to your broker after server checks. '
      'MintTick cannot cancel it from this screen.',
      'Submit LIVE order',
    );
    if (!approved || !mounted) return;
    final key = _pendingKey ??= HttpCustomerTradeIntentRepository.newKey();
    var requestSent = false;
    setState(() { _busy = true; _error = null; _outcome = null; });
    try {
      requestSent = true;
      final order = await widget.execution!.submitLiveEquityOrder(
        roomId: widget.roomId,
        idempotencyKey: key,
        instrumentId: stock.id,
        side: _side,
        quantity: quantity,
        limitPrice: price,
      );
      if (order.roomId != widget.roomId || order.instrumentId != stock.id ||
          order.side != _side) {
        throw const FormatException('Unexpected LIVE order receipt');
      }
      if (!mounted) return;
      setState(() {
        _outcome = _statusText(order);
        _liveOrders = [
          order,
          for (final o in _liveOrders) if (o.id != order.id) o,
        ];
        if (order.status.needsReconciliation) {
          _reconcileMessage = 'Unresolved. Reconcile before placing another order.';
        } else {
          _pendingKey = null;
        }
      });
      await widget.onChanged();
    } catch (error) {
      if (!mounted) return;
      final definiteRejection = error is ApiHttpFailure &&
          error.statusCode != null &&
          error.statusCode! >= 400 && error.statusCode! < 500;
      setState(() {
        if (definiteRejection) {
          _pendingKey = null;
          _error = 'Order rejected by the server. Nothing was placed.';
        } else {
          _error = requestSent
              ? 'Order outcome UNKNOWN. The server is checking its record. '
                  'Reconcile before placing another order.'
              : 'Order could not be submitted. Check stock, quote and price.';
        }
      });
      // After a dispatch attempt, the server's own record decides whether an
      // order is pending. Re-read it rather than trusting the local outcome.
      if (requestSent && !definiteRejection) {
        await _loadLiveOrders();
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  /// Wording derived only from the server's own order state.
  static String _statusText(LiveEquityOrder order) => switch (order.status) {
        LiveEquityOrderStatus.acknowledged =>
          'Accepted by broker · not yet filled.',
        LiveEquityOrderStatus.partiallyFilled =>
          'Partially filled · ${order.filledQuantity} of ${order.quantity}.',
        LiveEquityOrderStatus.filled =>
          'Filled · ${order.filledQuantity} of ${order.quantity}.',
        LiveEquityOrderStatus.rejected => 'Rejected by broker.',
        LiveEquityOrderStatus.cancelled => 'Cancelled.',
        _ => 'Outcome unknown · reconciliation required.',
      };

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final type = Theme.of(context).textTheme;
    final isPaper = widget.mode == 'PAPER';
    final isLiveTicket = widget.mode == 'LIVE';
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
                  child: InkWell(
                    key: const ValueKey('room-ticket-stock'),
                    onTap: _busy ? null : _chooseStock,
                    borderRadius: BorderRadius.circular(11),
                    child: Container(
                      height: 48,
                      padding: const EdgeInsets.symmetric(horizontal: 10),
                      decoration: BoxDecoration(
                        color: colors.surfaceContainerLow,
                        border: Border.all(color: colors.outlineVariant),
                        borderRadius: BorderRadius.circular(11)),
                      child: Row(children: [
                        Expanded(child: Text(
                          _stock?.tradingSymbol ?? 'Search & select stock',
                          maxLines: 1, overflow: TextOverflow.ellipsis,
                          style: type.bodySmall?.copyWith(
                            color: _stock == null ? colors.onSurfaceVariant
                                : colors.onSurface,
                            fontWeight: FontWeight.w700))),
                        Icon(Icons.search_rounded, size: 19, color: colors.primary),
                      ]),
                    ),
                  ),
                )),
                const SizedBox(width: 8),
                Expanded(child: _TicketField(
                  label: 'Product',
                  child: isPaper
                    // Manual Paper fills are cash-equity positions; the
                    // backend does not accept productType or manage an
                    // intraday lifecycle for this order endpoint.
                    ? const SizedBox(
                        height: 48,
                        child: Align(
                          alignment: Alignment.centerLeft,
                          child: Text('Cash equity'),
                        ),
                      )
                    : DropdownButtonFormField<String>(
                        isExpanded: true,
                        initialValue: _product,
                        items: const [
                          DropdownMenuItem(value: 'DELIVERY', child: Text('Delivery')),
                          DropdownMenuItem(value: 'INTRADAY', child: Text('Intraday')),
                        ],
                        onChanged: (value) {
                          if (value != null) {
                            setState(() {
                              _product = value;
                              _invalidate();
                            });
                          }
                        },
                      ),
                )),
              ]),
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
                    onChanged: (_) => setState(_invalidate)),
                )),
              ]),
              const _TicketValue('Order type', 'Limit'),
              _TicketValue('Price source', _quote == null
                ? 'Unavailable' : _quote!.status == 'LIVE'
                  ? 'LIVE · ₹${_quote!.ltp}'
                  : 'LAST RECORDED · ₹${_quote!.ltp}'),
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
              if (_busy && !_awaitingConfirmation)
                const LinearProgressIndicator(minHeight: 2),
              if (isLiveTicket && _unresolved.isNotEmpty)
                _UnresolvedOrders(
                  orders: _unresolved,
                  reconciling: _reconciling,
                  message: _reconcileMessage,
                  onReconcile: _reconcile,
                ),
              // The server's result of a reconcile stays visible after the banner
              // clears, so the user sees that the order was resolved.
              if (isLiveTicket && _unresolved.isEmpty && _reconcileMessage != null)
                Padding(padding: const EdgeInsets.only(top: 9),
                  child: Text(_reconcileMessage!,
                    key: const ValueKey('room-live-reconcile-message'),
                    style: type.bodySmall)),
              if (isLiveTicket && _liveExecutable && !_liveOrdersVerified)
                Padding(padding: const EdgeInsets.only(top: 9),
                  child: Text('LIVE order history could not be verified. '
                      'New orders are blocked until it loads.',
                    key: const ValueKey('room-live-history-unverified'),
                    style: type.bodySmall?.copyWith(color: colors.error))),
              if (_outcome != null) Padding(padding: const EdgeInsets.only(top: 7),
                child: Text(_outcome!, key: const ValueKey('room-order-outcome'),
                  style: type.bodySmall?.copyWith(fontWeight: FontWeight.w700))),
              if (_error != null) Padding(padding: const EdgeInsets.only(top: 7),
                child: Text(_error!, key: const ValueKey('room-order-error'),
                  style: type.bodySmall?.copyWith(
                  color: colors.error))),
              const SizedBox(height: 13),
              Row(children: [
                Expanded(child: OutlinedButton(
                  onPressed: () => Navigator.of(context).pop(),
                  child: const Text('Cancel'))),
                const SizedBox(width: 9),
                Expanded(child: FilledButton(
                  key: const ValueKey('room-ticket-review'),
                  onPressed: _busy || _unknown || _liveGateBlocked || !_valid() ||
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

/// Server-recorded LIVE orders the broker has not yet confirmed. Shown on every
/// open of the ticket until the server reports each one resolved.
class _UnresolvedOrders extends StatelessWidget {
  const _UnresolvedOrders({required this.orders, required this.reconciling,
    required this.message, required this.onReconcile});
  final List<LiveEquityOrder> orders;
  final bool reconciling;
  final String? message;
  final ValueChanged<LiveEquityOrder> onReconcile;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final type = Theme.of(context).textTheme;
    return Container(
      key: const ValueKey('room-live-unresolved'),
      margin: const EdgeInsets.only(top: 9),
      padding: const EdgeInsets.all(10),
      decoration: BoxDecoration(
        color: colors.errorContainer.withValues(alpha: 0.35),
        border: Border.all(color: colors.error),
        borderRadius: BorderRadius.circular(8)),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text('UNRESOLVED LIVE ORDER · RECONCILE FIRST',
          style: type.labelMedium?.copyWith(
            color: colors.error, fontWeight: FontWeight.w800)),
        const SizedBox(height: 4),
        Text('The broker has not confirmed these orders. New LIVE orders stay '
            'blocked until the server confirms each one.',
          style: type.bodySmall),
        for (final order in orders) ...[
          const SizedBox(height: 6),
          Row(children: [
            Expanded(child: Text(
              '${order.side} ${order.quantity} ${order.tradingSymbol} · '
              '${order.status.name.toUpperCase()}',
              style: type.bodySmall?.copyWith(fontWeight: FontWeight.w700))),
            TextButton(
              key: ValueKey('room-live-reconcile-${order.id}'),
              onPressed: reconciling ? null : () => onReconcile(order),
              child: const Text('Reconcile')),
          ]),
        ],
        if (message != null) Text(message!,
          key: const ValueKey('room-live-reconcile-message'),
          style: type.bodySmall),
      ]),
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

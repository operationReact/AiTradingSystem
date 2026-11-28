import 'package:flutter/material.dart';

import '../data/customer_paper_fno_repository.dart';
import '../data/customer_trade_intent_repository.dart';
import '../data/customer_upstox_fno_live_repository.dart';

/// Real-money NIFTY long-option ticket: one broker, one lot, one explicit
/// confirmation per request. All authorization and sizing are server-side.
class MintTickUpstoxFnoLivePanel extends StatefulWidget {
  const MintTickUpstoxFnoLivePanel({
    required this.roomId, required this.repository,
    this.horizon = 'POSITIONAL', super.key,
  });

  final String roomId;
  final CustomerUpstoxFnoLiveRepository repository;

  /// Room horizon: 'INTRADAY' or 'POSITIONAL'. Intraday auto-selects the
  /// nearest verified listed expiry; positional requires an explicit choice.
  /// Both use the identical verified contract and blocked-intent APIs; the
  /// backend exposes no per-order product field, so this is selection workflow
  /// only and implies no broker execution.
  final String horizon;

  bool get intradayHorizon => horizon == 'INTRADAY';

  @override
  State<MintTickUpstoxFnoLivePanel> createState() =>
      _MintTickUpstoxFnoLivePanelState();
}

class _MintTickUpstoxFnoLivePanelState
    extends State<MintTickUpstoxFnoLivePanel> {
  UpstoxFnoLiveCapability? _capability;
  CustomerFnoMarket? _market;
  List<DateTime> _expiries = const [];
  DateTime? _expiry;
  List<UpstoxFnoLiveReceipt> _orders = const [];
  bool _busy = false;
  String? _error, _pendingKey;
  int _generation = 0;

  @override
  void initState() {
    super.initState();
    _refresh();
  }

  @override
  void dispose() {
    ++_generation;
    super.dispose();
  }

  Future<void> _refresh() async {
    if (_busy) return;
    final request = ++_generation;
    setState(() {
      _busy = true;
      _error = null;
      _market = null; // never display a previously fresh quote as current
    });
    try {
      final cap = await widget.repository.capability(widget.roomId);
      final orders = await widget.repository.orders(widget.roomId);
      if (!mounted || request != _generation) return;
      setState(() {
        _capability = cap;
        _orders = orders;
        _expiries = const [];
        _expiry = null;
      });
      if (!cap.contractSourceReady || !cap.brokerConnected) return;
      final listed = await widget.repository.expiries(widget.roomId);
      if (!mounted || request != _generation) return;
      if (listed.verified) {
        setState(() => _expiries = listed.expiries);
      } else {
        setState(() => _error =
            'Listed expiry selection unavailable (${listed.status}).');
      }
    } catch (_) {
      if (mounted && request == _generation) {
        setState(() {
          _capability = null;
          _expiries = const [];
          _error = 'LIVE F&O readiness or order history cannot be verified. '
              'No new order can be submitted.';
        });
      }
    } finally {
      if (mounted && request == _generation) setState(() => _busy = false);
    }
    // Intraday rooms open on the nearest verified listed expiry (sorted
    // ascending). Done after readiness load so _loadMarket is not short-
    // circuited by the in-flight _busy guard; positional rooms wait for an
    // explicit choice.
    if (widget.intradayHorizon && mounted && request == _generation &&
        _expiry == null && _expiries.isNotEmpty) {
      _loadMarket(_expiries.first);
    }
  }

  Future<void> _loadMarket(DateTime expiry) async {
    if (_busy || !_expiries.contains(expiry)) return;
    final request = ++_generation;
    setState(() {
      _busy = true;
      _market = null;
      _expiry = expiry;
      _error = null;
    });
    try {
      final market = await widget.repository.market(widget.roomId, expiry);
      if (!mounted || request != _generation) return;
      if (!market.displayRightsVerified || market.liveOrderEnabled ||
          market.expiry != expiry ||
          market.underlyingKey != 'NSE_INDEX|Nifty 50') {
        throw const FormatException('Unverified NIFTY LIVE option list');
      }
      setState(() => _market = market);
    } catch (_) {
      if (mounted && request == _generation) {
        setState(() => _error =
            'Cannot verify actual NSE option quotes. No LIVE order available.');
      }
    } finally {
      if (mounted && request == _generation) setState(() => _busy = false);
    }
  }

  bool _canOrder(CustomerFnoLeg leg, String side) {
    final reference = side == 'BUY' ? leg.ask : leg.bid;
    return !_busy && _pendingKey == null &&
        _capability?.eligibleForOrderPreflight == true &&
        _market?.hasFreshQuotes == true && leg.quoteFresh &&
        leg.exchangeAt != null && leg.observedAt != null &&
        reference != null && reference.isFinite && reference > 0;
  }

  Future<void> _submit(CustomerFnoLeg leg, String side) async {
    if (!_canOrder(leg, side) || _expiry == null) return;
    final reference = side == 'BUY' ? leg.ask! : leg.bid!;
    final expiry = _expiry!;
    final value = reference * leg.lotSize;
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Confirm REAL Upstox F&O order'),
        content: Text(
          'This will send a REAL MONEY order to Upstox, NOT Paper.\n\n'
          '$side 1 lot (${leg.lotSize} units) of ${leg.tradingSymbol}\n'
          'Expiry: ${_ymd(expiry)}\n'
          'LIMIT: ₹$reference per unit\n'
          'Approximate option premium: ₹${value.toStringAsFixed(2)} '
          '(fees excluded).\n'
          'Broker quote: ${leg.exchangeAt!.toLocal()}\n\n'
          'Only click Place REAL order if you personally authorize '
          'this exact trade. An acknowledgement is not a fill.',
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false),
            child: const Text('Cancel')),
          FilledButton(
            key: const ValueKey('confirm-real-upstox-nfo-order'),
            onPressed: () => Navigator.pop(context, true),
            child: Text('Place REAL $side'),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted || !_canOrder(leg, side) ||
        _expiry != expiry) { return; }
    // A unique secure key exists for this exact submission. Ambiguous network
    // failures NEVER generate an automatic second broker request.
    final key = HttpCustomerTradeIntentRepository.newKey();
    setState(() {
      _pendingKey = key;
      _busy = true;
      _error = null;
    });
    try {
      final receipt = await widget.repository.submit(
        roomId: widget.roomId, idempotencyKey: key,
        instrumentKey: leg.instrumentKey, expiry: expiry,
        side: side, limitPrice: reference, explicitlyConfirmed: true,
      );
      if (!mounted) return;
      setState(() {
        _orders = [receipt, ..._orders.where((r) => r.id != receipt.id)];
        _error = 'Broker custody: ${receipt.state}. '
            'ACKNOWLEDGED does not mean filled. Reconcile to verify.';
      });
    } catch (_) {
      if (mounted) {
        setState(() => _error =
            'Order outcome is not verified. Do NOT resubmit. '
            'Refresh broker order history and reconcile any uncertain order.');
      }
    } finally {
      if (mounted) {
        setState(() {
          _busy = false;
          // Submit stays disabled until the user leaves/re-enters the screen.
          // Never issue another key in response to a network uncertainty.
        });
      }
    }
  }

  Future<void> _reconcile(UpstoxFnoLiveReceipt receipt) async {
    if (_busy) return;
    setState(() { _busy = true; _error = null; });
    try {
      final updated = await widget.repository.reconcile(widget.roomId, receipt.id);
      if (!mounted) return;
      setState(() {
        _orders = [for (final row in _orders) row.id == updated.id ? updated : row];
      });
    } catch (_) {
      if (mounted) {
        setState(() => _error =
            'Broker reconciliation unavailable. Do not place another order.');
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final type = Theme.of(context).textTheme;
    final cap = _capability;
    return SingleChildScrollView(child: Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Row(children: [
          Expanded(child: Text('UPSTOX · LIVE NIFTY OPTIONS',
            style: type.titleSmall?.copyWith(fontWeight: FontWeight.w800))),
          IconButton(
            key: const ValueKey('live-fno-refresh'),
            tooltip: 'Refresh broker and exchange readiness',
            onPressed: _busy ? null : _refresh,
            icon: const Icon(Icons.refresh_rounded),
          ),
        ]),
        const SizedBox(height: 6),
        Text('Real money · Manual only · 1 listed lot · Long options',
          style: type.bodySmall?.copyWith(color: cs.onSurfaceVariant)),
        if (_busy) const LinearProgressIndicator(minHeight: 2),
        if (_error != null)
          Padding(padding: const EdgeInsets.symmetric(vertical: 8),
            child: Text(_error!, style: type.bodySmall?.copyWith(color: cs.error))),
        if (cap == null)
          const Padding(padding: EdgeInsets.symmetric(vertical: 20),
            child: Text('Checking verified LIVE broker capability…'))
        else ...[
          const SizedBox(height: 10),
          Text(cap.eligibleForOrderPreflight
                  ? 'Order preflight available — each contract is checked again'
                  : 'LIVE orders blocked',
            style: type.titleSmall?.copyWith(
              color: cap.eligibleForOrderPreflight ? cs.primary : cs.error)),
          for (final reason in cap.reasons)
            Padding(padding: const EdgeInsets.only(top: 3),
              child: Text(reason.replaceAll('_', ' '),
                style: type.bodySmall?.copyWith(color: cs.onSurfaceVariant))),
          const SizedBox(height: 12),
          DropdownButtonFormField<DateTime>(
            key: const ValueKey('live-fno-expiry'),
            initialValue: _expiries.contains(_expiry) ? _expiry : null,
            decoration: const InputDecoration(labelText: 'Verified NIFTY expiry'),
            items: [for (final date in _expiries)
              DropdownMenuItem(value: date, child: Text(_ymd(date)))],
            onChanged: _busy ? null : (date) {
              if (date != null) _loadMarket(date);
            },
          ),
          const SizedBox(height: 9),
          if (_market != null) ...[
            Text('Quote state: ${_market!.status} · Exchange V3 only',
              style: type.labelMedium),
            const SizedBox(height: 6),
            for (final strike in _market!.strikes) ...[
              Card(child: Padding(
                padding: const EdgeInsets.all(10),
                child: Column(crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('Strike ₹${strike.strikePrice}',
                      style: type.labelLarge?.copyWith(fontWeight: FontWeight.bold)),
                    for (final leg in [strike.call, strike.put])
                      if (leg != null)
                        Padding(padding: const EdgeInsets.only(top: 8),
                          child: Row(children: [
                            Expanded(child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text('${leg.side} · ${leg.tradingSymbol}',
                                  maxLines: 1, overflow: TextOverflow.ellipsis,
                                  style: type.bodySmall),
                                Text(leg.quoteFresh
                                    ? 'Bid ₹${leg.bid}  ·  Ask ₹${leg.ask}'
                                    : 'Quote unavailable / stale',
                                  style: type.labelSmall?.copyWith(
                                    color: cs.onSurfaceVariant)),
                              ],
                            )),
                            TextButton(
                              onPressed: _canOrder(leg, 'BUY')
                                  ? () => _submit(leg, 'BUY') : null,
                              child: const Text('BUY'),
                            ),
                            TextButton(
                              onPressed: _canOrder(leg, 'SELL')
                                  ? () => _submit(leg, 'SELL') : null,
                              child: const Text('CLOSE'),
                            ),
                          ])),
                  ]),
              )),
            ],
          ],
        ],
        const SizedBox(height: 16),
        Text('Broker order receipts', style: type.titleSmall),
        for (final receipt in _orders.take(15))
          ListTile(
            dense: true,
            contentPadding: EdgeInsets.zero,
            title: Text('${receipt.side} ${receipt.instrumentKey}'),
            subtitle: Text('${receipt.state} · ${receipt.quantity} units '
              '· ₹${receipt.limitPrice}'),
            trailing: const {'SUBMITTING','UNKNOWN','ACKNOWLEDGED'}
                    .contains(receipt.state)
                ? TextButton(onPressed: _busy ? null
                    : () => _reconcile(receipt), child: const Text('Reconcile'))
                : null,
          ),
        if (_orders.isEmpty)
          const Text('No verified LIVE order receipts.'),
        const SizedBox(height: 12),
        Text('No automated order placement. Failed/unknown requests '
            'must be reconciled before trying another trade.',
          style: type.bodySmall?.copyWith(color: cs.onSurfaceVariant)),
      ],
    ));
  }

  static String _ymd(DateTime date) =>
      '${date.year.toString().padLeft(4, '0')}-'
      '${date.month.toString().padLeft(2, '0')}-'
      '${date.day.toString().padLeft(2, '0')}';
}

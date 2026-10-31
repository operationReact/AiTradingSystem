import 'package:flutter/material.dart';

import '../data/authenticated_api_client.dart';
import '../data/paper_trading_repository.dart';

class PaperOrdersPage extends StatefulWidget {
  const PaperOrdersPage({required this.repository, super.key});

  final PaperTradingRepository repository;

  @override
  State<PaperOrdersPage> createState() => _PaperOrdersPageState();
}

class _PaperOrdersPageState extends State<PaperOrdersPage> {
  final _symbol = TextEditingController();
  final _quantity = TextEditingController(text: '1');
  final _referencePrice = TextEditingController();
  final _stopLoss = TextEditingController();
  final _target = TextEditingController();
  final _signalGeneratedAt = TextEditingController();

  String _side = 'BUY';
  String _productType = 'DELIVERY';
  String? _pendingIdempotencyKey;
  String? _validationError;
  PaperOrderPage? _orders;
  ApiFailure? _listFailure;
  ApiFailure? _submitFailure;
  PaperOrderResult? _lastResult;
  bool _loading = false;
  bool _submitting = false;

  @override
  void initState() {
    super.initState();
    _refresh();
  }

  @override
  void dispose() {
    _symbol.dispose();
    _quantity.dispose();
    _referencePrice.dispose();
    _stopLoss.dispose();
    _target.dispose();
    _signalGeneratedAt.dispose();
    super.dispose();
  }

  Future<void> _refresh() async {
    if (_loading) return;
    setState(() {
      _loading = true;
      _listFailure = null;
    });
    try {
      final orders = await widget.repository.fetchOrders();
      if (!mounted) return;
      setState(() => _orders = orders);
    } on ApiFailure catch (failure) {
      if (!mounted) return;
      setState(() => _listFailure = failure);
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<void> _submit() async {
    if (_submitting) return;
    final quantity = int.tryParse(_quantity.text.trim());
    final referencePrice = num.tryParse(_referencePrice.text.trim());
    final stopLoss = _stopLoss.text.trim().isEmpty ? null : num.tryParse(_stopLoss.text.trim());
    final target = _target.text.trim().isEmpty ? null : num.tryParse(_target.text.trim());
    final signalGeneratedAt = DateTime.tryParse(_signalGeneratedAt.text.trim());
    final symbol = _symbol.text.trim().toUpperCase();

    String? validation;
    if (!RegExp(r'^[A-Z0-9&-]{1,20}$').hasMatch(symbol)) {
      validation = 'Enter a valid backend-supported symbol.';
    } else if (quantity == null || quantity < 1 || quantity > 10000) {
      validation = 'Quantity must be between 1 and 10000.';
    } else if (referencePrice == null || referencePrice <= 0) {
      validation = 'Reference price must be greater than zero.';
    } else if (signalGeneratedAt == null) {
      validation = 'Signal generated time must be a valid ISO-8601 timestamp.';
    } else if (_side == 'BUY' && (stopLoss == null || target == null)) {
      validation = 'BUY requires both stop loss and target.';
    } else if (_side == 'BUY' && stopLoss! >= referencePrice) {
      validation = 'BUY stop loss must be below reference price.';
    } else if (_side == 'BUY' && target! <= referencePrice) {
      validation = 'BUY target must be above reference price.';
    }

    if (validation != null) {
      setState(() {
        _validationError = validation;
        _submitFailure = null;
        _lastResult = null;
      });
      return;
    }

    final idempotencyKey = _pendingIdempotencyKey ?? 'flutter-paper-${DateTime.now().microsecondsSinceEpoch}';
    _pendingIdempotencyKey = idempotencyKey;
    setState(() {
      _submitting = true;
      _validationError = null;
      _submitFailure = null;
      _lastResult = null;
    });

    try {
      final result = await widget.repository.createOrder(
        CreatePaperOrderCommand(
          symbol: symbol,
          side: _side,
          quantity: quantity!,
          referencePrice: referencePrice!,
          stopLoss: stopLoss,
          target: target,
          signalGeneratedAt: signalGeneratedAt!,
          productType: _productType,
        ),
        idempotencyKey: idempotencyKey,
      );
      if (!mounted) return;
      setState(() {
        _lastResult = result;
        _pendingIdempotencyKey = null;
      });
      await _refresh();
    } on ApiFailure catch (failure) {
      if (!mounted) return;
      setState(() => _submitFailure = failure);
      // Keep the same idempotency key. A retry must never create a second intent.
    } finally {
      if (mounted) setState(() => _submitting = false);
    }
  }

  Future<void> _cancel(PaperOrder order) async {
    if (order.id.isEmpty) return;
    setState(() {
      _validationError = null;
      _submitFailure = null;
    });
    try {
      final result = await widget.repository.cancelOrder(order.id);
      if (!mounted) return;
      setState(() => _lastResult = result);
      await _refresh();
    } on ApiFailure catch (failure) {
      if (!mounted) return;
      setState(() => _submitFailure = failure);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('PAPER orders')),
      body: SafeArea(
        child: ListView(
          key: const ValueKey('paper-orders-list'),
          padding: const EdgeInsets.fromLTRB(16, 16, 16, 96),
          children: [
            Semantics(header: true, child: Text('Create PAPER order intent', style: Theme.of(context).textTheme.headlineSmall)),
            const SizedBox(height: 8),
            const Text('This submits only to the PAPER execution API. Backend risk rules remain authoritative. No LIVE broker route is exposed here.'),
            const SizedBox(height: 16),
            _orderForm(context),
            if (_validationError != null) ...[
              const SizedBox(height: 12),
              Card(
                key: const ValueKey('paper-order-validation-error'),
                child: Padding(padding: const EdgeInsets.all(16), child: Text(_validationError!)),
              ),
            ],
            if (_submitFailure != null) ...[
              const SizedBox(height: 12),
              _submitError(_submitFailure!),
            ],
            if (_lastResult != null) ...[
              const SizedBox(height: 12),
              _resultCard(_lastResult!),
            ],
            const SizedBox(height: 24),
            Semantics(header: true, child: Text('Recent PAPER orders', style: Theme.of(context).textTheme.titleLarge)),
            const SizedBox(height: 8),
            if (_loading && _orders == null)
              const Center(key: ValueKey('paper-orders-loading'), child: Padding(padding: EdgeInsets.all(32), child: CircularProgressIndicator()))
            else if (_listFailure != null && _orders == null)
              _listError(_listFailure!)
            else if (_orders != null) ...[
              if (_listFailure != null)
                Card(
                  key: const ValueKey('paper-orders-stale'),
                  child: Padding(
                    padding: const EdgeInsets.all(16),
                    child: Text('Refresh failed (${_listFailure!.code}). Orders below are last-known evidence.'),
                  ),
                ),
              if (_orders!.items.isEmpty)
                const Card(
                  key: ValueKey('paper-orders-empty'),
                  child: Padding(padding: EdgeInsets.all(16), child: Text('No PAPER orders are reported by the backend.')),
                )
              else
                ..._orders!.items.map(_orderCard),
            ],
          ],
        ),
      ),
      floatingActionButton: FloatingActionButton.extended(
        key: const ValueKey('paper-orders-refresh'),
        onPressed: _loading ? null : _refresh,
        icon: const Icon(Icons.refresh),
        label: const Text('Refresh'),
      ),
    );
  }

  Widget _orderForm(BuildContext context) => Card(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              TextField(key: const ValueKey('paper-order-symbol'), controller: _symbol, decoration: const InputDecoration(labelText: 'Symbol')),
              const SizedBox(height: 12),
              Wrap(
                spacing: 12,
                runSpacing: 12,
                children: [
                  DropdownButton<String>(
                    key: const ValueKey('paper-order-side'),
                    value: _side,
                    items: const [DropdownMenuItem(value: 'BUY', child: Text('BUY')), DropdownMenuItem(value: 'SELL', child: Text('SELL'))],
                    onChanged: (value) => value == null ? null : setState(() => _side = value),
                  ),
                  DropdownButton<String>(
                    key: const ValueKey('paper-order-product'),
                    value: _productType,
                    items: const [
                      DropdownMenuItem(value: 'DELIVERY', child: Text('DELIVERY')),
                      DropdownMenuItem(value: 'INTRADAY', child: Text('INTRADAY')),
                    ],
                    onChanged: (value) => value == null ? null : setState(() => _productType = value),
                  ),
                ],
              ),
              const SizedBox(height: 12),
              TextField(key: const ValueKey('paper-order-quantity'), controller: _quantity, keyboardType: TextInputType.number, decoration: const InputDecoration(labelText: 'Quantity')),
              const SizedBox(height: 12),
              TextField(key: const ValueKey('paper-order-reference-price'), controller: _referencePrice, keyboardType: const TextInputType.numberWithOptions(decimal: true), decoration: const InputDecoration(labelText: 'Reference price')),
              const SizedBox(height: 12),
              TextField(key: const ValueKey('paper-order-stop-loss'), controller: _stopLoss, keyboardType: const TextInputType.numberWithOptions(decimal: true), decoration: const InputDecoration(labelText: 'Stop loss (required for BUY)')),
              const SizedBox(height: 12),
              TextField(key: const ValueKey('paper-order-target'), controller: _target, keyboardType: const TextInputType.numberWithOptions(decimal: true), decoration: const InputDecoration(labelText: 'Target (required for BUY)')),
              const SizedBox(height: 12),
              TextField(
                key: const ValueKey('paper-order-signal-time'),
                controller: _signalGeneratedAt,
                decoration: const InputDecoration(
                  labelText: 'Signal generated at (ISO-8601)',
                  helperText: 'Enter authoritative signal time; the client does not invent it.',
                ),
              ),
              const SizedBox(height: 16),
              FilledButton.icon(
                key: const ValueKey('paper-order-submit'),
                onPressed: _submitting ? null : _submit,
                icon: _submitting ? const SizedBox.square(dimension: 18, child: CircularProgressIndicator(strokeWidth: 2)) : const Icon(Icons.science_outlined),
                label: Text(_submitting ? 'Submitting PAPER intent…' : 'Submit PAPER intent'),
              ),
              if (_pendingIdempotencyKey != null) ...[
                const SizedBox(height: 8),
                const Text('A retry will reuse the same idempotency key until this intent reaches a confirmed response.'),
              ],
            ],
          ),
        ),
      );

  Widget _submitError(ApiFailure failure) => Card(
        key: const ValueKey('paper-order-submit-error'),
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Text('Order not confirmed: ${failure.code}${failure.correlationId == null ? '' : ' • correlation ${failure.correlationId}'}'),
        ),
      );

  Widget _resultCard(PaperOrderResult result) => Card(
        key: const ValueKey('paper-order-result'),
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('Backend result: ${result.order.status}', style: Theme.of(context).textTheme.titleMedium),
              Text('${result.order.side} ${result.order.quantity} ${result.order.symbol} • ${result.order.productType}'),
              if (result.reasonCodes.isNotEmpty) Text('Risk reasons: ${result.reasonCodes.join(', ')}'),
              if (result.order.rejectionReason != null) Text('Rejection: ${result.order.rejectionReason}'),
              Text(result.replayed ? 'Idempotent replay confirmed.' : 'New PAPER intent response.'),
            ],
          ),
        ),
      );

  Widget _listError(ApiFailure failure) => Card(
        key: const ValueKey('paper-orders-error'),
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text('Orders unavailable: ${failure.code}'),
            const SizedBox(height: 12),
            FilledButton(onPressed: _refresh, child: const Text('Retry')),
          ]),
        ),
      );

  Widget _orderCard(PaperOrder order) => Card(
        key: ValueKey('paper-order-${order.id}'),
        child: ListTile(
          title: Text('${order.side} ${order.quantity} ${order.symbol}'),
          subtitle: Text(
            '${order.productType} • ${order.status} • filled ${order.filledQuantity}/${order.quantity}\n'
            'Entry ${order.entryPrice ?? 'UNKNOWN'} • avg fill ${order.averageFillPrice ?? 'UNKNOWN'} • updated ${order.updatedAt?.toIso8601String() ?? 'UNKNOWN'}',
          ),
          isThreeLine: true,
          trailing: order.status == 'NEW' || order.status == 'RISK_APPROVED'
              ? IconButton(
                  key: ValueKey('paper-order-cancel-${order.id}'),
                  tooltip: 'Cancel PAPER order',
                  onPressed: () => _cancel(order),
                  icon: const Icon(Icons.cancel_outlined),
                )
              : null,
        ),
      );
}

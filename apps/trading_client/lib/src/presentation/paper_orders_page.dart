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
      validation = 'Enter a valid stock symbol.';
    } else if (quantity == null || quantity < 1 || quantity > 10000) {
      validation = 'Quantity must be between 1 and 10000.';
    } else if (referencePrice == null || referencePrice <= 0) {
      validation = 'Reference price must be greater than zero.';
    } else if (signalGeneratedAt == null) {
      validation = 'Choose a valid signal time.';
    } else if (_side == 'BUY' && (stopLoss == null || target == null)) {
      validation = 'A PAPER buy needs both a stop loss and target.';
    } else if (_side == 'BUY' && stopLoss! >= referencePrice) {
      validation = 'Stop loss must be below the reference price.';
    } else if (_side == 'BUY' && target! <= referencePrice) {
      validation = 'Target must be above the reference price.';
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

  String _friendlyFailure(ApiFailure failure) {
    final code = failure.code.toUpperCase();
    if (code.contains('AUTH')) return 'Sign in again to continue.';
    if (code.contains('RISK')) return 'This PAPER trade did not pass the current risk rules.';
    if (code.contains('NETWORK') || code.contains('TIMEOUT')) return 'We could not reach the trading service. Try again.';
    return 'The PAPER order could not be completed.';
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Scaffold(
      appBar: AppBar(title: const Text('PAPER Orders')),
      body: SafeArea(
        child: ListView(
          key: const ValueKey('paper-orders-list'),
          padding: const EdgeInsets.fromLTRB(16, 16, 16, 96),
          children: [
            Container(
              padding: const EdgeInsets.all(20),
              decoration: BoxDecoration(
                borderRadius: BorderRadius.circular(24),
                gradient: LinearGradient(
                  colors: [theme.colorScheme.primaryContainer, theme.colorScheme.surfaceContainerHighest],
                ),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Semantics(
                    header: true,
                    child: Text('Practice a trade', style: theme.textTheme.headlineSmall?.copyWith(fontWeight: FontWeight.w900)),
                  ),
                  const SizedBox(height: 8),
                  Text(
                    'Create a simulated order with virtual money. PAPER mode lets you test entries, stops, targets and risk controls without sending a real broker order.',
                    style: theme.textTheme.bodyLarge?.copyWith(color: theme.colorScheme.onSurfaceVariant, height: 1.45),
                  ),
                ],
              ),
            ),
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
            Semantics(header: true, child: Text('Recent PAPER orders', style: theme.textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w800))),
            const SizedBox(height: 8),
            if (_loading && _orders == null)
              const Center(key: ValueKey('paper-orders-loading'), child: Padding(padding: EdgeInsets.all(32), child: CircularProgressIndicator()))
            else if (_listFailure != null && _orders == null)
              _listError(_listFailure!)
            else if (_orders != null) ...[
              if (_listFailure != null)
                const Card(
                  key: ValueKey('paper-orders-stale'),
                  child: Padding(
                    padding: EdgeInsets.all(16),
                    child: Text('Could not refresh. Showing your last loaded PAPER orders.'),
                  ),
                ),
              if (_orders!.items.isEmpty)
                const Card(
                  key: ValueKey('paper-orders-empty'),
                  child: Padding(padding: EdgeInsets.all(16), child: Text('No PAPER orders yet.')),
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
        icon: const Icon(Icons.refresh_rounded),
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
              Text('Trade setup', style: Theme.of(context).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w800)),
              const SizedBox(height: 14),
              TextField(key: const ValueKey('paper-order-symbol'), controller: _symbol, decoration: const InputDecoration(labelText: 'Stock symbol')),
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
              TextField(key: const ValueKey('paper-order-stop-loss'), controller: _stopLoss, keyboardType: const TextInputType.numberWithOptions(decimal: true), decoration: const InputDecoration(labelText: 'Stop loss')),
              const SizedBox(height: 12),
              TextField(key: const ValueKey('paper-order-target'), controller: _target, keyboardType: const TextInputType.numberWithOptions(decimal: true), decoration: const InputDecoration(labelText: 'Target')),
              const SizedBox(height: 12),
              TextField(
                key: const ValueKey('paper-order-signal-time'),
                controller: _signalGeneratedAt,
                decoration: const InputDecoration(
                  labelText: 'Signal time',
                  helperText: 'Use the time shown on the AI opportunity or trade idea.',
                ),
              ),
              const SizedBox(height: 16),
              FilledButton.icon(
                key: const ValueKey('paper-order-submit'),
                onPressed: _submitting ? null : _submit,
                icon: _submitting ? const SizedBox.square(dimension: 18, child: CircularProgressIndicator(strokeWidth: 2)) : const Icon(Icons.play_arrow_rounded),
                label: Text(_submitting ? 'Placing PAPER order…' : 'Place PAPER order'),
              ),
              if (_pendingIdempotencyKey != null) ...[
                const SizedBox(height: 8),
                const Text('Retrying will safely reuse the same order request.'),
              ],
            ],
          ),
        ),
      );

  Widget _submitError(ApiFailure failure) => Card(
        key: const ValueKey('paper-order-submit-error'),
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Text(_friendlyFailure(failure)),
        ),
      );

  Widget _resultCard(PaperOrderResult result) => Card(
        key: const ValueKey('paper-order-result'),
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('PAPER order ${result.order.status}', style: Theme.of(context).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w800)),
              const SizedBox(height: 4),
              Text('${result.order.side} ${result.order.quantity} ${result.order.symbol} • ${result.order.productType}'),
              if (result.order.rejectionReason != null) ...[
                const SizedBox(height: 6),
                Text('Why: ${result.order.rejectionReason}'),
              ],
            ],
          ),
        ),
      );

  Widget _listError(ApiFailure failure) => Card(
        key: const ValueKey('paper-orders-error'),
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(_friendlyFailure(failure)),
            const SizedBox(height: 12),
            FilledButton(onPressed: _refresh, child: const Text('Try again')),
          ]),
        ),
      );

  Widget _orderCard(PaperOrder order) => Card(
        key: ValueKey('paper-order-${order.id}'),
        child: ListTile(
          title: Text('${order.side} ${order.quantity} ${order.symbol}'),
          subtitle: Text(
            '${order.productType} • ${order.status} • filled ${order.filledQuantity}/${order.quantity}\n'
            'Entry ${order.entryPrice ?? '—'} • Avg fill ${order.averageFillPrice ?? '—'}',
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

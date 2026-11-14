import 'dart:math';

import 'package:flutter/material.dart';

import '../../../data/authenticated_api_client.dart';
import '../data/customer_paper_fno_repository.dart';

/// PAPER-only NIFTY long options: contract selection, BUY/SELL ticket and
/// virtual positions. Every value comes from the server. Anything the server
/// does not confirm is shown as unavailable, never as zero or as tradable.
class MintTickPaperFnoPanel extends StatefulWidget {
  const MintTickPaperFnoPanel({
    required this.roomId,
    required this.repository,
    this.initialExpiry,
    this.onFilled,
    super.key,
  });

  final String roomId;
  final CustomerPaperFnoRepository repository;
  /// Optional starting expiry. Never inferred: the user enters the verified one.
  final DateTime? initialExpiry;
  final Future<void> Function()? onFilled;

  @override
  State<MintTickPaperFnoPanel> createState() => _MintTickPaperFnoPanelState();
}

class _MintTickPaperFnoPanelState extends State<MintTickPaperFnoPanel> {
  CustomerFnoMarket? _market;
  List<CustomerFnoPosition> _positions = const [];
  String? _blocker;
  bool _loading = true;
  bool _submitting = false;
  String? _message;
  CustomerFnoLeg? _selected;
  String _side = 'BUY';
  int _lots = 1;
  final _price = TextEditingController();
  final _expiryText = TextEditingController();
  DateTime? _expiry;
  int _request = 0;

  @override
  void initState() {
    super.initState();
    _expiry = widget.initialExpiry;
    if (_expiry != null) {
      _expiryText.text = _ymd(_expiry!);
      _load();
    } else {
      _loading = false;
    }
  }

  @override
  void dispose() {
    _price.dispose();
    _expiryText.dispose();
    super.dispose();
  }

  void _applyExpiry() {
    final text = _expiryText.text.trim();
    final parsed = RegExp(r'^\d{4}-\d{2}-\d{2}$').hasMatch(text)
        ? DateTime.tryParse(text) : null;
    if (parsed == null) {
      setState(() => _blocker = 'Enter the verified expiry as YYYY-MM-DD.');
      return;
    }
    setState(() {
      _expiry = parsed;
      _selected = null;
      _message = null;
    });
    _load();
  }

  Future<void> _load() async {
    final expiry = _expiry;
    if (expiry == null) return;
    final request = ++_request;
    setState(() {
      _loading = true;
      _blocker = null;
    });
    try {
      final results = await Future.wait<Object>([
        widget.repository.market(widget.roomId, expiry),
        widget.repository.positions(widget.roomId),
      ]);
      if (!mounted || request != _request) return;
      setState(() {
        _market = results[0] as CustomerFnoMarket;
        _positions = results[1] as List<CustomerFnoPosition>;
        _loading = false;
      });
    } catch (error) {
      if (!mounted || request != _request) return;
      setState(() {
        _market = null;
        _positions = const [];
        _blocker = _describe(error);
        _loading = false;
      });
    }
  }

  static String _describe(Object error) {
    if (error is ApiHttpFailure) {
      return switch (error.statusCode) {
        409 => 'Upstox F&O display rights are not verified. No quotes are shown.',
        503 => 'F&O source is disabled or unavailable. Orders are closed.',
        404 => 'This PAPER room is unavailable.',
        401 || 403 => 'Sign in again to view F&O PAPER data.',
        _ => 'F&O data could not be loaded (HTTP ${error.statusCode}).',
      };
    }
    if (error is FormatException) {
      return 'The server response failed validation, so nothing is shown.';
    }
    return 'F&O data is unavailable. Check your connection and try again.';
  }

  bool get _canSubmit {
    final leg = _selected;
    final market = _market;
    if (_submitting || market == null || leg == null) return false;
    return market.hasFreshQuotes && leg.quoteFresh && leg.paperEligible &&
        _limitPrice() != null;
  }

  double? _limitPrice() {
    final value = double.tryParse(_price.text.trim());
    final tick = _selected?.tickSize.toDouble();
    if (value == null || !value.isFinite || value <= 0 || tick == null) return null;
    // Price must sit on the exchange tick grid.
    final steps = value / tick;
    if ((steps - steps.roundToDouble()).abs() > 1e-6) return null;
    return value;
  }

  void _select(CustomerFnoLeg leg) {
    setState(() {
      _selected = leg;
      _message = null;
      final suggested = _side == 'BUY' ? leg.ask : leg.bid;
      _price.text = suggested == null ? '' : suggested.toString();
    });
  }

  Future<void> _submit() async {
    final leg = _selected;
    final price = _limitPrice();
    if (!_canSubmit || leg == null || price == null) return;
    setState(() {
      _submitting = true;
      _message = null;
    });
    try {
      final fill = await widget.repository.submit(
        roomId: widget.roomId,
        idempotencyKey: _newKey(),
        instrumentKey: leg.instrumentKey,
        expiry: _expiry!,
        side: _side,
        lots: _lots,
        limitPrice: price,
      );
      if (!mounted) return;
      setState(() {
        _submitting = false;
        _message = '${fill.side} ${fill.quantity} filled at ${fill.fillPrice}. '
            'Virtual cash is now updated.';
      });
      await _load();
      await widget.onFilled?.call();
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _submitting = false;
        _message = error is ApiHttpFailure
            ? 'Order rejected by the server (HTTP ${error.statusCode}). Nothing was filled.'
            : 'The order outcome is unknown. It is not retried. Check positions before acting.';
      });
      await _load();
    }
  }

  static final _rand = Random.secure();
  static String _newKey() {
    final stamp = DateTime.now().microsecondsSinceEpoch.toRadixString(36);
    final nonce = _rand.nextInt(1 << 31).toRadixString(36);
    return 'fno-$stamp-$nonce';
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final type = Theme.of(context).textTheme;
    return Container(
      key: const ValueKey('paper-fno-panel'),
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: colors.surfaceContainerLow,
        border: Border.all(color: colors.outlineVariant),
        borderRadius: BorderRadius.circular(8)),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(children: [
            Expanded(child: Text('NIFTY options · PAPER', style: type.titleSmall)),
            Text('VIRTUAL · NO LIVE ORDERS',
              style: type.labelSmall?.copyWith(color: colors.onSurfaceVariant)),
          ]),
          const SizedBox(height: 4),
          Row(children: [
            Expanded(child: TextField(
              key: const ValueKey('paper-fno-expiry'),
              controller: _expiryText,
              decoration: const InputDecoration(
                labelText: 'Verified NIFTY expiry (YYYY-MM-DD)',
                isDense: true),
            )),
            const SizedBox(width: 8),
            FilledButton.tonal(
              key: const ValueKey('paper-fno-expiry-load'),
              onPressed: _applyExpiry,
              child: const Text('Load')),
          ]),
          const SizedBox(height: 10),
          if (_expiry == null)
            Text('Enter the expiry from the verified contract list to see strikes.',
              style: type.bodySmall),
          if (_loading)
            const Padding(
              padding: EdgeInsets.symmetric(vertical: 16),
              child: Center(child: CircularProgressIndicator(strokeWidth: 2))),
          if (_blocker != null)
            Text(_blocker!, key: const ValueKey('paper-fno-blocker'),
              style: TextStyle(color: colors.error)),
          if (!_loading && _expiry != null && _market != null) ..._marketView(colors, type),
          if (_message != null) ...[
            const SizedBox(height: 8),
            Text(_message!, key: const ValueKey('paper-fno-message'),
              style: type.bodySmall),
          ],
          const SizedBox(height: 12),
          _positionsView(colors, type),
        ],
      ),
    );
  }

  List<Widget> _marketView(ColorScheme colors, TextTheme type) {
    final market = _market!;
    final statusLabel = switch (market.status) {
      'FRESH_QUOTES' => 'Fresh quotes',
      'NO_FRESH_QUOTES' => 'No fresh quotes · orders disabled',
      _ => 'Market closed · orders disabled',
    };
    return [
      Text(statusLabel, key: const ValueKey('paper-fno-status'),
        style: type.labelMedium?.copyWith(color: colors.primary)),
      if (market.snapshotAt != null)
        Text('Snapshot ${market.snapshotAt!.toIso8601String()}',
          style: type.labelSmall?.copyWith(color: colors.onSurfaceVariant)),
      const SizedBox(height: 8),
      if (market.strikes.isEmpty)
        const Text('No verified strikes for this expiry.')
      else
        for (final strike in market.strikes) _strikeRow(strike, colors, type),
      const SizedBox(height: 10),
      _ticket(colors, type),
    ];
  }

  Widget _strikeRow(CustomerFnoStrike strike, ColorScheme colors, TextTheme type) =>
    Padding(
      padding: const EdgeInsets.only(bottom: 6),
      child: Row(children: [
        SizedBox(width: 70, child: Text(strike.strikePrice.toString(),
          style: type.labelLarge)),
        Expanded(child: _legChip(strike.call, 'CE', colors, type)),
        const SizedBox(width: 6),
        Expanded(child: _legChip(strike.put, 'PE', colors, type)),
      ]),
    );

  Widget _legChip(CustomerFnoLeg? leg, String label, ColorScheme colors, TextTheme type) {
    if (leg == null) {
      return Text('$label not listed', style: type.labelSmall);
    }
    final usable = leg.quoteFresh && leg.paperEligible;
    final selected = identical(_selected, leg) ||
        _selected?.instrumentKey == leg.instrumentKey;
    return OutlinedButton(
      key: ValueKey('paper-fno-leg-${leg.instrumentKey}'),
      onPressed: () => _select(leg),
      style: OutlinedButton.styleFrom(
        visualDensity: VisualDensity.compact,
        side: BorderSide(color: selected ? colors.primary : colors.outlineVariant)),
      child: Column(children: [
        Text('$label ${leg.lotSize}/lot', style: type.labelSmall),
        Text(usable
            ? 'Bid ${leg.bid ?? '—'} · Ask ${leg.ask ?? '—'}'
            : 'Quote not fresh',
          style: type.labelSmall?.copyWith(
            color: usable ? colors.onSurface : colors.onSurfaceVariant)),
      ]),
    );
  }

  Widget _ticket(ColorScheme colors, TextTheme type) {
    final leg = _selected;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (leg == null)
          Text('Select a CE or PE contract to place a PAPER order.',
            style: type.bodySmall)
        else ...[
          Text('${leg.tradingSymbol} · lot ${leg.lotSize} · tick ${leg.tickSize}',
            style: type.bodySmall),
          Row(children: [
            SegmentedButton<String>(
              key: const ValueKey('paper-fno-side'),
              segments: const [
                ButtonSegment(value: 'BUY', label: Text('BUY')),
                ButtonSegment(value: 'SELL', label: Text('SELL to close')),
              ],
              selected: {_side},
              onSelectionChanged: (s) => setState(() => _side = s.first),
            ),
          ]),
          Row(children: [
            IconButton(
              key: const ValueKey('paper-fno-lots-minus'),
              onPressed: _lots > 1 ? () => setState(() => _lots--) : null,
              icon: const Icon(Icons.remove)),
            Text('$_lots lot${_lots == 1 ? '' : 's'}',
              key: const ValueKey('paper-fno-lots')),
            IconButton(
              key: const ValueKey('paper-fno-lots-plus'),
              onPressed: _lots < 20 ? () => setState(() => _lots++) : null,
              icon: const Icon(Icons.add)),
          ]),
          TextField(
            key: const ValueKey('paper-fno-price'),
            controller: _price,
            keyboardType: const TextInputType.numberWithOptions(decimal: true),
            onChanged: (_) => setState(() {}),
            decoration: InputDecoration(
              labelText: 'Limit price (tick ${leg.tickSize})',
              errorText: _price.text.isEmpty || _limitPrice() != null
                  ? null : 'Must be a positive multiple of the tick size'),
          ),
          const SizedBox(height: 8),
          FilledButton(
            key: const ValueKey('paper-fno-submit'),
            onPressed: _canSubmit ? _submit : null,
            child: Text(_submitting ? 'Submitting…' : 'Place PAPER $_side'),
          ),
          if (!_canSubmit)
            Text('Disabled until the server reports fresh, PAPER-eligible quotes.',
              style: type.labelSmall?.copyWith(color: colors.onSurfaceVariant)),
        ],
      ],
    );
  }

  Widget _positionsView(ColorScheme colors, TextTheme type) {
    if (_positions.isEmpty) {
      return Text('No virtual F&O positions.', style: type.bodySmall);
    }
    return Column(
      key: const ValueKey('paper-fno-positions'),
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        for (final p in _positions)
          Padding(
            padding: const EdgeInsets.only(bottom: 4),
            child: Text(
              '${p.optionType} ${p.expiry.toIso8601String().substring(0, 10)} · '
              '${p.quantity} qty · cost ${_rupees(p.costBasisPaise)} · '
              '${p.indicativeUnrealizedPnlPaise == null || !p.priceFresh
                  ? 'P&L not valued' : 'P&L ${_rupees(p.indicativeUnrealizedPnlPaise!)}'}',
              style: type.bodySmall),
          ),
      ],
    );
  }

  static String _ymd(DateTime d) =>
      '${d.year.toString().padLeft(4, '0')}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';

  static String _rupees(int paise) {
    final sign = paise < 0 ? '-' : '';
    final abs = paise.abs();
    return '$sign₹${abs ~/ 100}.${(abs % 100).toString().padLeft(2, '0')}';
  }
}

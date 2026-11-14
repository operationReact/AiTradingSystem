import 'dart:async';
import 'dart:math';

import 'package:flutter/material.dart';

import '../../../data/authenticated_api_client.dart';
import '../data/customer_paper_fno_repository.dart';

/// PAPER-only NIFTY long options: contract selection, BUY/SELL ticket and
/// virtual positions. Every value comes from the server. Anything the server
/// does not confirm is shown as unavailable, never as zero or as tradable.
/// Client-side ceiling for acting on a quote. The server allows 15s; this is
/// conservative and fails closed. The backend still enforces its own limit.
const Duration kPaperFnoQuoteMaxAge = Duration(seconds: 12);

class MintTickPaperFnoPanel extends StatefulWidget {
  const MintTickPaperFnoPanel({
    required this.roomId,
    required this.repository,
    this.initialExpiry,
    this.onFilled,
    this.clock = DateTime.now,
    super.key,
  });

  /// Injected for tests; the production default is the device clock.
  final DateTime Function() clock;

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
  bool _positionsLoading = true;
  String? _positionsError;
  int _positionsRequest = 0;
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
  DateTime? _quotesReceivedAt;
  Timer? _ageTimer;
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
      _loadPositions();
    }
  }

  @override
  void dispose() {
    _ageTimer?.cancel();
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

  // A quote rights/source error must not erase existing PAPER positions.
  Future<void> _loadPositions() async {
    final request = ++_positionsRequest;
    if (!mounted) return;
    setState(() {
      _positionsLoading = true;
      _positionsError = null;
    });
    try {
      final positions = await widget.repository.positions(widget.roomId);
      if (!mounted || request != _positionsRequest) return;
      setState(() {
        _positions = positions;
        _positionsLoading = false;
      });
    } catch (_) {
      if (!mounted || request != _positionsRequest) return;
      setState(() {
        _positions = const [];
        _positionsLoading = false;
        _positionsError = 'Virtual F&O positions unavailable. Retry before acting.';
      });
    }
  }

  Future<void> _load() async {
    final expiry = _expiry;
    if (expiry == null) return;
    _loadPositions();
    final request = ++_request;
    setState(() {
      _loading = true;
      _blocker = null;
    });
    try {
      final market = await widget.repository.market(widget.roomId, expiry);
      if (!mounted || request != _request) return;
      _ageTimer?.cancel();
      // Re-evaluate eligibility every second so quotes age out on screen
      // without any user action. This only disables; it never claims freshness.
      _ageTimer = Timer.periodic(const Duration(seconds: 1), (_) {
        if (mounted) setState(() {});
      });
      setState(() {
        _market = market;
        _quotesReceivedAt = widget.clock();
        // A refresh replaces every leg. Clear the old selection and price so an
        // earlier quote can never gate a new order; the user re-selects a leg.
        _selected = null;
        _price.text = '';
        _loading = false;
      });
    } catch (error) {
      if (!mounted || request != _request) return;
      _ageTimer?.cancel();
      setState(() {
        _market = null;
        _quotesReceivedAt = null;
        _selected = null;
        _price.text = '';
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
        404 => 'F&O PAPER data is not available for this room. The feature may be turned off, or the room may not be a PAPER room.',
        401 || 403 => 'Sign in again to view F&O PAPER data.',
        _ => 'F&O data could not be loaded (HTTP ${error.statusCode}).',
      };
    }
    if (error is FormatException) {
      return 'The server response failed validation, so nothing is shown.';
    }
    return 'F&O data is unavailable. Check your connection and try again.';
  }

  /// True only while the quotes were received within the client ceiling.
  bool get _quotesWithinAge {
    final received = _quotesReceivedAt;
    if (received == null) return false;
    final age = widget.clock().difference(received);
    return !age.isNegative && age <= kPaperFnoQuoteMaxAge;
  }

  bool get _canSubmit {
    final leg = _selected;
    final market = _market;
    if (_submitting || market == null || leg == null) return false;
    return _quotesWithinAge && market.hasFreshQuotes &&
        market.displayRightsVerified && leg.quoteFresh && leg.paperEligible &&
        leg.observedAt != null && _sidePrice(leg, _side) != null &&
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

  /// BUY prices at the server ask, SELL-to-close at the server bid. A missing
  /// quote leaves the price empty, which keeps submission disabled.
  static num? _sidePrice(CustomerFnoLeg leg, String side) =>
      side == 'BUY' ? leg.ask : leg.bid;

  void _select(CustomerFnoLeg leg) {
    setState(() {
      _selected = leg;
      _message = null;
      final suggested = _sidePrice(leg, _side);
      _price.text = suggested == null ? '' : suggested.toString();
    });
  }

  void _setSide(String side) {
    setState(() {
      _side = side;
      _message = null;
      final leg = _selected;
      final suggested = leg == null ? null : _sidePrice(leg, side);
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
        // Only a definite 4xx from the server is a rejection. Anything else
        // (5xx, timeout, network, unreadable response) may have executed.
        _message = _isDefiniteRejection(error)
            ? 'Order rejected by the server (HTTP ${(error as ApiHttpFailure).statusCode}). Nothing was filled.'
            : 'The order outcome is unknown. It is not retried. Check positions before acting.';
      });
      await _load();
    }
  }

  static bool _isDefiniteRejection(Object error) =>
      error is ApiHttpFailure &&
      error.statusCode != null &&
      error.statusCode! >= 400 && error.statusCode! < 500;

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
          if (!_loading && _market != null && !_quotesWithinAge) ...[
            const SizedBox(height: 8),
            Text('Quotes are older than ${kPaperFnoQuoteMaxAge.inSeconds}s. Refresh before trading.',
              key: const ValueKey('paper-fno-stale'),
              style: type.bodySmall?.copyWith(color: colors.error)),
            const SizedBox(height: 4),
            FilledButton.tonal(
              key: const ValueKey('paper-fno-refresh'),
              onPressed: _load,
              child: const Text('Refresh quotes')),
          ],
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
    final statusLabel = !market.displayRightsVerified
        ? 'Display rights not verified · orders disabled'
        : switch (market.status) {
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
    final usable = _quotesWithinAge && _market?.hasFreshQuotes == true &&
        _market?.displayRightsVerified == true && leg.quoteFresh && leg.paperEligible;
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
              onSelectionChanged: (s) => _setSide(s.first),
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
    if (_positionsLoading) {
      return Text('Loading virtual F&O positions…', style: type.bodySmall);
    }
    if (_positionsError != null) {
      return Row(children: [
        Expanded(child: Text(_positionsError!, style: type.bodySmall?.copyWith(color: colors.error))),
        TextButton(
          key: const ValueKey('paper-fno-positions-retry'),
          onPressed: _loadPositions,
          child: const Text('Retry positions')),
      ]);
    }
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

import 'dart:async';
import 'dart:math';

import 'package:flutter/material.dart';

import '../../../data/authenticated_api_client.dart';
import '../data/customer_paper_fno_repository.dart';
import 'minttick_fno_quote_board.dart';

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
    this.view = 'trade',
    this.horizon = 'POSITIONAL',
    this.onFilled,
    this.clock = DateTime.now,
    super.key,
  });

  /// Injected for tests; the production default is the device clock.
  final DateTime Function() clock;

  final String roomId;
  /// Trade shows market and ticket; other tabs load only owner-bound history.
  final String view;
  final CustomerPaperFnoRepository repository;
  /// Optional starting expiry. Never inferred: the user enters the verified one.
  final DateTime? initialExpiry;

  /// Room horizon: 'INTRADAY' or 'POSITIONAL'. Intraday rooms auto-select the
  /// nearest *verified listed* expiry (the de-facto intraday contract, still a
  /// real listed date the user can change); positional rooms require an
  /// explicit expiry choice. Both reuse the identical verified contract and
  /// order APIs — the backend exposes no per-order product/segment field, so
  /// this is a selection-workflow difference, not a different order type.
  final String horizon;
  final Future<void> Function()? onFilled;

  bool get intradayHorizon => horizon == 'INTRADAY';

  @override
  State<MintTickPaperFnoPanel> createState() => _MintTickPaperFnoPanelState();
}

class _MintTickPaperFnoPanelState extends State<MintTickPaperFnoPanel>
    with WidgetsBindingObserver {
  CustomerFnoCapability? _capability;
  bool _checkingCapability = true;
  String? _capabilityError;
  int _capabilityRequest = 0;
  CustomerFnoMarket? _market;
  List<CustomerFnoPosition> _positions = const [];
  List<CustomerFnoFill> _orders = const [];
  CustomerFnoReceipt? _receipt;
  String? _receiptError;
  bool _positionsLoading = true;
  String? _positionsError;
  int _positionsRequest = 0;
  String? _blocker;
  bool _loading = true;
  bool _submitting = false;
  String? _message;
  CustomerFnoLeg? _selected;
  num? _dashboardStrike;
  String _side = 'BUY';
  int _lots = 1;
  final _price = TextEditingController();
  DateTime? _expiry;
  // Server-verified listed expiries only. Empty unless the list is VERIFIED.
  List<DateTime> _expiries = const [];
  String? _expiryBlocker;
  bool _expiriesLoading = false;
  DateTime? _quotesReceivedAt;
  Timer? _ageTimer;
  Timer? _pollTimer;
  bool _polling = false;
  int _request = 0;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _expiry = widget.initialExpiry;
    _loadCapability();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _pollTimer?.cancel();
    _ageTimer?.cancel();
    _price.dispose();
    super.dispose();
  }

  /// Queries only the unconditional, owner-scoped capability route. When the
  /// backend disables F&O, never call its conditionally absent market, expiry,
  /// positions, orders, or reconciliation routes. Unknown inventory stays
  /// UNKNOWN and the virtual wallet is not changed.
  Future<void> _loadCapability() async {
    final request = ++_capabilityRequest;
    if (!mounted) return;
    _ageTimer?.cancel();
    _pollTimer?.cancel();
    setState(() {
      _checkingCapability = true;
      _capabilityError = null;
      _capability = null;
      _selected = null;
      _market = null;
      _price.text = '';
    });
    try {
      final capability = await widget.repository.capability(widget.roomId);
      if (!mounted || request != _capabilityRequest) return;
      setState(() {
        _capability = capability;
        _checkingCapability = false;
        _loading = false;
        if (!capability.readOnlyConfigured) {
          _positionsLoading = false;
          _positionsError = 'Virtual F&O positions and orders are not verified.';
          _receipt = null;
          _receiptError = 'Bookkeeping not verified.';
          _orders = const [];
          _positions = const [];
          _expiries = const [];
          _expiry = null;
        }
      });
      // Local virtual order/position and ledger reads require only room
      // ownership, not licensed market quotes or an enabled order engine.
      // Keep viewing old PAPER history possible while trading stays blocked.
      if (capability.readOnlyConfigured && widget.view == 'trade') {
        _loadExpiries();
        if (_expiry != null) {
          _load();
        } else {
          _loadPositions();
        }
      } else {
        _loadPositions();
      }
    } catch (_) {
      if (!mounted || request != _capabilityRequest) return;
      setState(() {
        _checkingCapability = false;
        _capabilityError = 'F&O availability could not be verified.';
        _loading = false;
        _positionsLoading = false;
        _positionsError = 'Virtual F&O positions and orders are not verified.';
        _receipt = null;
        _receiptError = 'Bookkeeping not verified.';
        _orders = const [];
        _positions = const [];
        _expiries = const [];
        _expiry = null;
      });
    }
  }

  static String _capabilityStatusLabel(String status) => switch (status) {
    'FNO_PAPER_DISABLED' =>
      'Real NSE options PAPER orders are disabled. Only authorized real-market quotes may be shown.',
    'FNO_V3_FEED_DISABLED' =>
      'Real Upstox V3 options feed is not active. No verified live prices available.',
    'DISPLAY_RIGHTS_NOT_VERIFIED' =>
      'F&O market display permission is not verified.',
    'PAPER_USE_RIGHTS_NOT_VERIFIED' =>
      'F&O PAPER usage permission is not verified.',
    'ROOM_HORIZON_UNSUPPORTED' =>
      'This room type cannot trade F&O options. Use an F&O Intraday or Positional room.',
    _ => 'F&O data is unavailable.',
  };

  /// Reads the server's listed expiries. Nothing is inferred on the client, and a
  /// blocked or failed list shows its reason and offers no dates.
  Future<void> _loadExpiries() async {
    if (!mounted) return;
    setState(() => _expiriesLoading = true);
    try {
      final listed = await widget.repository.expiries(widget.roomId);
      if (!mounted) return;
      setState(() {
        _expiriesLoading = false;
        _expiries = listed.verified ? listed.expiries : const [];
        _expiryBlocker = listed.verified
            ? null
            : 'Expiry list not verified (${listed.status}). No strikes or orders are available.';
        // A chosen date that is no longer listed is dropped, with its quotes.
        if (_expiry != null && !_expiries.contains(_expiry)) {
          _expiry = null;
          _selected = null;
          _market = null;
          _price.text = '';
        }
      });
      // Intraday rooms open on the nearest verified expiry so the fast
      // workflow needs no manual pick. Positional rooms keep an explicit
      // choice. The date is a real listed one (expiries are sorted ascending),
      // never inferred, and the user can still change it in the dropdown.
      if (widget.intradayHorizon && _expiry == null && _expiries.isNotEmpty) {
        _selectExpiry(_expiries.first);
      }
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _expiriesLoading = false;
        _expiries = const [];
        _expiryBlocker = _describe(error);
        _expiry = null;
        _selected = null;
        _market = null;
        _price.text = '';
      });
    }
  }

  void _selectExpiry(DateTime expiry) {
    _pollTimer?.cancel();
    setState(() {
      _expiry = expiry;
      _dashboardStrike = null;
      _selected = null;
      _message = null;
      _price.text = '';
    });
    _load();
  }

  // A quote rights/source error must not erase existing PAPER positions.
  Future<void> _loadPositions() async {
    final request = ++_positionsRequest;
    if (!mounted) return;
    // Bookkeeping loads independently: its failure never hides positions or orders.
    _loadReceipt();
    setState(() {
      _positionsLoading = true;
      _positionsError = null;
    });
    try {
      // Positions and orders are read together; either failing blocks both.
      final results = await Future.wait<Object>([
        widget.repository.positions(widget.roomId),
        widget.repository.orders(widget.roomId),
      ]);
      if (!mounted || request != _positionsRequest) return;
      setState(() {
        _positions = results[0] as List<CustomerFnoPosition>;
        _orders = results[1] as List<CustomerFnoFill>;
        _positionsLoading = false;
      });
    } catch (_) {
      if (!mounted || request != _positionsRequest) return;
      setState(() {
        _positions = const [];
        _orders = const [];
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
        _dashboardStrike = null;
        _price.text = '';
        _loading = false;
      });
      _schedulePoll();
    } catch (error) {
      if (!mounted || request != _request) return;
      _ageTimer?.cancel();
      _pollTimer?.cancel();
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

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      // Reverify current owner rights after returning from the background.
      if (widget.view == 'trade') _loadCapability();
    } else {
      _pollTimer?.cancel();
    }
  }

  /// Technical 10-second HTTP refresh of the latest *rights-gated* V3 quotes.
  /// This never starts a broker WebSocket, does not place an order, and never
  /// preserves a contract selection if the server no longer lists it.
  void _schedulePoll() {
    _pollTimer?.cancel();
    if (!mounted || widget.view != 'trade' ||
        _capability?.readOnlyConfigured != true ||
        _expiry == null || _market == null ||
        _market!.status == 'MARKET_CLOSED') {
      return;
    }
    _pollTimer = Timer.periodic(const Duration(seconds: 10), (_) {
      if (!_polling && !_submitting && !_loading) _pollMarket();
    });
  }

  Future<void> _pollMarket() async {
    final expiry = _expiry;
    if (_polling || expiry == null || _capability?.readOnlyConfigured != true ||
        !mounted) {
      return;
    }
    _polling = true;
    final request = ++_request;
    try {
      final next = await widget.repository.market(widget.roomId, expiry);
      if (!mounted || request != _request || _expiry != expiry) return;
      final previousKey = _selected?.instrumentKey;
      CustomerFnoLeg? replacement;
      for (final strike in next.strikes) {
        for (final leg in [strike.call, strike.put]) {
          if (leg != null && leg.instrumentKey == previousKey) {
            replacement = leg;
          }
        }
      }
      // Preserve a user-typed LIMIT price only while the same listed contract
      // remains selected; submission still checks current server-side risk.
      setState(() {
        _market = next;
        _quotesReceivedAt = widget.clock();
        _selected = replacement;
        _blocker = null;
        if (previousKey != null && replacement == null) {
          _price.clear();
          _message = 'The selected option is no longer listed. Select again.';
        }
      });
      if (next.status == 'MARKET_CLOSED') _pollTimer?.cancel();
    } catch (error) {
      if (!mounted || request != _request || _expiry != expiry) return;
      // A rights loss, expired login or malformed feed immediately clears
      // every price and ticket. Never keep old V3 quotes labelled as live.
      setState(() {
        _market = null;
        _quotesReceivedAt = null;
        _selected = null;
        _price.clear();
        _blocker = _describe(error);
      });
      if (error is ApiHttpFailure &&
          const {401, 403, 404, 409}.contains(error.statusCode)) {
        _pollTimer?.cancel();
      }
    } finally {
      _polling = false;
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

  /// Whole days from today (local) to the selected verified expiry. Derived
  /// only from the verified listed date; null when no expiry is selected.
  int? _daysToExpiry() {
    final expiry = _expiry;
    if (expiry == null) return null;
    final now = widget.clock();
    final today = DateTime(now.year, now.month, now.day);
    final day = DateTime(expiry.year, expiry.month, expiry.day);
    return day.difference(today).inDays;
  }

  /// Horizon-specific workflow framing. Intraday opens on the nearest expiry
  /// and favours same-session square-off; positional asks the user to choose a
  /// holding expiry. Days-to-expiry is shown from the verified date; nothing
  /// here asserts margin, Greeks or any value the backend does not provide.
  Widget _horizonStrip(ColorScheme colors, TextTheme type) {
    final intraday = widget.intradayHorizon;
    final days = _daysToExpiry();
    final lead = intraday
        ? 'Intraday · nearest expiry · square off same session'
        : 'Positional · choose a holding expiry · overnight holds';
    return Container(
      key: const ValueKey('paper-fno-horizon'),
      padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 6),
      decoration: BoxDecoration(
        color: colors.primary.withValues(alpha: 0.08),
        borderRadius: BorderRadius.circular(6),
        border: Border.all(color: colors.primary.withValues(alpha: 0.22))),
      child: Row(children: [
        Icon(intraday ? Icons.bolt_rounded : Icons.timeline_rounded,
          size: 14, color: colors.primary),
        const SizedBox(width: 6),
        Expanded(child: Text(lead, maxLines: 2, overflow: TextOverflow.ellipsis,
          style: type.labelSmall?.copyWith(
            color: colors.onSurfaceVariant, fontSize: 10))),
        if (days != null) ...[
          const SizedBox(width: 6),
          Text(days <= 0 ? 'Expiry today' : '${days}d to expiry',
            key: const ValueKey('paper-fno-days-to-expiry'),
            style: type.labelSmall?.copyWith(
              color: colors.primary, fontWeight: FontWeight.w800, fontSize: 10)),
        ],
      ]),
    );
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
    // The position/order inventory must be verified before any new virtual
    // order. An unavailable read is not equivalent to an empty portfolio.
    if (_capability?.paperConfigured != true ||
        _positionsLoading || _positionsError != null) {
      return false;
    }
    if (_submitting || market == null || leg == null) return false;
    return _quotesWithinAge && market.hasFreshQuotes &&
        market.displayRightsVerified && leg.quoteFresh && leg.paperEligible &&
        leg.observedAt != null && _sidePrice(leg, _side) != null &&
        _sideAllowed(leg) && _limitPrice() != null;
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

  /// Virtual lots currently held in this contract, from server positions only.
  int _heldLots(CustomerFnoLeg leg) {
    var contracts = 0;
    for (final p in _positions) {
      if (p.instrumentKey == leg.instrumentKey) contracts += p.quantity;
    }
    return leg.lotSize <= 0 ? 0 : contracts ~/ leg.lotSize;
  }

  /// SELL only closes held long lots. BUY is bounded by the server's own checks.
  bool _sideAllowed(CustomerFnoLeg leg) =>
      _side == 'BUY' || _heldLots(leg) >= _lots;

  int _maxLotsFor(CustomerFnoLeg leg) =>
      _side == 'SELL' ? _heldLots(leg).clamp(0, 20) : 20;

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
      // Never leave more lots selected than the side allows.
      if (leg != null) {
        final max = _maxLotsFor(leg);
        _lots = max < 1 ? 1 : _lots.clamp(1, max);
      }
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
    if (widget.view == 'positions' || widget.view == 'orders') {
      return Container(
        key: const ValueKey('paper-fno-panel'),
        padding: const EdgeInsets.all(14),
        decoration: BoxDecoration(
          color: colors.surfaceContainerLow,
          border: Border.all(color: colors.outlineVariant),
          borderRadius: BorderRadius.circular(12)),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Row(children: [
            Expanded(child: Text(
              widget.view == 'positions' ? 'F&O Positions' : 'F&O Orders',
              style: type.titleMedium?.copyWith(fontWeight: FontWeight.w800))),
            IconButton(onPressed: _loadPositions,
              tooltip: 'Refresh F&O history',
              icon: const Icon(Icons.refresh_rounded)),
          ]),
          const SizedBox(height: 12),
          if (widget.view == 'positions') _positionsView(colors, type)
          else _ordersView(colors, type),
          const SizedBox(height: 12),
          _bookkeepingView(colors, type),
        ]),
      );
    }
    if (_checkingCapability || _capability?.readOnlyConfigured != true) {
      return Container(
        key: const ValueKey('paper-fno-panel'),
        padding: const EdgeInsets.all(14),
        decoration: BoxDecoration(
          color: colors.surfaceContainerLow,
          border: Border.all(color: colors.outlineVariant),
          borderRadius: BorderRadius.circular(8),
        ),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text('NIFTY options · PAPER', style: type.titleSmall),
          const SizedBox(height: 8),
          if (_checkingCapability)
            const Text('Checking F&O availability…')
          else
            Text(_capabilityError ??
                _capabilityStatusLabel(_capability?.status ?? ''),
              key: const ValueKey('paper-fno-capability-blocked'),
              style: type.bodyMedium?.copyWith(color: colors.onSurfaceVariant)),
          if (!_checkingCapability) ...[
            const SizedBox(height: 6),
            if (_positionsLoading)
              Text('Loading saved virtual F&O history…',
                style: type.bodySmall?.copyWith(color: colors.onSurfaceVariant))
            else if (_positionsError != null)
              Text('Saved positions and orders could not be verified.',
                style: type.bodySmall?.copyWith(color: colors.error))
            else ...[
              Text('Saved PAPER: ${_positions.length} open positions · '
                  '${_orders.length} recent orders',
                key: const ValueKey('paper-fno-historical-counts'),
                style: type.bodySmall?.copyWith(color: colors.onSurfaceVariant)),
              const SizedBox(height: 4),
              Text('Bookkeeping: ${_receipt?.status ?? 'UNVERIFIED'}',
                key: const ValueKey('paper-fno-historical-reconciliation'),
                style: type.bodySmall?.copyWith(color: colors.onSurfaceVariant)),
            ],
            const SizedBox(height: 4),
            Text('No simulated or broker order can be placed until real Upstox quotes and required permissions are verified.',
              style: type.bodySmall?.copyWith(color: colors.onSurfaceVariant)),
            const SizedBox(height: 7),
            Text('Real NSE contracts only · No synthetic option prices',
              key: const ValueKey('paper-fno-real-only'),
              style: type.labelSmall?.copyWith(color: colors.primary)),
            const SizedBox(height: 9),
            TextButton.icon(
              key: const ValueKey('paper-fno-capability-retry'),
              onPressed: _loadCapability,
              icon: const Icon(Icons.refresh_rounded, size: 18),
              label: const Text('Recheck F&O'),
            ),
            const SizedBox(height: 12),
            const MintTickFnoUnavailableOverview(),
          ],
        ]),
      );
    }
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
            Expanded(child: Text('Options desk',
              style: type.titleSmall?.copyWith(fontWeight: FontWeight.w700))),
            Text('PAPER · NO LIVE ORDERS',
              style: type.labelSmall?.copyWith(color: colors.onSurfaceVariant)),
          ]),
          const SizedBox(height: 4),
          _horizonStrip(colors, type),
          if (_capability?.paperConfigured == false) ...[
            const SizedBox(height: 6),
            Text(_capabilityStatusLabel(_capability!.status),
              key: const ValueKey('paper-fno-order-rights-blocked'),
              style: type.bodySmall?.copyWith(color: colors.onSurfaceVariant)),
          ],
          const SizedBox(height: 4),
          if (_expiryBlocker != null)
            Text(_expiryBlocker!, key: const ValueKey('paper-fno-expiry-blocked'),
              style: TextStyle(color: colors.error))
          else
            DropdownButton<DateTime>(
              key: const ValueKey('paper-fno-expiry-dropdown'),
              isExpanded: true,
              isDense: true,
              menuMaxHeight: 250,
              itemHeight: 48,
              hint: Text(_expiriesLoading
                  ? 'Loading verified expiries…' : 'Select a verified expiry'),
              value: _expiries.contains(_expiry) ? _expiry : null,
              items: [
                for (final d in _expiries)
                  DropdownMenuItem<DateTime>(value: d, child: Text(_displayExpiry(d))),
              ],
              onChanged: _expiriesLoading || _expiries.isEmpty
                  ? null
                  : (d) {
                      if (d != null) _selectExpiry(d);
                    },
            ),
          const SizedBox(height: 10),
          if (_expiry == null && _expiryBlocker == null)
            Text('Select a verified expiry to see strikes.', style: type.bodySmall),
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
          // Do not repeat empty Orders and Positions tabs on the main desk.
          if (!_positionsLoading && _positions.isNotEmpty) ...[
            const SizedBox(height: 10),
            _positionsView(colors, type),
          ],
          if (!_positionsLoading && _positionsError != null) ...[
            const SizedBox(height: 10),
            _positionsView(colors, type),
            // An unverified inventory must never look like zero orders.
            const SizedBox(height: 6),
            _ordersView(colors, type),
          ],
          if (!_positionsLoading && _positionsError == null &&
              _orders.isNotEmpty) ...[
            const SizedBox(height: 10),
            _ordersView(colors, type),
          ],
          const SizedBox(height: 8),
          // Give the ListTile its own ink surface inside the decorated panel.
          // A bare ExpansionTile here triggers Material's hidden-ink assertion.
          Material(
            type: MaterialType.transparency,
            child: ExpansionTile(
              key: const ValueKey('paper-fno-readiness-details'),
              tilePadding: EdgeInsets.zero,
              childrenPadding: EdgeInsets.zero,
              title: Text('Trading & ledger checks', style: type.titleSmall),
              subtitle: Text(
                _receipt == null ? 'Verification unavailable'
                  : _receipt!.tradingApproved ? 'Server approved'
                  : 'Trading not approved',
                style: type.labelSmall?.copyWith(color: colors.onSurfaceVariant)),
              children: [_bookkeepingView(colors, type)],
            ),
          ),
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
        Text('Snapshot · ${_displayTimestamp(market.snapshotAt)} local',
          style: type.labelSmall?.copyWith(color: colors.onSurfaceVariant)),
      const SizedBox(height: 8),
      MintTickFnoQuoteBoard(
        key: const ValueKey('fno-premium-quote-board'),
        market: market,
        fresh: _quotesWithinAge,
        strike: _dashboardStrike,
        selected: _selected,
        canClose: _selected != null && _heldLots(_selected!) > 0,
        onStrike: (value) => setState(() {
          _dashboardStrike = value;
          _selected = null;
          _price.clear();
          _message = null;
        }),
        onLeg: _select,
        // Selection is separate from submitting a virtual order.
        onTicket: (leg, side) {
          _select(leg);
          _setSide(side);
        },
      ),
      const SizedBox(height: 10),
      _ticket(colors, type),
    ];
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
              segments: [
                ButtonSegment(value: 'BUY', label: Text('BUY')),
                ButtonSegment(value: 'SELL', label: Text('SELL to close'),
                  enabled: _heldLots(leg) > 0),
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
              onPressed: _selected != null && _lots < _maxLotsFor(_selected!)
                  ? () => setState(() => _lots++) : null,
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
          if (_heldLots(leg) == 0)
            Text('No virtual position in this contract to close.',
              key: const ValueKey('paper-fno-no-position'),
              style: type.labelSmall?.copyWith(color: colors.onSurfaceVariant)),
          if (_side == 'SELL' && _heldLots(leg) > 0 && _lots > _heldLots(leg))
            Text('You hold ${_heldLots(leg)} lot(s) of this contract.',
              style: type.labelSmall?.copyWith(color: colors.error)),
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
          Container(
            key: ValueKey('fno-position-${p.instrumentKey}'),
            margin: const EdgeInsets.only(bottom: 7),
            padding: const EdgeInsets.all(10),
            decoration: BoxDecoration(
              color: colors.surfaceContainerLow,
              border: Border.all(color: colors.outlineVariant),
              borderRadius: BorderRadius.circular(12)),
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text('${p.optionType} ${_ymd(p.expiry)} · ${p.quantity} qty',
                style: type.titleSmall?.copyWith(fontWeight: FontWeight.w700)),
              Text('Cost basis ${_rupees(p.costBasisPaise)}',
                style: type.bodySmall?.copyWith(color: colors.onSurfaceVariant)),
              if (p.indicativeUnrealizedPnlPaise == null || !p.priceFresh)
                Text('P&L not valued · return % unavailable',
                  key: ValueKey('fno-return-unverified-${p.instrumentKey}'),
                  style: type.bodySmall?.copyWith(color: colors.onSurfaceVariant))
              else
                Row(children: [
                  Expanded(child: Text(
                    'Indicative P&L ${_rupees(p.indicativeUnrealizedPnlPaise!)}',
                    style: type.bodySmall?.copyWith(
                      color: p.indicativeUnrealizedPnlPaise! >= 0
                        ? colors.primary : colors.error))),
                  Text(p.costBasisPaise <= 0 ? '—' :
                    '${p.indicativeUnrealizedPnlPaise! >= 0 ? '+' : ''}${(p.indicativeUnrealizedPnlPaise! * 100 / p.costBasisPaise).toStringAsFixed(2)}%',
                    key: ValueKey('fno-return-percent-${p.instrumentKey}'),
                    style: type.titleSmall?.copyWith(fontWeight: FontWeight.w800,
                      color: p.indicativeUnrealizedPnlPaise! >= 0
                        ? colors.primary : colors.error)),
                ]),
            ]),
          ),
      ],
    );
  }

  Future<void> _loadReceipt() async {
    try {
      final receipt = await widget.repository.reconciliation(widget.roomId);
      if (!mounted) return;
      setState(() {
        _receipt = receipt;
        _receiptError = null;
      });
    } catch (_) {
      if (!mounted) return;
      setState(() {
        _receipt = null;
        _receiptError = 'Bookkeeping check unavailable. Trading readiness is unknown.';
      });
    }
  }

  /// Bookkeeping consistency is shown apart from trading readiness. A reconciled
  /// ledger never approves trading by itself.
  Widget _bookkeepingView(ColorScheme colors, TextTheme type) {
    final r = _receipt;
    if (r == null) {
      return Text(_receiptError ?? 'Checking bookkeeping…',
        key: const ValueKey('paper-fno-receipt-unknown'), style: type.bodySmall);
    }
    return Column(
      key: const ValueKey('paper-fno-receipt'),
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('Bookkeeping', style: type.labelLarge),
        Text(
          'Ledger ${r.walletReconciled ? 'reconciled' : 'NOT reconciled'} · '
          'fills ${r.recordedFills} · open contracts ${r.openContracts} · '
          'realized ${_rupees(r.recordedRealizedPnlPaise)} '
          '(computed ${_rupees(r.computedRealizedPnlPaise)})',
          style: type.bodySmall),
        const SizedBox(height: 4),
        Text('Trading readiness', style: type.labelLarge),
        Text(
          'Approved for trading: ${r.tradingApproved ? 'yes' : 'no'} · '
          'market data ${r.marketDataVerified ? 'verified' : 'not verified'} · '
          'provider rights ${r.providerRightsVerified ? 'verified' : 'not verified'}',
          style: type.bodySmall),
        if (r.blockerCodes.isNotEmpty)
          Text('Blockers: ${r.blockerCodes.join(', ')}',
            style: type.labelSmall?.copyWith(color: colors.error)),
        Text('Bookkeeping consistency does not approve trading. Checked '
            '${_displayTimestamp(r.checkedAt)} local.',
          style: type.labelSmall?.copyWith(color: colors.onSurfaceVariant)),
      ],
    );
  }

  Widget _ordersView(ColorScheme colors, TextTheme type) {
    if (_positionsLoading) return const SizedBox.shrink();
    if (_positionsError != null) {
      return Text(
        'Virtual F&O orders could not be verified. Retry positions before acting.',
        key: const ValueKey('paper-fno-orders-unavailable'),
        style: type.bodySmall?.copyWith(color: colors.error),
      );
    }
    if (_orders.isEmpty) {
      return Text('No virtual F&O orders.', style: type.bodySmall);
    }
    return Column(
      key: const ValueKey('paper-fno-orders'),
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('Virtual F&O orders', style: type.labelLarge),
        for (final f in _orders.take(10))
          Padding(
            padding: const EdgeInsets.only(top: 4),
            child: Text(
              '${f.side} ${f.quantity ~/ f.lotSize} lot(s) · ${f.optionType} '
              '${_ymd(f.expiry)} · fill ${f.fillPrice} · '
              'cash after ${_rupees(f.cashAfterPaise)}'
              '${f.side == 'SELL' ? ' · realized ${_rupees(f.realizedPnlDeltaPaise)}' : ''}',
              style: type.bodySmall),
          ),
      ],
    );
  }

  static String _displayTimestamp(DateTime? instant) {
    if (instant == null) return 'unknown';
    final d = instant.toLocal();
    return '${_ymd(d)} ${d.hour.toString().padLeft(2, '0')}:${d.minute.toString().padLeft(2, '0')}';
  }

  static String _displayExpiry(DateTime d) {
    const weekdays = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
    const months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun',
      'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
    return '${weekdays[d.weekday - 1]}, ${d.day} ${months[d.month - 1]} ${d.year}';
  }

  static String _ymd(DateTime d) =>
      '${d.year.toString().padLeft(4, '0')}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';

  static String _rupees(int paise) {
    final sign = paise < 0 ? '-' : '';
    final abs = paise.abs();
    return '$sign₹${abs ~/ 100}.${(abs % 100).toString().padLeft(2, '0')}';
  }
}

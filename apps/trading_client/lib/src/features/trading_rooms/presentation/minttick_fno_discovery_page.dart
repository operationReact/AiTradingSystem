import 'dart:async';

import 'package:flutter/material.dart';

import '../data/customer_paper_fno_repository.dart';
import '../data/customer_fno_discovery_repository.dart';
import '../domain/trading_room_draft.dart';
import 'minttick_customer_widgets.dart';

/// A market-discovery surface, not an order gateway. Quotes come solely from
/// customer-authorized, owner-scoped NSE option endpoints. Empty/unverified
/// segments never display synthetic prices or recommendations.
class MintTickFnoDiscoveryPage extends StatefulWidget {
  const MintTickFnoDiscoveryPage({
    required this.discovery,
    this.rooms,
    this.onOpenRoom,
    this.onCreateRoom,
    super.key,
  });

  final TradingRoomDraftStore? rooms;
  final CustomerFnoDiscoveryRepository discovery;
  final ValueChanged<TradingRoomDraft>? onOpenRoom;
  final VoidCallback? onCreateRoom;

  @override
  State<MintTickFnoDiscoveryPage> createState() => _MintTickFnoDiscoveryPageState();
}

/// F&O data facts are derived exclusively from a source market observation.
class MintTickFnoDiscoveryFacts {
  static List<CustomerFnoLeg> optionLegs(
      CustomerFnoMarket market, String side) {
    if (side != 'ALL' && side != 'CE' && side != 'PE') {
      return const [];
    }
    return List.unmodifiable([
      for (final strike in market.strikes) ...[
        if ((side == 'ALL' || side == 'CE') && strike.call != null)
          strike.call!,
        if ((side == 'ALL' || side == 'PE') && strike.put != null)
          strike.put!,
      ],
    ]);
  }

  static bool recentTick(CustomerFnoLeg leg, DateTime now) {
    final exchange = leg.exchangeAt?.toUtc();
    final observed = leg.observedAt?.toUtc();
    final current = now.toUtc();
    if (!leg.quoteFresh || exchange == null || observed == null ||
        exchange.isAfter(current.add(const Duration(seconds: 2))) ||
        observed.isAfter(current.add(const Duration(seconds: 2)))) {
      return false;
    }
    final exchangeAge = current.difference(exchange);
    final observedAge = current.difference(observed);
    return !exchangeAge.isNegative && !observedAge.isNegative &&
        exchangeAge <= const Duration(seconds: 15) &&
        observedAge <= const Duration(seconds: 15);
  }

  /// Never label stale or missing exchange observations as a live quote.
  static List<CustomerFnoLeg> freshLegs(
      CustomerFnoMarket market, DateTime now) {
    if (!market.hasFreshQuotes || !market.displayRightsVerified) {
      return const [];
    }
    return List.unmodifiable(optionLegs(market, 'ALL')
        .where((leg) => recentTick(leg, now)));
  }

  /// Previous-close changes are not an intraday momentum/volume ranking.
  static List<CustomerFnoLeg> previousCloseMovers(CustomerFnoMarket market) {
    final all = [
      ...optionLegs(market, 'CE'),
      ...optionLegs(market, 'PE'),
    ].where((leg) => market.hasFreshQuotes &&
        market.displayRightsVerified &&
        leg.exchangeAt != null && leg.observedAt != null &&
        leg.priceChangePct != null).toList();
    all.sort((a, b) =>
        b.priceChangePct!.abs().compareTo(a.priceChangePct!.abs()));
    return List.unmodifiable(all);
  }
}

class _MintTickFnoDiscoveryPageState extends State<MintTickFnoDiscoveryPage>
    with WidgetsBindingObserver {
  String? _roomId;
  String _section = 'Explore';
  String _side = 'ALL';
  bool _loading = false;
  String? _error;
  int _generation = 0;
  List<DateTime> _expiries = const [];
  DateTime? _expiry;
  CustomerFnoMarket? _market;
  bool _readOnlyConfigured = false;
  String? _rightsStatus;
  Timer? _quoteTimer;
  bool _appActive = true;
  bool _polling = false;
  int _tickCount = 0;
  CustomerFnoDiscoveryCapability? _source;

  List<TradingRoomDraft> get _availableRooms => widget.rooms?.drafts
      .where((r) => (r.marketScope == 'FNO' || r.marketScope == 'ALL') &&
          (r.mode == 'PAPER' || r.mode == 'LIVE')).toList() ?? const [];

  TradingRoomDraft? get _room {
    for (final room in _availableRooms) {
      if (room.id == _roomId) {
        return room;
      }
    }
    return null;
  }

  @override
  void initState() {
    super.initState();
    widget.rooms?.addListener(_onRoomsChanged);
    WidgetsBinding.instance.addObserver(this);
    _quoteTimer = Timer.periodic(const Duration(seconds: 5), (_) {
      if (!_appActive || !mounted || _loading || _polling) return;
      ++_tickCount;
      // Refresh capability/connection metrics every 30 seconds too;
      // a previously connected socket must never remain labelled active.
      if (_tickCount % 6 == 0) {
        unawaited(_refresh());
      } else if (_readOnlyConfigured && _expiry != null) {
        unawaited(_loadMarket(_expiry!, quiet: true));
      }
    });
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) {
        return;
      }
      unawaited(_refresh());
      final rooms = widget.rooms;
      if (rooms != null) {
        if (!rooms.loaded) {
          unawaited(rooms.load());
        } else {
          _onRoomsChanged();
        }
      }
    });
  }

  @override
  void dispose() {
    _generation++;
    _quoteTimer?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    widget.rooms?.removeListener(_onRoomsChanged);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    _appActive = state == AppLifecycleState.resumed;
    if (_appActive && !_loading && !_polling) {
      unawaited(_refresh());
    }
  }

  void _onRoomsChanged() {
    if (!mounted) {
      return;
    }
    final rooms = _availableRooms;
    final known = rooms.any((r) => r.id == _roomId);
    if (known || (rooms.isEmpty && _roomId == null)) {
      return;
    }
    final preferred = rooms.where((r) => r.mode == 'LIVE' &&
        r.marketScope == 'FNO').toList();
    _selectRoom(preferred.isNotEmpty ? preferred.first.id :
        rooms.isNotEmpty ? rooms.first.id : null);
  }

  void _selectRoom(String? roomId) {
    setState(() => _roomId = roomId);
  }

  Future<void> _refresh() async {
    final request = ++_generation;
    setState(() {
      _loading = true;
      _error = null;
      _market = null;
      _expiry = null;
      _expiries = const [];
      _readOnlyConfigured = false;
      _source = null;
    });
    try {
      final capability = await widget.discovery.capability();
      if (!mounted || request != _generation) {
        return;
      }
      final readEnabled = capability.displayRightsVerified &&
          capability.feedConfigured && capability.sourceReady;
      final discoveryEnabled = (capability.displayRightsVerified ||
              capability.contractDiscoveryVerified) &&
          capability.sourceReady;
      setState(() {
        _source = capability;
        _rightsStatus = capability.status;
        _readOnlyConfigured = readEnabled;
      });
      if (!discoveryEnabled) {
        return;
      }
      final listed = await widget.discovery.expiries();
      if (!mounted || request != _generation) {
        return;
      }
      if (!listed.verified) {
        setState(() => _rightsStatus = listed.status);
        return;
      }
      final dates = listed.expiries;
      if (dates.isEmpty) {
        setState(() => _rightsStatus = 'NO_CURRENT_LISTED_EXPIRIES');
        return;
      }
      setState(() {
        _expiries = dates;
        _expiry = dates.first;
      });
      // Discovery-only sessions show listed expiries without requesting prices.
      if (_readOnlyConfigured) {
        await _loadMarket(dates.first, expected: request);
      }
    } catch (_) {
      if (mounted && request == _generation) {
        setState(() => _error =
            'Authenticated NSE option discovery is unavailable.');
      }
    } finally {
      if (mounted && request == _generation) {
        setState(() => _loading = false);
      }
    }
  }

  Future<void> _loadMarket(DateTime expiry, {int? expected, bool quiet = false}) async {
    if (!_expiries.contains(expiry)) {
      return;
    }
    final request = expected ?? ++_generation;
    if (expected == null) {
      setState(() {
        if (quiet) {
          _polling = true;
        } else {
          _loading = true;
          _market = null;
        }
        _error = null;
        _expiry = expiry;
      });
    }
    try {
      final next = await widget.discovery.market(expiry);
      if (!mounted || request != _generation) {
        return;
      }
      if (!next.displayRightsVerified || next.liveOrderEnabled ||
          next.underlyingKey != 'NSE_INDEX|Nifty 50' ||
          next.expiry != expiry) {
        throw const FormatException('Unverified NSE option data');
      }
      setState(() => _market = next);
    } catch (_) {
      if (mounted && request == _generation) {
        setState(() {
          _market = null; // Never retain a stale LIVE label on read failure.
          _error = 'Unable to verify listed options or exchange-timed quotes.';
        });
      }
    } finally {
      if (mounted && request == _generation) {
        setState(() {
          if (quiet) {
            _polling = false;
          } else {
            _loading = false;
          }
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final c = theme.colorScheme;
    final room = _room;
    final market = _market;
    final fresh = market != null && market.hasFreshQuotes &&
        market.displayRightsVerified &&
        market.strikes.any((strike) =>
          (strike.call != null && MintTickFnoDiscoveryFacts.recentTick(
            strike.call!, DateTime.now().toUtc())) ||
          (strike.put != null && MintTickFnoDiscoveryFacts.recentTick(
            strike.put!, DateTime.now().toUtc())));
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      const SizedBox(height: 7),
      Row(children: [
        const Expanded(child: Text('F&O · Discover',
          style: TextStyle(fontSize: 22, fontWeight: FontWeight.w800))),
        _statusPill(context, fresh ? 'FRESH V3' :
            market?.status == 'MARKET_CLOSED' ? 'MARKET CLOSED' : 'NOT LIVE',
            fresh),
        IconButton(
          key: const ValueKey('fno-discovery-refresh'),
          tooltip: 'Refresh F&O source',
          onPressed: _loading ? null : _refresh,
          icon: const Icon(Icons.refresh_rounded),
        ),
      ]),
      Text('Real NSE contracts · no indicative or synthetic prices',
        style: theme.textTheme.bodySmall?.copyWith(color: c.onSurfaceVariant)),
      const SizedBox(height: 5),
      Text('Stream: ${_source?.streamState ?? 'UNVERIFIED'} · '
          'fresh contracts: ${_source?.freshExchangeTimedQuotes ?? 0} · '
          'last exchange tick: ${_time(_source?.latestFreshExchangeTradeAt)}',
        style: theme.textTheme.labelSmall?.copyWith(color: c.onSurfaceVariant)),
      const SizedBox(height: 6),
      Text('Listed contracts: ${market == null ? 'unavailable' : market.strikes.length * 2} · '
          'Exchange-fresh: ${market == null ? 0 : MintTickFnoDiscoveryFacts.freshLegs(
            market, DateTime.now().toUtc()).length} · '
          'Display authorization: ${_source?.displayRightsVerified == true ? 'verified' : 'not verified'}',
        style: theme.textTheme.labelSmall?.copyWith(color: c.onSurfaceVariant)),
      const SizedBox(height: 16),
      _roomPicker(context, room),
      const SizedBox(height: 14),
      _navigation(context),
      const SizedBox(height: 13),
      if (_loading) const LinearProgressIndicator(minHeight: 2),
      if (_error != null) Padding(
        padding: const EdgeInsets.only(top: 7, bottom: 9),
        child: Text(_error!, style: TextStyle(color: c.error))),
      if (!_readOnlyConfigured || market == null) _unavailable(context,
          'NIFTY options data',
          'Market discovery uses the independent Upstox source. '
          '${_rightsStatus == 'CONTRACTS_LISTED_PRICES_NOT_AUTHORIZED'
              ? 'Expiries and contract identities are listed from the verified source. '
                'Live prices, OI and Greeks are not authorized for display.'
              : '${(_rightsStatus ?? 'Data not yet verified').replaceAll('_', ' ')}.'}',
          action: room == null || widget.onOpenRoom == null ? null : 'Open terminal',
          onTap: room == null ? null : () => widget.onOpenRoom?.call(room)),
      if (_section == 'Explore') ...[
        _marketIntro(context, room, market),
        _expirySection(context),
        _watchlist(context, market, compact: true),
        _movers(context, market),
        _unavailable(context, 'Futures chain',
          'Futures prices and volumes require a verified NSE F&O futures feed. '
          'Only currently supported, source-listed NIFTY options are shown above.'),
        _unavailable(context, 'Event-driven stock options',
          'No verified corporate-event-to-options feed is connected. '
          'Events and dividend prices will not be estimated.'),
        _unavailable(context, 'Research recommendations',
          'Only timestamped and independently validated strategy outcomes '
          'can appear here. No placeholder targets or returns.'),
      ] else if (_section == 'Chain') ...[
        _expirySection(context),
        _watchlist(context, market, compact: false),
      ] else if (_section == 'Analytics') ...[
        _analytics(context, market),
      ] else ...[
        _trade(context, room),
      ],
      const SizedBox(height: 20),
    ]);
  }

  Widget _roomPicker(BuildContext context, TradingRoomDraft? room) {
    final type = Theme.of(context).textTheme;
    return MintTickSurface(
      padding: const EdgeInsets.all(12),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text('TRADING TERMINAL · OPTIONAL', style: type.labelSmall),
        const SizedBox(height: 7),
        if (_availableRooms.isEmpty)
          Row(children: [
            const Expanded(child: Text('Market discovery works without a room.')),
            TextButton(onPressed: widget.onCreateRoom,
              child: const Text('Create F&O room')),
          ])
        else
          DropdownButtonHideUnderline(child: DropdownButton<String>(
            key: const ValueKey('fno-discovery-room'),
            value: room?.id,
            isExpanded: true,
            items: [for (final r in _availableRooms)
              DropdownMenuItem(value: r.id,
                child: Text('${r.name} · ${r.mode} · ${r.marketScope}',
                  maxLines: 1, overflow: TextOverflow.ellipsis))],
            onChanged: _loading ? null : _selectRoom,
          )),
        if (room != null)
          Text('Open your ${room.mode} terminal for order entry. '
              'This market feed does not require a broker connection.',
            style: type.bodySmall),
      ]),
    );
  }

  Widget _navigation(BuildContext context) {
    final c = Theme.of(context).colorScheme;
    return SizedBox(height: 43,
      child: Row(children: [
        for (final name in const ['Explore', 'Chain', 'Analytics', 'Trade'])
          Expanded(child: InkWell(
            key: ValueKey('fno-discovery-tab-${name.toLowerCase()}'),
            onTap: () => setState(() => _section = name),
            child: Container(
              alignment: Alignment.center,
              decoration: BoxDecoration(border: Border(bottom: BorderSide(
                color: _section == name ? c.primary : c.outlineVariant,
                width: _section == name ? 2.5 : 1))),
              child: Text(name, maxLines: 1,
                style: TextStyle(fontSize: 12,
                  color: _section == name ? c.onSurface : c.onSurfaceVariant,
                  fontWeight: _section == name ? FontWeight.w800 : FontWeight.w500)),
            ),
          )),
      ]),
    );
  }

  Widget _marketIntro(BuildContext context, TradingRoomDraft? room,
      CustomerFnoMarket? market) {
    final c = Theme.of(context).colorScheme;
    return Padding(padding: const EdgeInsets.only(bottom: 14),
      child: MintTickSurface(child: Column(
        crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          Icon(Icons.show_chart_rounded, color: c.primary),
          const SizedBox(width: 8),
          const Expanded(child: Text('NIFTY 50 · NSE options',
            style: TextStyle(fontSize: 17, fontWeight: FontWeight.w800))),
          Text(market == null ? '—' : market.status.replaceAll('_', ' '),
            style: const TextStyle(fontSize: 10)),
        ]),
        const SizedBox(height: 8),
        Text(market?.snapshotAt == null
            ? 'No verified contract metadata retrieval timestamp'
            : 'Contract metadata retrieved: ${_time(market!.snapshotAt)} · '
              'exchange-timed quotes validated separately',
          style: Theme.of(context).textTheme.bodySmall),
        if (room != null && widget.onOpenRoom != null) Align(alignment: Alignment.centerRight,
          child: TextButton.icon(
            onPressed: () => widget.onOpenRoom?.call(room),
            icon: const Icon(Icons.open_in_new, size: 15),
            label: const Text('Open trading terminal'),
          )),
      ])),
    );
  }

  Widget _expirySection(BuildContext context) => Padding(
    padding: const EdgeInsets.only(bottom: 14),
    child: MintTickSurface(child: Column(
      crossAxisAlignment: CrossAxisAlignment.start, children: [
        _heading('Upcoming NIFTY expiries', 'Exchange-listed only'),
        const SizedBox(height: 9),
        if (_expiries.isEmpty)
          const Text('No verified upcoming expiry calendar.')
        else Wrap(spacing: 7, runSpacing: 7, children: [
          for (final date in _expiries)
            ChoiceChip(
              key: ValueKey('fno-discovery-expiry-${_ymd(date)}'),
              label: Text(_ymd(date)),
              selected: _expiry == date,
              onSelected: _loading ? null : (_) => _loadMarket(date),
            ),
        ]),
      ]),
    ));

  Widget _watchlist(BuildContext context, CustomerFnoMarket? market,
      {required bool compact}) {
    final c = Theme.of(context).colorScheme;
    final type = Theme.of(context).textTheme;
    final now = DateTime.now().toUtc();
    final list = market == null ? <CustomerFnoLeg>[] :
        MintTickFnoDiscoveryFacts.optionLegs(market, _side);
    // Strikes are listed ascending, so unquoted far-from-ATM rows would
    // otherwise fill the compact watchlist. Verified ticks come first.
    final ordered = [
      ...list.where((leg) => MintTickFnoDiscoveryFacts.recentTick(leg, now)),
      ...list.where((leg) => !MintTickFnoDiscoveryFacts.recentTick(leg, now)),
    ];
    final visible = compact ? ordered.take(6).toList() : ordered;
    return Padding(padding: const EdgeInsets.only(bottom: 14),
      child: MintTickSurface(child: Column(
        crossAxisAlignment: CrossAxisAlignment.start, children: [
          _heading(compact ? 'Options watchlist' : 'NIFTY option chain',
              'Source-listed · ${list.length} ${_side == 'ALL' ? 'contracts' : _side == 'CE' ? 'calls' : 'puts'}'),
          const SizedBox(height: 9),
          Wrap(spacing: 8, children: [
            ChoiceChip(
              key: const ValueKey('fno-discovery-all'),
              selected: _side == 'ALL', label: const Text('All CE + PE'),
              onSelected: (_) => setState(() => _side = 'ALL')),
            ChoiceChip(
              key: const ValueKey('fno-discovery-call'),
              selected: _side == 'CE', label: const Text('Call'),
              onSelected: (_) => setState(() => _side = 'CE')),
            ChoiceChip(
              key: const ValueKey('fno-discovery-put'),
              selected: _side == 'PE', label: const Text('Put'),
              onSelected: (_) => setState(() => _side = 'PE')),
          ]),
          if (list.isEmpty) const Padding(
            padding: EdgeInsets.symmetric(vertical: 14),
            child: Text('No verified option contracts available.')),
          for (final leg in visible) ...[
            Divider(color: c.outlineVariant),
            _optionRow(context, leg, details: !compact),
          ],
          if (compact && list.length > visible.length)
            Align(alignment: Alignment.centerRight,
              child: TextButton(
                onPressed: () => setState(() => _section = 'Chain'),
                child: const Text('View full chain'),
              )),
          if (market?.status == 'MARKET_CLOSED')
            Text('Market closed · no stale contract is labeled LIVE.',
              style: type.labelSmall?.copyWith(color: c.onSurfaceVariant)),
        ]),
      ));
  }

  Widget _optionRow(BuildContext context, CustomerFnoLeg leg,
      {bool details = false}) {
    final c = Theme.of(context).colorScheme;
    final type = Theme.of(context).textTheme;
    final verified = _market?.hasFreshQuotes == true &&
        _market?.displayRightsVerified == true &&
        MintTickFnoDiscoveryFacts.recentTick(leg, DateTime.now().toUtc());
    final pct = verified ? leg.priceChangePct : null;
    final price = verified ? leg.ltp : null;
    return Padding(padding: const EdgeInsets.symmetric(vertical: 7),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          Container(width: 38, height: 38,
            alignment: Alignment.center,
            decoration: BoxDecoration(color: c.surfaceContainerHighest,
                borderRadius: BorderRadius.circular(12)),
            child: Text(leg.side, style: TextStyle(color: c.primary,
                fontWeight: FontWeight.w800, fontSize: 12))),
          const SizedBox(width: 10),
          Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(leg.tradingSymbol, maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: type.bodyMedium?.copyWith(fontWeight: FontWeight.w700)),
              Text('Bid ${_price(verified ? leg.bid : null)}  ·  '
                  'Ask ${_price(verified ? leg.ask : null)}',
                style: type.labelSmall?.copyWith(color: c.onSurfaceVariant)),
              Text(verified
                  ? 'Exchange ${_time(leg.exchangeAt)} · V3 verified'
                  : 'No fresh exchange-timed quote',
                style: type.labelSmall?.copyWith(color: c.onSurfaceVariant)),
            ])),
          Column(crossAxisAlignment: CrossAxisAlignment.end, children: [
            Text(_price(price), style: type.titleSmall?.copyWith(
              fontWeight: FontWeight.w800,
              color: pct == null ? c.onSurface :
                  pct >= 0 ? c.primary : c.error)),
            Text(pct == null ? 'Change unavailable'
                  : '${pct >= 0 ? '+' : ''}${pct.toStringAsFixed(2)}%',
              style: type.labelSmall?.copyWith(color: c.onSurfaceVariant)),
          ]),
        ]),
        if (details) ...[
          const SizedBox(height: 7),
          Text('Contract ${leg.instrumentKey} · Lot ${leg.lotSize} · '
              'Tick ${leg.tickSize}',
            maxLines: 2,
            style: type.labelSmall?.copyWith(color: c.onSurfaceVariant)),
          const SizedBox(height: 4),
          Wrap(spacing: 12, runSpacing: 5, children: [
            _metric(context, 'Previous close',
                verified ? _price(leg.previousClose) : '—'),
            _metric(context, 'Open interest',
                verified ? (leg.openInterest?.toString() ?? '—') : '—'),
            _metric(context, 'IV', verified ? _decimal(leg.iv) : '—'),
            _metric(context, 'Delta', verified ? _decimal(leg.delta) : '—'),
            _metric(context, 'Gamma', verified ? _decimal(leg.gamma) : '—'),
            _metric(context, 'Theta', verified ? _decimal(leg.theta) : '—'),
            _metric(context, 'Vega', verified ? _decimal(leg.vega) : '—'),
          ]),
          const SizedBox(height: 4),
          Text(verified
              ? 'Observed ${_time(leg.observedAt)} · exchange ${_time(leg.exchangeAt)}'
              : 'Market fields hidden until a valid exchange-time V3 observation',
            style: type.labelSmall?.copyWith(color: c.onSurfaceVariant)),
        ],
      ]),
    );
  }

  Widget _metric(BuildContext context, String name, String value) =>
      Text('$name: $value',
        style: Theme.of(context).textTheme.labelSmall?.copyWith(
          color: Theme.of(context).colorScheme.onSurfaceVariant));

  Widget _movers(BuildContext context, CustomerFnoMarket? market) {
    final top = market == null ? <CustomerFnoLeg>[] :
        MintTickFnoDiscoveryFacts.previousCloseMovers(market)
          .where((leg) => MintTickFnoDiscoveryFacts.recentTick(
              leg, DateTime.now().toUtc())).take(4).toList();
    return Padding(padding: const EdgeInsets.only(bottom: 14),
      child: MintTickSurface(child: Column(
        crossAxisAlignment: CrossAxisAlignment.start, children: [
        _heading('Options by price movement', 'Since previous close · not 5-min momentum'),
        const SizedBox(height: 9),
        if (top.isEmpty)
          const Text('Change data unavailable until fresh V3 LTP and '
              'previous-close quotes are verified.')
        else for (final leg in top) _optionRow(context, leg),
        const SizedBox(height: 6),
        Text('5-min, 15-min, 1-hour momentum and most-traded rankings '
            'require validated time-series and trading-volume inputs.',
          style: Theme.of(context).textTheme.bodySmall),
      ])),
    );
  }

  Widget _analytics(BuildContext context, CustomerFnoMarket? market) {
    final all = market == null ? <CustomerFnoLeg>[] :
        MintTickFnoDiscoveryFacts.optionLegs(market, 'ALL');
    final fresh = market == null ? <CustomerFnoLeg>[] :
        MintTickFnoDiscoveryFacts.freshLegs(market, DateTime.now().toUtc());
    return Padding(padding: const EdgeInsets.only(bottom: 14),
      child: MintTickSurface(child: Column(
        crossAxisAlignment: CrossAxisAlignment.start, children: [
          _heading('Full NIFTY contract metrics',
              '${all.length} listed · ${fresh.length} currently exchange-fresh · V3 only'),
          const SizedBox(height: 10),
          if (all.isEmpty) const Text('No verified source-listed contracts.')
          else if (fresh.isEmpty) const Text(
              'No current V3 exchange-timed metrics. Listed contracts '
              'are still visible under Chain, without invented prices.')
          else for (final leg in fresh) ...[
            Text('${leg.tradingSymbol} · ${leg.side}',
              style: Theme.of(context).textTheme.bodyMedium
                  ?.copyWith(fontWeight: FontWeight.w800)),
            const SizedBox(height: 4),
            Wrap(spacing: 12, runSpacing: 6, children: [
              _metric(context, 'LTP', _price(leg.ltp)),
              _metric(context, 'Bid', _price(leg.bid)),
              _metric(context, 'Ask', _price(leg.ask)),
              _metric(context, 'Prev close', _price(leg.previousClose)),
              _metric(context, 'OI', leg.openInterest?.toString() ?? '—'),
              _metric(context, 'IV', _decimal(leg.iv)),
              _metric(context, 'Delta', _decimal(leg.delta)),
              _metric(context, 'Gamma', _decimal(leg.gamma)),
              _metric(context, 'Theta', _decimal(leg.theta)),
              _metric(context, 'Vega', _decimal(leg.vega)),
            ]),
            Text('Exchange: ${_time(leg.exchangeAt)} · '
                'Observed: ${_time(leg.observedAt)}',
              style: Theme.of(context).textTheme.labelSmall),
            const SizedBox(height: 12),
          ],
          const Divider(),
          const Text('All available V3 fields are shown for the supported '
              'NIFTY pilot. Volume rankings, historical charts, broader '
              'indices, stock F&O and commodities require separate '
              'verified feeds and usage permissions. IV retains the '
              'provider numeric value; no unverified unit conversion.'),
        ]),
      ));
  }

  Widget _trade(BuildContext context, TradingRoomDraft? room) =>
      MintTickSurface(child: Column(
        crossAxisAlignment: CrossAxisAlignment.start, children: [
        _heading('Trade in your F&O room', 'PAPER and LIVE are independent'),
        const SizedBox(height: 10),
        const Text('Choose a listed contract in Explore or Chain, then open '
            'the room terminal to review fresh bid/ask, eligible quantity, '
            'risk and broker confirmation. Browsing never sends an order.'),
        const SizedBox(height: 14),
        FilledButton.icon(
          key: const ValueKey('fno-discovery-open-terminal'),
          onPressed: room == null ? widget.onCreateRoom
              : widget.onOpenRoom == null ? null
              : () => widget.onOpenRoom?.call(room),
          icon: const Icon(Icons.candlestick_chart_rounded),
          label: Text(room == null ? 'Create F&O room' : 'Open trading terminal'),
        ),
      ]));

  Widget _unavailable(BuildContext context, String title, String subtitle,
      {String? action, VoidCallback? onTap}) =>
      Padding(padding: const EdgeInsets.only(bottom: 14),
        child: MintTickSurface(child: Column(
          crossAxisAlignment: CrossAxisAlignment.start, children: [
            _heading(title, 'Verified sources only'),
            const SizedBox(height: 9),
            Text(subtitle, style: Theme.of(context).textTheme.bodySmall),
            if (onTap != null && action != null)
              Align(alignment: Alignment.centerRight,
                child: TextButton(onPressed: onTap, child: Text(action))),
          ]),
        ));

  Widget _heading(String title, String detail) => Column(
    crossAxisAlignment: CrossAxisAlignment.start, children: [
      Text(title, style: const TextStyle(
        fontSize: 16, fontWeight: FontWeight.w800)),
      const SizedBox(height: 2),
      Text(detail, style: Theme.of(context).textTheme.labelSmall?.copyWith(
        color: Theme.of(context).colorScheme.onSurfaceVariant)),
    ]);

  Widget _statusPill(BuildContext context, String status, bool fresh) {
    final c = Theme.of(context).colorScheme;
    return Container(padding: const EdgeInsets.symmetric(
        horizontal: 9, vertical: 6),
      decoration: BoxDecoration(color: c.surfaceContainerHighest,
        borderRadius: BorderRadius.circular(8)),
      child: Text(status, style: TextStyle(
        color: fresh ? c.primary : c.onSurfaceVariant,
        fontSize: 10, fontWeight: FontWeight.w800)));
  }

  static String _price(num? n) =>
      n == null || !n.isFinite || n <= 0 ? '—' : '₹${n.toStringAsFixed(2)}';

  static String _decimal(num? n) =>
      n == null || !n.isFinite ? '—' : n.toStringAsFixed(2);

  static String _ymd(DateTime d) => '${d.day.toString().padLeft(2, '0')} '
      '${_month(d.month)} ${d.year}';

  static String _month(int m) => const [
    'Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'
  ][m - 1];

  static String _time(DateTime? date) =>
      date == null ? 'unknown' : '${date.toUtc().hour.toString().padLeft(2, '0')}:'
          '${date.toUtc().minute.toString().padLeft(2, '0')} UTC';
}

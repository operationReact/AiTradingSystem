import 'dart:async';
import 'dart:math' as math;

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

  /// Estimates the at-the-money strike where fresh CE and PE last prices are
  /// closest. The feed has no spot price, so callers must label this estimate.
  static num? atmStrike(CustomerFnoMarket market, DateTime now) {
    if (!market.hasFreshQuotes || !market.displayRightsVerified) {
      return null;
    }
    num? best;
    var bestGap = double.infinity;
    for (final strike in market.strikes) {
      final call = strike.call;
      final put = strike.put;
      if (call == null || put == null ||
          !recentTick(call, now) || !recentTick(put, now)) {
        continue;
      }
      final callLtp = call.ltp;
      final putLtp = put.ltp;
      if (callLtp == null || putLtp == null || callLtp <= 0 || putLtp <= 0) {
        continue;
      }
      final gap = (callLtp - putLtp).abs();
      if (gap < bestGap) {
        bestGap = gap.toDouble();
        best = strike.strikePrice;
      }
    }
    return best;
  }
  /// Computes an OI put/call ratio only for the exact returned expiry chain
  /// when every listed strike has fresh, time-attested CE and PE open interest.
  /// A sampled/partial/stale chain must never be labelled market-wide PCR.
  static ({int calls, int puts, double pcr})? verifiedOiTotals(
      CustomerFnoMarket market, DateTime now) {
    if (!market.displayRightsVerified || !market.hasFreshQuotes ||
        market.strikes.isEmpty) {
      return null;
    }
    var calls = 0;
    var puts = 0;
    for (final strike in market.strikes) {
      final ce = strike.call;
      final pe = strike.put;
      if (ce == null || pe == null ||
          !recentTick(ce, now) || !recentTick(pe, now) ||
          ce.openInterest == null || pe.openInterest == null ||
          ce.openInterest! < 0 || pe.openInterest! < 0) {
        return null;
      }
      calls += ce.openInterest!;
      puts += pe.openInterest!;
    }
    if (calls == 0) { // no valid denominator
      return null;
    }
    return (calls: calls, puts: puts, pcr: puts / calls);
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
  String _section = 'Overview';
  String _strikeQuery = '';
  bool _showAllStrikes = false;
  int _strikePage = 0;
  DateTime? _centeredExpiry;
  final ScrollController _chainScroll = ScrollController();
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
    _chainScroll.dispose();
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
      _centeredExpiry = null;
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

  static const int _windowRadius = 4;
  static const double _cellWidth = 66;
  static const double _strikeWidth = 84;
  static const double _tableWidth = _cellWidth * 6 + _strikeWidth;
  static const double _strikeCentreX = _cellWidth * 3 + _strikeWidth / 2;
  static const TextStyle _tabular =
      TextStyle(fontFeatures: [FontFeature.tabularFigures()]);

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final room = _room;
    final market = _market;
    final now = DateTime.now().toUtc();
    final freshCount = market == null
        ? 0
        : MintTickFnoDiscoveryFacts.freshLegs(market, now).length;
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      const SizedBox(height: 7),
      Row(children: [
        const Expanded(child: Text('F&O · Discover',
          style: TextStyle(fontSize: 22, fontWeight: FontWeight.w800))),
        _statusChip(context, market, freshCount > 0),
        IconButton(
          key: const ValueKey('fno-discovery-refresh'),
          tooltip: 'Refresh F&O source',
          onPressed: _loading ? null : _refresh,
          icon: const Icon(Icons.refresh_rounded),
        ),
      ]),
      Text('Real NSE contracts · no indicative or synthetic prices',
        style: theme.textTheme.bodySmall?.copyWith(
          color: theme.colorScheme.onSurfaceVariant)),
      const SizedBox(height: 12),
      _summary(context, market, freshCount),
      const SizedBox(height: 10),
      _navigation(context),
      const SizedBox(height: 10),
      if (_loading) const LinearProgressIndicator(minHeight: 2),
      if (_error != null) Padding(
        padding: const EdgeInsets.only(top: 7, bottom: 9),
        child: Text(_error!,
            style: TextStyle(color: theme.colorScheme.error))),
      if (_section == 'Overview') ...[
        _overview(context, market, now),
      ] else if (_section == 'Chain') ...[
        _expiryRow(context),
        const SizedBox(height: 8),
        _chain(context, market, now),
      ] else if (_section == 'Insights') ...[
        _insights(context, market, now),
      ] else if (_section == 'Movers') ...[
        _movers(context, market, now),
      ] else ...[
        _roomPicker(context, room),
        const SizedBox(height: 10),
        _trade(context, room),
      ],
      const SizedBox(height: 20),
    ]);
  }

  Widget _statusChip(BuildContext context, CustomerFnoMarket? market,
      bool live) {
    final c = Theme.of(context).colorScheme;
    String label;
    IconData icon;
    Color color;
    if (market == null) {
      label = _loading ? 'LOADING' : 'NOT LIVE';
      icon = Icons.sync_disabled_rounded;
      color = c.onSurfaceVariant;
    } else if (market.status == 'MARKET_CLOSED') {
      label = 'MARKET CLOSED';
      icon = Icons.nightlight_round;
      color = c.onSurfaceVariant;
    } else if (live) {
      label = 'LIVE';
      icon = Icons.fiber_manual_record;
      color = c.primary;
    } else {
      label = 'NO FRESH QUOTES';
      icon = Icons.warning_amber_rounded;
      color = c.tertiary;
    }
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 5),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.12),
        border: Border.all(color: color.withValues(alpha: 0.5)),
        borderRadius: BorderRadius.circular(8)),
      child: Row(mainAxisSize: MainAxisSize.min, children: [
        Icon(icon, size: 12, color: color),
        const SizedBox(width: 5),
        Text(label, style: TextStyle(
          color: color, fontSize: 10, fontWeight: FontWeight.w800)),
      ]),
    );
  }

  Widget _summary(BuildContext context, CustomerFnoMarket? market,
      int freshCount) {
    final c = Theme.of(context).colorScheme;
    final count = market == null
        ? null : MintTickFnoDiscoveryFacts.optionLegs(market, 'ALL').length;
    final caption = count == null
        ? 'Verified contract catalog unavailable'
        : freshCount > 0
            ? '$count contracts · $freshCount verified fresh quotes'
            : '$count contracts listed · prices unavailable';
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 5),
      child: Row(children: [
        Tooltip(
          message: 'Feed state: ${_source?.streamState ?? 'unverified'}',
          child: Icon(freshCount > 0 ? Icons.bolt_rounded
                : Icons.info_outline_rounded,
            size: 16, color: freshCount > 0 ? c.primary : c.onSurfaceVariant),
        ),
        const SizedBox(width: 7),
        Expanded(child: Text(caption, maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: Theme.of(context).textTheme.bodySmall?.copyWith(
            color: c.onSurfaceVariant))),
      ]),
    );
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
        for (final name in const ['Overview', 'Chain', 'Insights', 'Movers', 'Trade'])
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


  Widget _overview(BuildContext context, CustomerFnoMarket? market,
      DateTime now) {
    final theme = Theme.of(context);
    final c = theme.colorScheme;
    final type = theme.textTheme;
    final fresh = market == null
        ? <CustomerFnoLeg>[]
        : MintTickFnoDiscoveryFacts.freshLegs(market, now).toList();
    final movers = fresh.where((leg) =>
        leg.priceChangePct != null && leg.priceChangePct!.isFinite).toList()
      ..sort((a, b) => b.priceChangePct!.abs()
          .compareTo(a.priceChangePct!.abs()));
    Widget shortcut(String label, IconData icon, String target) =>
      OutlinedButton.icon(
        key: ValueKey('fno-discovery-quick-${target.toLowerCase()}'),
        onPressed: () => setState(() => _section = target),
        icon: Icon(icon, size: 16),
        label: Text(label),
        style: OutlinedButton.styleFrom(
          visualDensity: VisualDensity.compact,
          padding: const EdgeInsets.symmetric(horizontal: 11, vertical: 8)),
      );
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      _panel(context, 'Explore NIFTY options',
        'Verified NSE contracts · no synthetic prices',
        Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          if (_expiries.isNotEmpty) ...[
            Text('SELECT EXPIRY', style: type.labelSmall?.copyWith(
              color: c.onSurfaceVariant)),
            const SizedBox(height: 8),
            _expiryRow(context),
            const SizedBox(height: 10),
          ],
          Wrap(spacing: 7, runSpacing: 2, children: [
            shortcut('Option chain', Icons.view_list_outlined, 'Chain'),
            shortcut('OI Insights', Icons.insights_outlined, 'Insights'),
            shortcut('Price movers', Icons.trending_up, 'Movers'),
            shortcut('Trade desk', Icons.candlestick_chart_outlined, 'Trade'),
          ]),
          if (_availableRooms.isEmpty) ...[
            const SizedBox(height: 8),
            Text('Market discovery works without a room.',
              style: type.bodySmall?.copyWith(color: c.onSurfaceVariant)),
            TextButton(
              key: const ValueKey('fno-discovery-create-room'),
              onPressed: widget.onCreateRoom,
              child: const Text('Create F&O room'),
            ),
          ],
        ])),
      if (movers.isEmpty)
        _panel(context, 'Price leaders unavailable',
          market?.status == 'MARKET_CLOSED'
            ? 'Market closed · contract identities remain browsable'
            : 'Source-verified quotes required',
          Text('Fresh exchange-timed option prices are unavailable. '
              'Browse listed contracts in Option chain, or return when '
              'the feed is authorized and current. No stale price is LIVE.',
              style: type.bodySmall))
      else
        _panel(context, 'Largest verified price moves',
          'Change since previous close · not intraday momentum',
          Column(children: [
            for (final leg in movers.take(5))
              _moverRow(context, market!, leg, now),
          ])),
      if (_rightsStatus != null && !_readOnlyConfigured)
        Text('Source status: ${_rightsStatus!.replaceAll('_', ' ')}',
          style: type.labelSmall?.copyWith(color: c.onSurfaceVariant)),
      Text('Futures, basket execution and event-driven recommendations '
           'require separate verified integrations.',
           style: type.labelSmall?.copyWith(color: c.onSurfaceVariant)),
    ]);
  }

  Widget _expiryRow(BuildContext context) {
    if (_expiries.isEmpty) {
      return MintTickSurface(child: Text('No verified upcoming expiry calendar.',
          style: Theme.of(context).textTheme.bodySmall));
    }
    return SizedBox(height: 40,
      child: ListView.separated(
        scrollDirection: Axis.horizontal,
        itemCount: _expiries.length,
        separatorBuilder: (_, _) => const SizedBox(width: 8),
        itemBuilder: (context, index) {
          final date = _expiries[index];
          return ChoiceChip(
            key: ValueKey('fno-discovery-expiry-${_ymd(date)}'),
            label: Text(_ymd(date)),
            selected: _expiry == date,
            onSelected: _loading ? null : (_) => _loadMarket(date),
          );
        },
      ));
  }

  Widget _chain(BuildContext context, CustomerFnoMarket? market, DateTime now) {
    final c = Theme.of(context).colorScheme;
    final type = Theme.of(context).textTheme;
    if (market == null) {
      return _panel(context, 'NIFTY option chain', 'Source-listed contracts',
        const Text('No verified option contracts available for this expiry.'));
    }
    final atm = MintTickFnoDiscoveryFacts.atmStrike(market, now);
    final searchedStrike = num.tryParse(_strikeQuery.trim());
    final strikes = _visibleStrikes(market.strikes, searchedStrike ?? atm);
    final pages = (market.strikes.length / (_windowRadius * 2 + 1)).ceil();
    if (atm != null && _centeredExpiry != market.expiry) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (!mounted || !_chainScroll.hasClients) return;
        final position = _chainScroll.position;
        final target = (_strikeCentreX - position.viewportDimension / 2)
            .clamp(0.0, position.maxScrollExtent);
        _chainScroll.jumpTo(target.toDouble());
        _centeredExpiry = market.expiry;
      });
    }
    return _panel(context, 'NIFTY option chain',
      '${_ymd(market.expiry)} · ${market.strikes.length} strikes · '
      'exchange-listed',
      Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text(atm == null
            ? 'No fresh option prices. Search by strike to view contracts.'
            : 'ATM ≈ ${_strike(atm)} · estimated from fresh CE/PE prices, '
              'not spot.',
          style: type.labelSmall?.copyWith(color: c.onSurfaceVariant)),
        const SizedBox(height: 8),
        TextField(
          key: const ValueKey('fno-discovery-strike-search'),
          keyboardType: const TextInputType.numberWithOptions(decimal: true),
          decoration: const InputDecoration(
            prefixIcon: Icon(Icons.search, size: 20),
            isDense: true,
            border: OutlineInputBorder(),
            hintText: 'Search strike, e.g. 25800',
          ),
          onChanged: (value) => setState(() {
            _strikeQuery = value;
            _showAllStrikes = false;
            _strikePage = 0;
          }),
        ),
        if (searchedStrike != null) ...[
          const SizedBox(height: 6),
          Text('Showing listed strikes near ${_strike(searchedStrike)} · '
              'search selection is not an ATM estimate.',
              style: type.labelSmall?.copyWith(color: c.onSurfaceVariant)),
        ],
        const SizedBox(height: 10),
        if (strikes.isEmpty)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 12),
            child: Row(children: [
              Icon(Icons.search_rounded, size: 20, color: c.onSurfaceVariant),
              const SizedBox(width: 10),
              Expanded(child: Text('No strike selected. Enter a strike above '
                'or browse listed contracts in short pages.',
                style: type.bodySmall?.copyWith(color: c.onSurfaceVariant))),
            ]),
          )
        else if (MintTickFnoDiscoveryFacts.freshLegs(market, now).isEmpty)
          _listedOnlyChain(context, market, strikes, now)
        else if (MediaQuery.sizeOf(context).width < 600)
          _compactChain(context, market, strikes, atm, now)
        else
          SingleChildScrollView(
            controller: _chainScroll,
            scrollDirection: Axis.horizontal,
            child: SizedBox(width: _tableWidth, child: Column(children: [
              _chainHeader(context),
              for (final strike in strikes)
                _chainRow(context, market, strike, atm, now),
            ])),
          ),
        const SizedBox(height: 4),
        if (_showAllStrikes)
          Row(children: [
            Text('Page ${_strikePage + 1} of $pages',
              style: type.labelSmall?.copyWith(color: c.onSurfaceVariant)),
            const Spacer(),
            TextButton(
              key: const ValueKey('fno-discovery-prev-page'),
              onPressed: _strikePage == 0
                  ? null : () => setState(() => _strikePage--),
              child: const Text('Previous')),
            TextButton(
              key: const ValueKey('fno-discovery-next-page'),
              onPressed: _strikePage + 1 >= pages
                  ? null : () => setState(() => _strikePage++),
              child: const Text('Next')),
            TextButton(
              onPressed: () => setState(() {
                _showAllStrikes = false;
                _strikePage = 0;
              }),
              child: const Text('Close')),
          ])
        else if (strikes.isEmpty)
          Align(alignment: Alignment.centerRight,
            child: TextButton.icon(
              key: const ValueKey('fno-discovery-browse-strikes'),
              onPressed: market.strikes.isEmpty ? null : () => setState(() {
                _showAllStrikes = true;
                _strikePage = 0;
                _strikeQuery = '';
              }),
              icon: const Icon(Icons.view_list_rounded, size: 16),
              label: const Text('Browse listed strikes'),
            )),
        if (market.status == 'MARKET_CLOSED')
          Text('Market closed · no stale contract is labelled LIVE.',
            style: type.labelSmall?.copyWith(color: c.onSurfaceVariant)),
      ]));
  }

  List<CustomerFnoStrike> _visibleStrikes(
      List<CustomerFnoStrike> strikes, num? atm) {
    final window = _windowRadius * 2 + 1;
    if (strikes.isEmpty) return const [];
    if (_showAllStrikes) {
      final start = (_strikePage * window).clamp(0,
          math.max(0, strikes.length - 1)).toInt();
      return strikes.sublist(start,
          math.min(strikes.length, start + window));
    }
    if (atm == null) return const [];
    if (strikes.length <= window) return strikes;
    var centre = 0;
    for (var i = 1; i < strikes.length; i++) {
      if ((strikes[i].strikePrice - atm).abs() <
          (strikes[centre].strikePrice - atm).abs()) {
        centre = i;
      }
    }
    final start = math.max(0,
        math.min(centre - _windowRadius, strikes.length - window));
    return strikes.sublist(start, start + window);
  }

  /// Contract identities remain browsable when prices cannot be verified.
  Widget _listedOnlyChain(BuildContext context, CustomerFnoMarket market,
      List<CustomerFnoStrike> strikes, DateTime now) {
    final c = Theme.of(context).colorScheme;
    final heading = Theme.of(context).textTheme.labelSmall?.copyWith(
      color: c.onSurfaceVariant, fontWeight: FontWeight.w700);
    Widget side(CustomerFnoLeg? leg, String label) =>
      Expanded(child: Align(alignment: Alignment.center,
        child: TextButton(
          onPressed: leg == null
              ? null : () => _showLeg(context, market, leg, now),
          child: Text(label, style: const TextStyle(
            fontWeight: FontWeight.w700, fontSize: 12)),
        )));
    return Column(children: [
      Row(children: [
        Expanded(child: Text('CALL · CE',
          textAlign: TextAlign.center, style: heading)),
        Expanded(child: Text('STRIKE',
          textAlign: TextAlign.center, style: heading)),
        Expanded(child: Text('PUT · PE',
          textAlign: TextAlign.center, style: heading)),
      ]),
      const SizedBox(height: 5),
      for (final strike in strikes)
        Container(
          decoration: BoxDecoration(border: Border(bottom: BorderSide(
            width: 0.5, color: c.outlineVariant.withValues(alpha: 0.5)))),
          child: Row(children: [
            side(strike.call, 'View CE'),
            Expanded(child: Text(_strike(strike.strikePrice),
              textAlign: TextAlign.center,
              style: const TextStyle(
                fontSize: 13, fontWeight: FontWeight.w800))),
            side(strike.put, 'View PE'),
          ]),
        ),
    ]);
  }

  /// On a phone both CE and PE prices must be visible without horizontal
  /// scrolling. Extended OI/Greeks/depth are available from the leg detail.
  Widget _compactChain(BuildContext context, CustomerFnoMarket market,
      List<CustomerFnoStrike> strikes, num? atm, DateTime now) {
    final c = Theme.of(context).colorScheme;
    final heading = Theme.of(context).textTheme.labelSmall?.copyWith(
      fontWeight: FontWeight.w800, color: c.onSurfaceVariant);
    Widget priceCell(CustomerFnoLeg? leg) {
      final fresh = leg != null && _fresh(market, leg, now);
      final change = fresh ? leg.priceChangePct : null;
      return Expanded(child: InkWell(
        onTap: leg == null ? null : () => _showLeg(context, market, leg, now),
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: 6, horizontal: 3),
          child: Column(mainAxisSize: MainAxisSize.min, children: [
            Text(_price(fresh ? leg.ltp : null),
              maxLines: 1,
              style: const TextStyle(
                fontSize: 13, fontWeight: FontWeight.w800,
                fontFeatures: [FontFeature.tabularFigures()])),
            const SizedBox(height: 3),
            Text(_changeText(change), maxLines: 1,
              style: TextStyle(fontSize: 10,
                color: change == null ? c.onSurfaceVariant
                    : change >= 0 ? c.primary : c.error)),
          ]),
        )));
    }
    return Column(children: [
      Row(children: [
        Expanded(child: Text('CALL · CE',
          textAlign: TextAlign.center, style: heading)),
        Expanded(child: Text('STRIKE',
          textAlign: TextAlign.center, style: heading)),
        Expanded(child: Text('PUT · PE',
          textAlign: TextAlign.center, style: heading)),
      ]),
      Divider(color: c.outlineVariant),
      for (final strike in strikes)
        Container(
          decoration: BoxDecoration(
            color: atm != null && atm == strike.strikePrice
                ? c.primary.withValues(alpha: 0.12) : null,
            border: Border(bottom: BorderSide(
              color: c.outlineVariant.withValues(alpha: 0.5), width: 0.5)),
          ),
          child: Row(children: [
            priceCell(strike.call),
            Expanded(child: Column(mainAxisSize: MainAxisSize.min, children: [
              if (atm != null && atm == strike.strikePrice)
                Text('ATM', style: TextStyle(
                  fontSize: 9, color: c.primary, fontWeight: FontWeight.w700)),
              Text(_strike(strike.strikePrice),
                textAlign: TextAlign.center,
                style: const TextStyle(
                  fontSize: 14, fontWeight: FontWeight.w800)),
            ])),
            priceCell(strike.put),
          ]),
        ),
    ]);
  }

  Widget _chainHeader(BuildContext context) {
    final c = Theme.of(context).colorScheme;
    final style = Theme.of(context).textTheme.labelSmall?.copyWith(
      color: c.onSurfaceVariant, fontWeight: FontWeight.w700);
    Widget sub(String text) => SizedBox(width: _cellWidth,
        child: Text(text, textAlign: TextAlign.right, style: style));
    return Column(children: [
      Row(children: [
        SizedBox(width: _cellWidth * 3, child: Text('CALL · CE',
          textAlign: TextAlign.center,
          style: style?.copyWith(color: c.onSurface))),
        SizedBox(width: _strikeWidth, child: Text('STRIKE',
          textAlign: TextAlign.center,
          style: style?.copyWith(color: c.onSurface))),
        SizedBox(width: _cellWidth * 3, child: Text('PUT · PE',
          textAlign: TextAlign.center,
          style: style?.copyWith(color: c.onSurface))),
      ]),
      const SizedBox(height: 4),
      Row(children: [
        sub('OI'), sub('LTP'), sub('Chg'),
        const SizedBox(width: _strikeWidth),
        sub('Chg'), sub('LTP'), sub('OI'),
      ]),
      Divider(color: c.outlineVariant, height: 10),
    ]);
  }

  Widget _chainRow(BuildContext context, CustomerFnoMarket market,
      CustomerFnoStrike strike, num? atm, DateTime now) {
    final c = Theme.of(context).colorScheme;
    final tint = c.primary.withValues(alpha: 0.07);
    final isAtm = atm != null && strike.strikePrice == atm;
    final callItm = atm != null && strike.strikePrice < atm;
    final putItm = atm != null && strike.strikePrice > atm;
    return Container(
      decoration: BoxDecoration(
        color: isAtm ? c.primary.withValues(alpha: 0.14) : null,
        border: Border(bottom: BorderSide(
          color: c.outlineVariant.withValues(alpha: 0.5), width: 0.5))),
      child: Row(children: [
        _legCells(context, market, strike.call, now, itm: callItm,
            tint: tint, reverse: false),
        SizedBox(width: _strikeWidth, child: Padding(
          padding: const EdgeInsets.symmetric(vertical: 8),
          child: Column(mainAxisSize: MainAxisSize.min, children: [
            if (isAtm) Text('ATM', style: TextStyle(color: c.primary,
                fontSize: 9, fontWeight: FontWeight.w800)),
            Text(_strike(strike.strikePrice), style: const TextStyle(
                fontSize: 13, fontWeight: FontWeight.w800)),
          ]))),
        _legCells(context, market, strike.put, now, itm: putItm,
            tint: tint, reverse: true),
      ]),
    );
  }

  Widget _legCells(BuildContext context, CustomerFnoMarket market,
      CustomerFnoLeg? leg, DateTime now, {required bool itm,
      required Color tint, required bool reverse}) {
    final c = Theme.of(context).colorScheme;
    final fresh = leg != null && _fresh(market, leg, now);
    final pct = fresh ? leg.priceChangePct : null;
    final cells = <Widget>[
      _numCell(context, fresh ? _count(leg.openInterest) : '—'),
      _numCell(context, fresh ? _amount(leg.ltp) : '—', bold: true),
      _numCell(context, _changeText(pct),
          color: pct == null ? c.onSurfaceVariant
              : pct >= 0 ? c.primary : c.error),
    ];
    return InkWell(
      onTap: leg == null ? null : () => _showLeg(context, market, leg, now),
      child: Container(
        width: _cellWidth * 3,
        color: itm ? tint : null,
        child: Row(children: reverse ? cells.reversed.toList() : cells)),
    );
  }

  Widget _numCell(BuildContext context, String text,
      {bool bold = false, Color? color}) =>
      SizedBox(width: _cellWidth, child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 8),
        child: Text(text, textAlign: TextAlign.right, maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: _tabular.copyWith(
            fontSize: 12,
            fontWeight: bold ? FontWeight.w800 : FontWeight.w500,
            color: color))));

  void _showLeg(BuildContext context, CustomerFnoMarket market,
      CustomerFnoLeg leg, DateTime now) {
    final fresh = _fresh(market, leg, now);
    final type = Theme.of(context).textTheme;
    final c = Theme.of(context).colorScheme;
    final pct = fresh ? leg.priceChangePct : null;
    if (!fresh) {
      showModalBottomSheet<void>(
        context: context,
        builder: (sheetContext) => SafeArea(
          child: Padding(
            padding: const EdgeInsets.fromLTRB(20, 20, 20, 18),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(leg.tradingSymbol,
                  style: type.titleMedium?.copyWith(
                    fontWeight: FontWeight.w800)),
                const SizedBox(height: 5),
                Text('${leg.side == 'CE' ? 'Call' : 'Put'} · '
                     'Lot ${leg.lotSize} · Tick ${leg.tickSize}',
                  style: type.bodySmall?.copyWith(
                    color: c.onSurfaceVariant)),
                const SizedBox(height: 18),
                Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Icon(Icons.info_outline_rounded,
                    color: c.onSurfaceVariant, size: 18),
                  const SizedBox(width: 10),
                  const Expanded(child: Text(
                    'Price, OI and Greeks are unavailable without '
                    'a verified fresh exchange quote. No trading signal '
                    'or order can be inferred from the contract listing.')),
                ]),
                const SizedBox(height: 14),
                Align(alignment: Alignment.centerRight,
                  child: TextButton(
                    onPressed: () => Navigator.of(sheetContext).pop(),
                    child: const Text('Close'),
                  )),
              ],
            ),
          ),
        ),
      );
      return;
    }
    showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      builder: (sheetContext) => SafeArea(child: SingleChildScrollView(
        padding: const EdgeInsets.fromLTRB(18, 14, 18, 18),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(leg.tradingSymbol, style: type.titleMedium
                ?.copyWith(fontWeight: FontWeight.w800)),
            Text('${leg.side == 'CE' ? 'Call' : 'Put'} · lot ${leg.lotSize} · '
                'tick ${leg.tickSize}', style: type.bodySmall),
            const SizedBox(height: 10),
            Text(fresh
                ? 'Exchange-timed · V3 verified'
                : 'No fresh exchange-timed quote · values hidden',
              style: type.labelSmall?.copyWith(
                color: fresh ? c.primary : c.onSurfaceVariant,
                fontWeight: FontWeight.w700)),
            const Divider(height: 22),
            _sheetRow(sheetContext, 'LTP', _price(fresh ? leg.ltp : null)),
            _sheetRow(sheetContext, 'Change since previous close',
                pct == null ? '—' : _changeText(pct)),
            _sheetRow(sheetContext, 'Bid', _price(fresh ? leg.bid : null)),
            _sheetRow(sheetContext, 'Ask', _price(fresh ? leg.ask : null)),
            _sheetRow(sheetContext, 'Previous close',
                _price(fresh ? leg.previousClose : null)),
            _sheetRow(sheetContext, 'Open interest',
                fresh ? _count(leg.openInterest) : '—'),
            _sheetRow(sheetContext, 'IV', fresh ? _decimal(leg.iv) : '—'),
            _sheetRow(sheetContext, 'Delta', fresh ? _decimal(leg.delta) : '—'),
            _sheetRow(sheetContext, 'Gamma', fresh ? _decimal(leg.gamma) : '—'),
            _sheetRow(sheetContext, 'Theta', fresh ? _decimal(leg.theta) : '—'),
            _sheetRow(sheetContext, 'Vega', fresh ? _decimal(leg.vega) : '—'),
            const Divider(height: 22),
            _sheetRow(sheetContext, 'Exchange time',
                fresh ? _time(leg.exchangeAt) : '—'),
            _sheetRow(sheetContext, 'Observed',
                fresh ? _time(leg.observedAt) : '—'),
            const SizedBox(height: 6),
            Text('Instrument ${leg.instrumentKey}',
              style: type.labelSmall?.copyWith(color: c.onSurfaceVariant)),
            const SizedBox(height: 12),
            Text('Browsing never sends an order. Order entry happens only in '
                'your trading terminal after risk checks and confirmation.',
              style: type.bodySmall),
          ]),
      )),
    );
  }

  Widget _sheetRow(BuildContext context, String label, String value) =>
      Padding(
        padding: const EdgeInsets.symmetric(vertical: 4),
        child: Row(children: [
          Expanded(child: Text(label,
              style: Theme.of(context).textTheme.bodySmall)),
          Text(value, style: _tabular.copyWith(
              fontWeight: FontWeight.w700, fontSize: 13)),
        ]),
      );

  Widget _insights(BuildContext context, CustomerFnoMarket? market,
      DateTime now) {
    final type = Theme.of(context).textTheme;
    final c = Theme.of(context).colorScheme;
    if (market == null) {
      return _panel(context, 'Options positioning', 'Verified quotes only',
        const Text('Insights unavailable until a listed expiry and '
            'source-authorized option chain are loaded.'));
    }
    final totals = MintTickFnoDiscoveryFacts.verifiedOiTotals(market, now);
    Widget statistic(String label, String value) => Expanded(
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text(label, maxLines: 1, overflow: TextOverflow.ellipsis,
          style: type.labelSmall?.copyWith(color: c.onSurfaceVariant)),
        const SizedBox(height: 5),
        Text(value, style: type.titleMedium?.copyWith(
          fontWeight: FontWeight.w800, fontFeatures: const [
            FontFeature.tabularFigures(),
          ])),
      ]));
    return _panel(context, 'Options positioning',
        '${_ymd(market.expiry)} · returned strikes only',
      Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        if (totals == null)
          Text('Open-interest comparison unavailable. All returned CE and '
              'PE legs require fresh exchange timestamps and verified OI.',
              style: type.bodySmall)
        else ...[
          Row(children: [
            statistic('CALL OI', _count(totals.calls)),
            statistic('PUT OI', _count(totals.puts)),
            statistic('OI PCR', totals.pcr.toStringAsFixed(2)),
          ]),
          const SizedBox(height: 12),
          Text('OI PCR = put open interest / call open interest for this '
              'returned chain. Not a market-wide PCR or trade signal.',
              style: type.bodySmall?.copyWith(color: c.onSurfaceVariant)),
        ],
        const Divider(height: 22),
        Text('IV, Greeks, OI change, market depth and Max Pain are shown '
            'only when the source supplies independently verified values. '
            'A closed or stale session never produces an actionable signal.',
            style: type.bodySmall?.copyWith(color: c.onSurfaceVariant)),
      ]));
  }

  Widget _movers(BuildContext context, CustomerFnoMarket? market,
      DateTime now) {
    final all = market == null
        ? <CustomerFnoLeg>[]
        : MintTickFnoDiscoveryFacts.previousCloseMovers(market)
            .where((leg) => MintTickFnoDiscoveryFacts.recentTick(leg, now))
            .toList();
    final gainers = all.where((l) => _pct(l) > 0).toList()
      ..sort((a, b) => _pct(b).compareTo(_pct(a)));
    final losers = all.where((l) => _pct(l) < 0).toList()
      ..sort((a, b) => _pct(a).compareTo(_pct(b)));
    final c = Theme.of(context).colorScheme;
    Widget list(List<CustomerFnoLeg> legs) => legs.isEmpty
        ? Text('None verified yet.',
            style: Theme.of(context).textTheme.bodySmall)
        : Column(children: [
            for (final leg in legs.take(5))
              _moverRow(context, market!, leg, now),
          ]);
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      _panel(context, 'Top gainers', 'Since previous close · fresh only',
        gainers.isEmpty
            ? Text('Change data unavailable until fresh V3 LTP and '
                'previous-close quotes are verified.',
                style: Theme.of(context).textTheme.bodySmall)
            : list(gainers)),
      const SizedBox(height: 14),
      _panel(context, 'Top losers', 'Since previous close · fresh only',
        list(losers)),
      const SizedBox(height: 14),
      _panel(context, 'Momentum and volume rankings',
        'Not connected yet',
        Text('5-min, 15-min, 1-hour momentum and most-traded rankings need '
            'validated time-series and trading-volume inputs.',
          style: Theme.of(context).textTheme.bodySmall?.copyWith(
            color: c.onSurfaceVariant))),
    ]);
  }

  Widget _moverRow(BuildContext context, CustomerFnoMarket market,
      CustomerFnoLeg leg, DateTime now) {
    final c = Theme.of(context).colorScheme;
    final type = Theme.of(context).textTheme;
    final pct = _pct(leg);
    return InkWell(
      onTap: () => _showLeg(context, market, leg, now),
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 9),
        child: Row(children: [
          Container(width: 38, height: 26,
            alignment: Alignment.center,
            decoration: BoxDecoration(color: c.surfaceContainerHighest,
              borderRadius: BorderRadius.circular(8)),
            child: Text(leg.side, style: TextStyle(color: c.primary,
                fontWeight: FontWeight.w800, fontSize: 11))),
          const SizedBox(width: 10),
          Expanded(child: Text(leg.tradingSymbol, maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: type.bodyMedium?.copyWith(fontWeight: FontWeight.w700))),
          Text(_price(leg.ltp), style: _tabular.copyWith(
              fontWeight: FontWeight.w700)),
          const SizedBox(width: 12),
          SizedBox(width: 84, child: Text(_changeText(pct),
            textAlign: TextAlign.right,
            style: TextStyle(color: pct >= 0 ? c.primary : c.error,
                fontWeight: FontWeight.w800))),
        ]),
      ),
    );
  }

  Widget _trade(BuildContext context, TradingRoomDraft? room) =>
      MintTickSurface(child: Column(
        crossAxisAlignment: CrossAxisAlignment.start, children: [
        _heading('Trade in your F&O room', 'PAPER and LIVE are independent'),
        const SizedBox(height: 10),
        const Text('Choose a listed contract in Chain, then open the room '
            'terminal to review fresh bid/ask, eligible quantity, risk and '
            'broker confirmation. Browsing never sends an order.'),
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

  Widget _panel(BuildContext context, String title, String detail,
      Widget child) =>
      Padding(padding: const EdgeInsets.only(bottom: 14),
        child: MintTickSurface(child: Column(
          crossAxisAlignment: CrossAxisAlignment.start, children: [
            _heading(title, detail),
            const SizedBox(height: 10),
            child,
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

  static bool _fresh(CustomerFnoMarket market, CustomerFnoLeg leg,
          DateTime now) =>
      market.hasFreshQuotes && market.displayRightsVerified &&
      MintTickFnoDiscoveryFacts.recentTick(leg, now);

  static num _pct(CustomerFnoLeg leg) => leg.priceChangePct ?? 0;

  static String _changeText(num? pct) => pct == null || !pct.isFinite
      ? '—'
      : '${pct >= 0 ? '▲' : '▼'} ${pct.abs().toStringAsFixed(2)}%';

  static String _strike(num n) => n == n.roundToDouble()
      ? n.toInt().toString()
      : n.toStringAsFixed(2);

  static String _count(num? n) => n == null || !n.isFinite || n < 0
      ? '—'
      : n.toInt().toString();

  static String _amount(num? n) =>
      n == null || !n.isFinite || n <= 0 ? '—' : n.toStringAsFixed(2);

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

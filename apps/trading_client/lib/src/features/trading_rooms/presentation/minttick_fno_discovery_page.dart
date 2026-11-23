import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../data/customer_paper_fno_repository.dart';
import '../data/customer_fno_discovery_repository.dart';
import '../data/customer_fno_universe_repository.dart';
import 'minttick_fno_universe_panel.dart';
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

  /// Rank by actual open-interest counts, never mislabel OI as traded volume
  /// or OI change. The V2 chain timestamp is the HTTP retrieval time, so
  /// these observations are display-only and never confer order eligibility.
  static List<CustomerFnoLeg> highestOpenInterest(
      CustomerFnoMarket market, DateTime now, String side) {
    if (!market.displayRightsVerified) return const [];
    final utc = now.toUtc();
    final snapshot = market.snapshotAt?.toUtc();
    final chainRecent = snapshot != null && !snapshot.isAfter(utc) &&
        utc.difference(snapshot) <= const Duration(minutes: 5);
    int? oi(CustomerFnoLeg leg) {
      if (market.hasFreshQuotes && recentTick(leg, utc) &&
          leg.openInterest != null) {
        return leg.openInterest;
      }
      return chainRecent
          ? market.chainSnapshotIndicators[leg.instrumentKey]?.openInterest
          : null;
    }
    final legs = optionLegs(market, side)
        .where((leg) => oi(leg) != null).toList();
    legs.sort((a, b) {
      final difference = oi(b)!.compareTo(oi(a)!);
      return difference != 0
          ? difference : a.instrumentKey.compareTo(b.instrumentKey);
    });
    return List.unmodifiable(legs);
  }

  /// V2 snapshot rankings require independent display permission and a
  /// recent HTTP observation. They are NOT timestamped trades or order data.
  /// VOLUME uses actual source volume; OI_GAIN uses current minus previous OI.
  static List<CustomerFnoLeg> snapshotLeaders(
      CustomerFnoMarket market, DateTime now, String side,
      {required String metric}) {
    if (!market.displayRightsVerified ||
        (metric != 'VOLUME' && metric != 'OI_GAIN')) {
      return const [];
    }
    final utc = now.toUtc();
    final at = market.snapshotAt?.toUtc();
    if (at == null || at.isAfter(utc) ||
        utc.difference(at) > const Duration(minutes: 5)) {
      return const [];
    }
    int? score(CustomerFnoLeg leg) {
      final chain = market.chainSnapshotIndicators[leg.instrumentKey];
      if (chain == null) return null;
      if (metric == 'VOLUME') {
        final volume = chain.volume;
        return volume != null && volume > 0 ? volume : null;
      }
      final change = chain.openInterestChange;
      return change != null && change > 0 ? change : null;
    }
    final legs = optionLegs(market, side)
        .where((leg) => score(leg) != null).toList();
    legs.sort((a, b) {
      final difference = score(b)!.compareTo(score(a)!);
      return difference != 0
          ? difference : a.instrumentKey.compareTo(b.instrumentKey);
    });
    return List.unmodifiable(legs);
  }

  /// No underlying spot/reference is NOT permission to render hundreds of
  /// rows or invent an ATM. Start at the middle of the listed strike range
  /// until the user explicitly selects Show all.
  static List<CustomerFnoStrike> visibleStrikes(
      List<CustomerFnoStrike> strikes, num? reference,
      {bool showAll = false, int radius = 4}) {
    final window = radius * 2 + 1;
    if (showAll || strikes.length <= window) return strikes;
    var centre = strikes.length ~/ 2;
    if (reference != null) {
      for (var i = 0; i < strikes.length; i++) {
        if ((strikes[i].strikePrice - reference).abs() <
            (strikes[centre].strikePrice - reference).abs()) {
          centre = i;
        }
      }
    }
    final start = math.max(0, math.min(centre - radius, strikes.length - window));
    return strikes.sublist(start, start + window);
  }
}

class _MintTickFnoDiscoveryPageState extends State<MintTickFnoDiscoveryPage>
    with WidgetsBindingObserver {
  String? _roomId;
  String _section = 'Chain';
  String _optionSide = 'CE';
  String _smartlistKind = 'Highest OI';
  String _search = '';
  bool _showAllStrikes = false;
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
    });
    try {
      final capability = await widget.discovery.capability();
      if (!mounted || request != _generation) {
        return;
      }
      // Listed-contract/V2 reference browsing does not require an AUTO V3
      // socket. The backend enforces independent display rights, and returns
      // NO_FRESH_QUOTES while V3 is unavailable; never unlock order entry.
      final readEnabled = capability.displayRightsVerified &&
          capability.sourceReady;
      final discoveryEnabled = (capability.displayRightsVerified ||
              capability.contractDiscoveryVerified) &&
          capability.sourceReady;
      setState(() {
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

  static const int _windowRadius = 7;
  static const TextStyle _tabular =
      TextStyle(fontFeatures: [FontFeature.tabularFigures()]);

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final market = _market;
    final now = DateTime.now().toUtc();
    final all = market == null || !market.displayRightsVerified
        ? <CustomerFnoLeg>[]
        : MintTickFnoDiscoveryFacts.optionLegs(market, _optionSide)
            .where((leg) => leg.tradingSymbol.toLowerCase()
                .contains(_search.toLowerCase().trim()))
            .toList();
    final reference = market == null ? null
        : MintTickFnoDiscoveryFacts.atmStrike(market, now) ??
            market.underlyingReferenceSpot;
    final ranked = [...all]..sort((a, b) {
      final ak = _strikeForLeg(market!, a);
      final bk = _strikeForLeg(market, b);
      if (reference != null && ak != null && bk != null) {
        final distance = (ak - reference).abs()
            .compareTo((bk - reference).abs());
        if (distance != 0) return distance;
      }
      return (ak ?? 0).compareTo(bk ?? 0);
    });
    final listed = ranked.take(5).toList();
    final searchKeys = all.map((leg) => leg.instrumentKey).toSet();
    final smartlistPool = market == null
        ? <CustomerFnoLeg>[]
        : _smartlistKind == 'Highest OI'
            ? MintTickFnoDiscoveryFacts.highestOpenInterest(
                market, now, _optionSide)
            : MintTickFnoDiscoveryFacts.snapshotLeaders(
                market, now, _optionSide,
                metric: _smartlistKind == 'Top traded'
                    ? 'VOLUME' : 'OI_GAIN');
    final smartlist = smartlistPool
        .where((leg) => searchKeys.contains(leg.instrumentKey))
        .take(5).toList();
    final freshCount = market == null ? 0
        : MintTickFnoDiscoveryFacts.freshLegs(market, now).length;

    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      Row(children: [
        const Expanded(child: Text('Options',
          key: ValueKey('minttick-options-title'),
          style: TextStyle(fontSize: 22, fontWeight: FontWeight.w800))),
        _statusChip(context, market, freshCount > 0),
        IconButton(
          key: const ValueKey('fno-discovery-refresh'),
          tooltip: 'Refresh options',
          onPressed: _loading ? null : _refresh,
          icon: const Icon(Icons.refresh_rounded, size: 21),
        ),
      ]),
      const SizedBox(height: 4),
      _navigation(context),
      if (_loading) const LinearProgressIndicator(minHeight: 2),
      if (_error != null)
        Padding(
          padding: const EdgeInsets.symmetric(vertical: 7),
          child: Text(_error!,
            style: TextStyle(fontSize: 12, color: scheme.error))),
      if (!_readOnlyConfigured && _rightsStatus != null)
        Padding(
          padding: const EdgeInsets.only(top: 6),
          child: Text(_rightsStatus!.replaceAll('_', ' '),
            style: Theme.of(context).textTheme.labelSmall?.copyWith(
              color: scheme.onSurfaceVariant))),
      const SizedBox(height: 10),
      if (_section == 'Chain') ...[
        _chainFilters(context),
        const SizedBox(height: 8),
        _chain(context, market, now),
      ] else if (_section == 'Movers') ...[
        TextField(
          key: const ValueKey('minttick-options-search'),
          onChanged: (value) => setState(() => _search = value),
          decoration: InputDecoration(
            isDense: true,
            prefixIcon: const Icon(Icons.search_rounded, size: 20),
            hintText: 'Search contracts',
            filled: true,
            fillColor: scheme.surfaceContainerLow,
            border: OutlineInputBorder(
                borderRadius: BorderRadius.circular(10)),
            contentPadding: const EdgeInsets.symmetric(
                horizontal: 10, vertical: 9),
          ),
        ),
        const SizedBox(height: 8),
        Row(children: [
          _sideToggle('CE', 'Calls'),
          const SizedBox(width: 8),
          _sideToggle('PE', 'Puts'),
        ]),
        const SizedBox(height: 12),
        Text('Listed Options',
          style: Theme.of(context).textTheme.titleMedium?.copyWith(
            fontWeight: FontWeight.w800)),
        const SizedBox(height: 5),
        _optionsList(context, market, listed, now),
        const SizedBox(height: 16),
        Text('Options Smartlists',
          style: Theme.of(context).textTheme.titleMedium?.copyWith(
            fontWeight: FontWeight.w800)),
        const SizedBox(height: 5),
        SingleChildScrollView(scrollDirection: Axis.horizontal,
          child: Row(children: [
            for (final kind in const ['Highest OI', 'Top traded', 'OI gainers'])
              Padding(
                padding: const EdgeInsets.only(right: 6),
                child: ChoiceChip(
                  key: ValueKey('minttick-options-smartlist-${kind.toLowerCase().replaceAll(' ', '-')}'),
                  label: Text(kind),
                  selected: _smartlistKind == kind,
                  visualDensity: VisualDensity.compact,
                  onSelected: (_) => setState(() => _smartlistKind = kind),
                )),
          ])),
        if (smartlist.isNotEmpty)
          Padding(
            padding: const EdgeInsets.only(bottom: 4),
            child: Text('Provider snapshot · read only',
              style: Theme.of(context).textTheme.labelSmall?.copyWith(
                color: scheme.onSurfaceVariant))),
        _optionsList(context, market, smartlist, now,
          emptyText: _smartlistKind == 'Highest OI'
              ? 'Verified open-interest counts unavailable.'
              : _smartlistKind == 'Top traded'
                  ? 'Verified traded volume unavailable.'
                  : 'Verified OI changes unavailable.',
          compact: true),
        const SizedBox(height: 14),
        _movers(context, market, now),
      ] else if (_section == 'Universe') ...[
        MintTickFnoUniversePanel(
          repository: CustomerFnoUniverseRepository(widget.discovery.api)),
      ] else ...[
        _roomPicker(context, _room),
        const SizedBox(height: 8),
        _trade(context, _room),
      ],
      const SizedBox(height: 12),
    ]);
  }

  Widget _chainFilters(BuildContext context) {
    final c = Theme.of(context).colorScheme;
    return Row(children: [
      // The current verified chain endpoint supports NIFTY 50 only.
      // Do not render a selector that pretends other indices are available.
      Expanded(child: Container(
        key: const ValueKey('minttick-options-index-picker'),
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 12),
        decoration: BoxDecoration(
          color: c.surfaceContainerLow,
          borderRadius: BorderRadius.circular(9),
          border: Border.all(color: c.outlineVariant)),
        child: const Text('NIFTY 50', maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: TextStyle(fontWeight: FontWeight.w700, fontSize: 12)),
      )),
      const SizedBox(width: 8),
      Expanded(child: OutlinedButton(
        key: const ValueKey('minttick-options-expiry-picker'),
        onPressed: _expiries.isEmpty ? null : _showExpirySheet,
        style: OutlinedButton.styleFrom(
          padding: const EdgeInsets.symmetric(horizontal: 9),
          minimumSize: const Size(0, 43)),
        child: Row(children: [
          Expanded(child: Text(
            _expiry == null ? 'Select expiry' : _ymd(_expiry!),
            maxLines: 1, overflow: TextOverflow.ellipsis,
            style: const TextStyle(fontSize: 12))),
          const Icon(Icons.keyboard_arrow_down_rounded, size: 17),
        ]),
      )),
    ]);
  }

  Widget _sideToggle(String side, String label) {
    return ChoiceChip(
      key: ValueKey('minttick-options-${side.toLowerCase()}'),
      label: Text(label),
      selected: _optionSide == side,
      onSelected: (_) => setState(() => _optionSide = side),
      visualDensity: VisualDensity.compact,
      labelPadding: const EdgeInsets.symmetric(horizontal: 2),
    );
  }

  void _showExpirySheet() {
    showModalBottomSheet<void>(
      context: context,
      builder: (sheetContext) => SafeArea(child: ListView(
        shrinkWrap: true,
        children: [
          const ListTile(title: Text('Expiry date')),
          for (final date in _expiries)
            ListTile(
              key: ValueKey('minttick-options-expiry-${date.toIso8601String().substring(0,10)}'),
              title: Text(_ymd(date)),
              leading: Icon(_expiry == date
                  ? Icons.radio_button_checked
                  : Icons.radio_button_unchecked),
              onTap: () {
                Navigator.pop(sheetContext);
                _loadMarket(date);
              },
            ),
        ],
      )),
    );
  }

  Widget _optionsList(BuildContext context, CustomerFnoMarket? market,
      List<CustomerFnoLeg> legs, DateTime now,
      {String emptyText = 'No verified option contracts available.', bool compact = false}) {
    final scheme = Theme.of(context).colorScheme;
    final style = Theme.of(context).textTheme;
    if (legs.isEmpty) {
      return Padding(
        padding: const EdgeInsets.symmetric(vertical: 16),
        child: Text(emptyText, style: style.bodyMedium?.copyWith(
          color: scheme.onSurfaceVariant)),
      );
    }
    final atm = market == null ? null
        : MintTickFnoDiscoveryFacts.atmStrike(market, now);
    return Column(children: [
      Padding(
        padding: const EdgeInsets.only(bottom: 2),
        child: Row(children: [
          const Expanded(flex: 5, child: Text('Option')),
          Expanded(flex: 3, child: Text(
            compact && _smartlistKind == 'Top traded' ? 'Volume'
              : compact && _smartlistKind == 'OI gainers' ? 'ΔOI' : 'OI',
            textAlign: TextAlign.right,
            style: style.bodySmall?.copyWith(color: scheme.onSurfaceVariant))),
          Expanded(flex: 3, child: Text('LTP', textAlign: TextAlign.right,
            style: style.bodySmall?.copyWith(color: scheme.onSurfaceVariant))),
        ]),
      ),
      for (final leg in legs)
        InkWell(
          key: ValueKey(compact
              ? 'minttick-options-smartlist-contract-${leg.instrumentKey}'
              : 'minttick-options-contract-${leg.instrumentKey}'),
          onTap: market == null ? null : () => _showLeg(context, market, leg, now),
          child: Container(
            padding: const EdgeInsets.symmetric(vertical: 12),
            decoration: BoxDecoration(border: Border(bottom: BorderSide(
                color: scheme.outlineVariant.withValues(alpha: .52)))),
            child: Row(children: [
              Expanded(flex: 5, child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(leg.tradingSymbol, maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: style.bodyMedium?.copyWith(fontWeight: FontWeight.w700)),
                  const SizedBox(height: 4),
                  Row(children: [
                    Text('NFO', style: style.labelSmall?.copyWith(
                      color: scheme.onSurfaceVariant)),
                    if (atm != null) ...[
                      const SizedBox(width: 5),
                      Text(_strikeBadge(leg, atm),
                        style: style.labelSmall?.copyWith(
                          color: scheme.onSurfaceVariant)),
                    ],
                  ]),
                ],
              )),
              Expanded(flex: 3, child: Text(
                market != null && market.displayRightsVerified
                    ? compact && _smartlistKind == 'Top traded'
                        ? _count(_chainSnapshot(market, leg, now)?.volume)
                        : compact && _smartlistKind == 'OI gainers'
                            ? _count(_chainSnapshot(market, leg, now)
                                ?.openInterestChange)
                            : _count(_displayOpenInterest(market, leg, now))
                    : '—',
                textAlign: TextAlign.right,
                style: style.bodyMedium?.copyWith(fontWeight: FontWeight.w700),
              )),
              Expanded(flex: 3, child: Column(
                crossAxisAlignment: CrossAxisAlignment.end,
                children: [
                  Text(
                    market == null ? '—' : _displayQuote(market, leg, now)
                        ? _amount(leg.ltp)
                        : _amount(_chainSnapshot(market, leg, now)?.ltp),
                    style: style.bodyLarge?.copyWith(fontWeight: FontWeight.w800,
                      color: _fresh(market!, leg, now)
                          ? (leg.priceChangePct ?? 0) < 0 ? scheme.error : scheme.primary
                          : scheme.onSurfaceVariant)),
                  if (market != null &&
                      (_displayQuote(market, leg, now) ||
                       _chainSnapshot(market, leg, now) != null))
                    Text(_fresh(market, leg, now)
                        ? _changeText(leg.priceChangePct)
                        : _displayQuote(market, leg, now)
                          ? 'Last · ${_shortTime(leg.exchangeAt)}'
                          : 'Chain · ${_shortTime(market.snapshotAt)}',
                      style: style.labelSmall?.copyWith(
                        color: scheme.onSurfaceVariant)),
                ],
              )),
            ]),
          ),
        ),
    ]);
  }

  static num? _strikeForLeg(CustomerFnoMarket market, CustomerFnoLeg leg) {
    for (final row in market.strikes) {
      if (row.call?.instrumentKey == leg.instrumentKey ||
          row.put?.instrumentKey == leg.instrumentKey) {
        return row.strikePrice;
      }
    }
    return null;
  }

  static String _strikeBadge(CustomerFnoLeg leg, num atm) {
    final match = RegExp(r'(\d+(?:\.\d+)?)\s*(CE|PE)$').firstMatch(
      leg.tradingSymbol);
    if (match == null) return '';
    final strike = num.tryParse(match.group(1)!);
    if (strike == null) return '';
    if (strike == atm) return 'ATM';
    final inMoney = leg.side == 'CE' ? strike < atm : strike > atm;
    return inMoney ? 'ITM' : 'OTM';
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
        for (final name in const ['Chain', 'Movers', 'Universe', 'Trade'])
          Expanded(child: InkWell(
            key: ValueKey('fno-discovery-tab-${name.toLowerCase()}'),
            onTap: () => setState(() => _section = name),
            child: Container(
              alignment: Alignment.center,
              decoration: BoxDecoration(border: Border(bottom: BorderSide(
                color: _section == name ? c.primary : c.outlineVariant,
                width: _section == name ? 2.5 : 1))),
              child: Text(name, maxLines: 1,
                style: TextStyle(fontSize: 13,
                  color: _section == name ? c.onSurface : c.onSurfaceVariant,
                  fontWeight: _section == name ? FontWeight.w800 : FontWeight.w500)),
            ),
          )),
      ]),
    );
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

  Widget _chain(BuildContext context, CustomerFnoMarket? market,
      DateTime now) {
    final c = Theme.of(context).colorScheme;
    final type = Theme.of(context).textTheme;
    if (market == null) {
      return _panel(context, 'NIFTY option chain', 'Exchange-listed contracts',
        const Text('No verified option contracts available for this expiry.'));
    }
    final atm = MintTickFnoDiscoveryFacts.atmStrike(market, now);
    final near = atm ?? market.underlyingReferenceSpot;
    final strikes = MintTickFnoDiscoveryFacts.visibleStrikes(
      market.strikes, near, showAll: _showAllStrikes,
      radius: _windowRadius);
    final hiddenCount = market.strikes.length - strikes.length;

    return _panel(context, 'NIFTY option chain',
      '${_ymd(market.expiry)} · ${market.strikes.length} listed strikes',
      Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        if (near != null)
          Text(atm == null
              ? 'Near ${_strike(near)} · provider reference'
              : 'ATM ≈ ${_strike(atm)} · estimated from fresh option prices',
            style: type.labelSmall?.copyWith(color: c.onSurfaceVariant)),
        Padding(
          padding: const EdgeInsets.only(top: 3, bottom: 10),
          child: Text(
            market.status == 'MARKET_CLOSED'
                ? 'Market closed · displayed prices are not live'
                : market.hasFreshQuotes
                    ? 'Fresh prices marked by color; other values may be snapshots'
                    : 'Reference prices · not executable quotes',
            style: type.labelSmall?.copyWith(color: c.onSurfaceVariant),
          ),
        ),
        // All five columns fit the available phone width. More metrics
        // belong in the contract details sheet, not off-screen columns.
        _chainHeader(context),
        for (final strike in strikes)
          _chainRow(context, market, strike, atm, now),
        if (market.strikes.length > strikes.length || _showAllStrikes)
          Align(alignment: Alignment.centerRight,
            child: TextButton(
              key: const ValueKey('fno-chain-expand-strikes'),
              onPressed: () => setState(() => _showAllStrikes = !_showAllStrikes),
              child: Text(_showAllStrikes
                  ? 'Show near ATM'
                  : 'Show all ${market.strikes.length} strikes ($hiddenCount more)'),
            )),
      ]));
  }

  Widget _chainHeader(BuildContext context) {
    final c = Theme.of(context).colorScheme;
    final style = Theme.of(context).textTheme.labelSmall?.copyWith(
      color: c.onSurfaceVariant, fontWeight: FontWeight.w700, fontSize: 10);
    Widget heading(String label, int flex, {bool center = false}) =>
        Expanded(flex: flex, child: Text(label,
          textAlign: center ? TextAlign.center : TextAlign.right,
          maxLines: 1, overflow: TextOverflow.ellipsis,
          style: style));
    return Column(children: [
      Padding(
        padding: const EdgeInsets.only(bottom: 4),
        child: Row(children: [
          Expanded(flex: 5, child: Text('CALL · CE',
            textAlign: TextAlign.center, style: style)),
          Expanded(flex: 3, child: Text('STRIKE',
            textAlign: TextAlign.center,
            style: style?.copyWith(color: c.onSurface))),
          Expanded(flex: 5, child: Text('PUT · PE',
            textAlign: TextAlign.center, style: style)),
        ]),
      ),
      Row(children: [
        heading('OI', 2),
        heading('LTP', 3),
        heading('STRIKE', 3, center: true),
        heading('LTP', 3),
        heading('OI', 2),
      ]),
      Divider(color: c.outlineVariant, height: 11),
    ]);
  }

  Widget _chainRow(BuildContext context, CustomerFnoMarket market,
      CustomerFnoStrike strike, num? atm, DateTime now) {
    final c = Theme.of(context).colorScheme;
    final isAtm = atm != null && strike.strikePrice == atm;
    final callItm = atm != null && strike.strikePrice < atm;
    final putItm = atm != null && strike.strikePrice > atm;
    final call = strike.call;
    final put = strike.put;
    return Container(
      decoration: BoxDecoration(
        color: isAtm ? c.primary.withValues(alpha: .10) : null,
        border: Border(bottom: BorderSide(
          color: c.outlineVariant.withValues(alpha: .42), width: .5))),
      child: Row(children: [
        Expanded(flex: 2, child: _chainCell(context, market, call, now,
          text: call == null ? '—' : _shortCount(
            _displayOpenInterest(market, call, now)), itm: callItm)),
        Expanded(flex: 3, child: _chainCell(context, market, call, now,
          text: _chainLtp(market, call, now),
          strong: true, itm: callItm)),
        Expanded(flex: 3, child: Padding(
          padding: const EdgeInsets.symmetric(vertical: 9, horizontal: 1),
          child: Column(mainAxisSize: MainAxisSize.min, children: [
            if (isAtm) Text('ATM', style: TextStyle(color: c.primary,
              fontSize: 9, fontWeight: FontWeight.w800)),
            Text(_strike(strike.strikePrice),
              textAlign: TextAlign.center, maxLines: 1,
              style: const TextStyle(
                fontSize: 12, fontWeight: FontWeight.w800,
                fontFeatures: [FontFeature.tabularFigures()])),
          ]),
        )),
        Expanded(flex: 3, child: _chainCell(context, market, put, now,
          text: _chainLtp(market, put, now),
          strong: true, itm: putItm)),
        Expanded(flex: 2, child: _chainCell(context, market, put, now,
          text: put == null ? '—' : _shortCount(
            _displayOpenInterest(market, put, now)), itm: putItm)),
      ]),
    );
  }

  String _chainLtp(CustomerFnoMarket market, CustomerFnoLeg? leg,
      DateTime now) {
    if (leg == null) return '—';
    if (_displayQuote(market, leg, now)) return _amount(leg.ltp);
    return _amount(_chainSnapshot(market, leg, now)?.ltp);
  }

  Widget _chainCell(BuildContext context, CustomerFnoMarket market,
      CustomerFnoLeg? leg, DateTime now, {required String text,
      bool strong = false, bool itm = false}) {
    final c = Theme.of(context).colorScheme;
    final fresh = leg != null && _fresh(market, leg, now);
    final color = strong && fresh
        ? (leg.priceChangePct ?? 0) < 0 ? c.error : c.primary
        : strong ? c.onSurface : c.onSurfaceVariant;
    return InkWell(
      onTap: leg == null ? null : () => _showLeg(context, market, leg, now),
      child: Container(
        color: itm ? c.primary.withValues(alpha: .045) : null,
        alignment: Alignment.centerRight,
        padding: const EdgeInsets.symmetric(horizontal: 2, vertical: 10),
        child: Text(text,
          textAlign: TextAlign.right, maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: _tabular.copyWith(
            fontSize: 11.5, fontWeight:
              strong ? FontWeight.w800 : FontWeight.w500, color: color)),
      ),
    );
  }

  void _showLeg(BuildContext context, CustomerFnoMarket market,
      CustomerFnoLeg leg, DateTime now) {
    final fresh = _fresh(market, leg, now);
    final visible = _displayQuote(market, leg, now);
    // Chain snapshot values are separate from verified V3 exchange trades.
    final chain = _chainSnapshot(market, leg, now);
    final chainOnly = !visible && chain != null;
    final type = Theme.of(context).textTheme;
    final c = Theme.of(context).colorScheme;
    final pct = fresh ? leg.priceChangePct : null;
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
                : visible
                    ? 'Last verified trade · ${_shortTime(leg.exchangeAt)}'
                    : chainOnly
                        ? 'Upstox chain snapshot · read only'
                        : 'No verified trade available',
              style: type.labelSmall?.copyWith(
                color: fresh ? c.primary : c.onSurfaceVariant,
                fontWeight: FontWeight.w700)),
            const Divider(height: 22),
            _sheetRow(sheetContext, chainOnly ? 'LTP · chain snapshot' : 'LTP',
                _price(visible ? leg.ltp : chain?.ltp)),
            _sheetRow(sheetContext, 'Change since previous close',
                pct == null ? '—' : _changeText(pct)),
            _sheetRow(sheetContext,
                fresh && leg.bid != null ? 'Bid' : 'Bid · chain snapshot',
                _price(fresh && leg.bid != null ? leg.bid : chain?.bid)),
            _sheetRow(sheetContext,
                fresh && leg.ask != null ? 'Ask' : 'Ask · chain snapshot',
                _price(fresh && leg.ask != null ? leg.ask : chain?.ask)),
            _sheetRow(sheetContext, 'Previous close',
                _price(visible ? leg.previousClose : null)),
            _sheetRow(sheetContext,
                fresh && leg.openInterest != null
                    ? 'Open interest'
                    : chain?.openInterest != null
                        ? 'OI · chain snapshot'
                        : visible ? 'OI · last trade' : 'Open interest',
                _count(_displayOpenInterest(market, leg, now))),
            _sheetRow(sheetContext,
                fresh && leg.volume != null
                    ? 'Traded volume · V3'
                    : 'Traded volume · chain snapshot',
                _count(fresh && leg.volume != null
                    ? leg.volume : chain?.volume)),
            _sheetRow(sheetContext, 'Last traded quantity · V3',
                _count(fresh ? leg.lastTradedQuantity : null)),
            if (fresh && leg.bid != null && leg.ask != null) ...[
              _sheetRow(sheetContext, 'Best bid quantity · V3',
                  _count(leg.bidQuantity)),
              _sheetRow(sheetContext, 'Best ask quantity · V3',
                  _count(leg.askQuantity)),
            ],
            _sheetRow(sheetContext,
                fresh && leg.iv != null ? 'IV' : 'IV · chain snapshot',
                _decimal(fresh && leg.iv != null ? leg.iv : chain?.iv)),
            _sheetRow(sheetContext,
                fresh && leg.delta != null ? 'Delta' : 'Delta · chain snapshot',
                _decimal(fresh && leg.delta != null ? leg.delta : chain?.delta, places: 4)),
            _sheetRow(sheetContext,
                fresh && leg.gamma != null ? 'Gamma' : 'Gamma · chain snapshot',
                _decimal(fresh && leg.gamma != null ? leg.gamma : chain?.gamma, places: 6)),
            _sheetRow(sheetContext,
                fresh && leg.theta != null ? 'Theta' : 'Theta · chain snapshot',
                _decimal(fresh && leg.theta != null ? leg.theta : chain?.theta)),
            _sheetRow(sheetContext,
                fresh && leg.vega != null ? 'Vega' : 'Vega · chain snapshot',
                _decimal(fresh && leg.vega != null ? leg.vega : chain?.vega)),
            const Divider(height: 22),
            _sheetRow(sheetContext, 'Exchange time',
                visible ? _time(leg.exchangeAt) : '—'),
            _sheetRow(sheetContext, 'Observed',
                visible ? _time(leg.observedAt) : '—'),
            if (chain != null)
              _sheetRow(sheetContext, 'Chain retrieved',
                  _time(market.snapshotAt)),
            const SizedBox(height: 6),
            Text('Instrument ${leg.instrumentKey}',
              style: type.labelSmall?.copyWith(color: c.onSurfaceVariant)),
            const SizedBox(height: 12),
            if (chain != null) ...[
              Text('Chain snapshot values are indicative and cannot be used '
                  'as executable quotes or proof of a recent trade.',
                style: type.bodySmall),
              const SizedBox(height: 8),
            ],
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

  // The server includes retained prices only when display and snapshot
  // retention rights are verified; these never imply quoteFresh/order readiness.
  /// Provider HTTP retrieval time is not an exchange trade timestamp.
  static CustomerFnoChainSnapshotIndicator? _chainSnapshot(
      CustomerFnoMarket market, CustomerFnoLeg leg, DateTime now) {
    final at = market.snapshotAt;
    if (!market.displayRightsVerified || at == null || at.isAfter(now) ||
        now.difference(at) > const Duration(minutes: 5)) {
      return null;
    }
    return market.chainSnapshotIndicators[leg.instrumentKey];
  }

  // Prefer fresh V3 OI, then recent V2 reference OI, then independently
  // authorized retained V3 OI. Never prefer last week's OI over newer chain OI.
  static int? _displayOpenInterest(
      CustomerFnoMarket market, CustomerFnoLeg leg, DateTime now) {
    if (!market.displayRightsVerified) return null;
    if (_fresh(market, leg, now) && leg.openInterest != null) {
      return leg.openInterest;
    }
    final chainOi = _chainSnapshot(market, leg, now)?.openInterest;
    if (chainOi != null) return chainOi;
    return _displayQuote(market, leg, now) ? leg.openInterest : null;
  }

  static bool _displayQuote(CustomerFnoMarket market, CustomerFnoLeg leg,
          DateTime now) =>
      market.displayRightsVerified && leg.ltp != null && leg.ltp! > 0 &&
      (_fresh(market, leg, now) ||
       (!leg.quoteFresh && leg.exchangeAt != null &&
        !leg.exchangeAt!.isAfter(now) &&
        now.difference(leg.exchangeAt!) <= const Duration(days: 7)));

  static String _shortTime(DateTime? value) {
    if (value == null) return '—';
    final local = value.toLocal();
    return '${local.day.toString().padLeft(2, '0')}/'
        '${local.month.toString().padLeft(2, '0')} '
        '${local.hour.toString().padLeft(2, '0')}:'
        '${local.minute.toString().padLeft(2, '0')}';
  }

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

  static String _decimal(num? n, {int places = 2}) =>
      n == null || !n.isFinite ? '—' : n.toStringAsFixed(places);

  static String _ymd(DateTime d) => '${d.day.toString().padLeft(2, '0')} '
      '${_month(d.month)} ${d.year}';

  static String _month(int m) => const [
    'Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'
  ][m - 1];

  static String _time(DateTime? date) =>
      date == null ? 'unknown' : '${date.toUtc().hour.toString().padLeft(2, '0')}:'
          '${date.toUtc().minute.toString().padLeft(2, '0')} UTC';
}

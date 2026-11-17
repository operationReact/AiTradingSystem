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
    required this.rooms,
    required this.discovery,
    required this.onOpenRoom,
    required this.onCreateRoom,
    super.key,
  });

  final TradingRoomDraftStore rooms;
  final CustomerFnoDiscoveryRepository discovery;
  final ValueChanged<TradingRoomDraft> onOpenRoom;
  final VoidCallback onCreateRoom;

  @override
  State<MintTickFnoDiscoveryPage> createState() => _MintTickFnoDiscoveryPageState();
}

/// F&O data facts are derived exclusively from a source market observation.
class MintTickFnoDiscoveryFacts {
  static List<CustomerFnoLeg> optionLegs(
      CustomerFnoMarket market, String side) {
    if (side != 'CE' && side != 'PE') {
      return const [];
    }
    return List.unmodifiable([
      for (final strike in market.strikes)
        if (side == 'CE' && strike.call != null) strike.call!,
      for (final strike in market.strikes)
        if (side == 'PE' && strike.put != null) strike.put!,
    ]);
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

class _MintTickFnoDiscoveryPageState extends State<MintTickFnoDiscoveryPage> {
  String? _roomId;
  String _section = 'Explore';
  String _side = 'CE';
  bool _loading = false;
  String? _error;
  int _generation = 0;
  List<DateTime> _expiries = const [];
  DateTime? _expiry;
  CustomerFnoMarket? _market;
  bool _readOnlyConfigured = false;
  String? _rightsStatus;

  List<TradingRoomDraft> get _availableRooms => widget.rooms.drafts
      .where((r) => (r.marketScope == 'FNO' || r.marketScope == 'ALL') &&
          (r.mode == 'PAPER' || r.mode == 'LIVE')).toList();

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
    widget.rooms.addListener(_onRoomsChanged);
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) {
        return;
      }
      unawaited(_refresh());
      if (!widget.rooms.loaded) {
        unawaited(widget.rooms.load());
      } else {
        _onRoomsChanged();
      }
    });
  }

  @override
  void dispose() {
    _generation++;
    widget.rooms.removeListener(_onRoomsChanged);
    super.dispose();
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
      final readEnabled = capability.displayRightsVerified &&
          capability.feedConfigured && capability.sourceReady;
      setState(() {
        _rightsStatus = capability.status;
        _readOnlyConfigured = readEnabled;
      });
      if (!readEnabled) {
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
      await _loadMarket(dates.first, expected: request);
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

  Future<void> _loadMarket(DateTime expiry, {int? expected}) async {
    if (!_expiries.contains(expiry)) {
      return;
    }
    final request = expected ?? ++_generation;
    if (expected == null) {
      setState(() {
        _loading = true;
        _error = null;
        _market = null;
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
        setState(() => _error =
            'Unable to verify listed options or exchange-timed quotes.');
      }
    } finally {
      if (mounted && request == _generation) {
        setState(() => _loading = false);
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
        market.displayRightsVerified;
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
          '${(_rightsStatus ?? 'Data not yet verified').replaceAll('_', ' ')}.',
          action: room == null ? null : 'Open terminal',
          onTap: room == null ? null : () => widget.onOpenRoom(room)),
      if (_section == 'Explore') ...[
        _marketIntro(context, room, market),
        _expirySection(context),
        _watchlist(context, market, compact: true),
        _movers(context, market),
        _unavailable(context, 'Futures chain',
          'Futures prices and volumes require a verified NSE F&O futures feed. '
          'Only supported NIFTY options are shown above.'),
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
            ? 'No exchange-timed option snapshot verified'
            : 'Contract snapshot: ${_time(market.snapshotAt)} · '
              'Upstox V3 quotes are independently checked',
          style: Theme.of(context).textTheme.bodySmall),
        if (room != null) Align(alignment: Alignment.centerRight,
          child: TextButton.icon(
            onPressed: () => widget.onOpenRoom(room),
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
    final list = market == null ? <CustomerFnoLeg>[] :
        MintTickFnoDiscoveryFacts.optionLegs(market, _side);
    final visible = compact ? list.take(6).toList() : list;
    return Padding(padding: const EdgeInsets.only(bottom: 14),
      child: MintTickSurface(child: Column(
        crossAxisAlignment: CrossAxisAlignment.start, children: [
          _heading(compact ? 'Options watchlist' : 'NIFTY option chain',
              'Source-listed · ${list.length} ${_side == 'CE' ? 'calls' : 'puts'}'),
          const SizedBox(height: 9),
          Wrap(spacing: 8, children: [
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
            _optionRow(context, leg),
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

  Widget _optionRow(BuildContext context, CustomerFnoLeg leg) {
    final c = Theme.of(context).colorScheme;
    final type = Theme.of(context).textTheme;
    final verified = _market?.hasFreshQuotes == true &&
        leg.quoteFresh && leg.exchangeAt != null && leg.observedAt != null;
    final pct = verified ? leg.priceChangePct : null;
    final price = verified ? leg.ltp : null;
    return Padding(padding: const EdgeInsets.symmetric(vertical: 7),
      child: Row(children: [
        Container(width: 38, height: 38,
          alignment: Alignment.center,
          decoration: BoxDecoration(color: c.surfaceContainerHighest,
              borderRadius: BorderRadius.circular(12)),
          child: Text(leg.side, style: TextStyle(color: c.primary,
              fontWeight: FontWeight.w800, fontSize: 12))),
        const SizedBox(width: 10),
        Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('NIFTY  ${leg.tradingSymbol}', maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: type.bodyMedium?.copyWith(fontWeight: FontWeight.w700)),
            Text('Bid ${_price(verified ? leg.bid : null)}  ·  '
                'Ask ${_price(verified ? leg.ask : null)}',
              style: type.labelSmall?.copyWith(color: c.onSurfaceVariant)),
            if (verified) Text('Exchange ${_time(leg.exchangeAt)}',
              style: type.labelSmall?.copyWith(color: c.onSurfaceVariant)),
          ])),
        Column(crossAxisAlignment: CrossAxisAlignment.end, children: [
          Text(_price(price), style: type.titleSmall?.copyWith(
            fontWeight: FontWeight.w800,
            color: pct == null ? c.onSurface :
                pct >= 0 ? c.primary : c.error)),
          Text(pct == null ? 'No verified change'
                : '${pct >= 0 ? '+' : ''}${pct.toStringAsFixed(2)}%',
            style: type.labelSmall?.copyWith(color: c.onSurfaceVariant)),
        ]),
      ]),
    );
  }

  Widget _movers(BuildContext context, CustomerFnoMarket? market) {
    final top = market == null ? <CustomerFnoLeg>[] :
        MintTickFnoDiscoveryFacts.previousCloseMovers(market).take(4).toList();
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
    final legs = market == null ? <CustomerFnoLeg>[] : [
      ...MintTickFnoDiscoveryFacts.optionLegs(market, 'CE'),
      ...MintTickFnoDiscoveryFacts.optionLegs(market, 'PE'),
    ];
    final valid = legs.where((l) => market?.hasFreshQuotes == true &&
        l.quoteFresh && l.exchangeAt != null && l.observedAt != null &&
        l.openInterest != null).toList();
    return MintTickSurface(child: Column(
      crossAxisAlignment: CrossAxisAlignment.start, children: [
        _heading('Open interest & Greeks', 'Verified same-frame V3 only'),
        const SizedBox(height: 10),
        if (valid.isEmpty)
          const Text('No verified OI/Greeks observations available.')
        else for (final leg in valid.take(20)) ...[
          Text(leg.tradingSymbol,
            style: Theme.of(context).textTheme.bodyMedium
                ?.copyWith(fontWeight: FontWeight.w800)),
          Text('OI ${leg.openInterest}   ·   Delta ${_decimal(leg.delta)}   '
              '·   Theta ${_decimal(leg.theta)}   '
              '·   IV ${leg.iv == null ? '—' : (leg.iv! * 100).toStringAsFixed(2)}%',
            style: Theme.of(context).textTheme.bodySmall),
          const SizedBox(height: 12),
        ],
        const Divider(),
        const Text('Historical charts and intraday momentum stay unavailable '
            'until independently verified candle feeds are integrated.'),
      ]),
    );
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
              : () => widget.onOpenRoom(room),
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
      date == null ? 'unknown' : '${date.toLocal().hour.toString().padLeft(2, '0')}:'
          '${date.toLocal().minute.toString().padLeft(2, '0')} local';
}

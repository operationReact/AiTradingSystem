import 'package:flutter/material.dart';

import '../../../data/instrument_repository.dart';
import '../data/customer_market_repository.dart';
import 'minttick_room_candlestick_desk.dart';

/// Strategy family that drives the equity Trade layout and chart default.
enum _Family { intraday, positional }

/// Resolved strategy framing for a room's `focus`. Every supported focus gets
/// its own title, summary, chart interval and layout family; unknown or
/// deferred values ('Help me choose') fall back to a neutral daily view and are
/// never silently relabelled as Swing.
class _Strategy {
  const _Strategy(this.title, this.note, this.family, this.frame);
  final String title;
  final String note;
  final _Family family;
  final int frame; // index into the candlestick desk frames: 0=5m,1=1H,2=1D

  bool get isIntraday => family == _Family.intraday;
}

_Strategy _strategyFor(String focus) => switch (focus) {
  'Intraday' => const _Strategy('INTRADAY',
      'Session speed · live price, day range & volume', _Family.intraday, 0),
  'Swing' => const _Strategy('SWING',
      'Swing horizon · 52-week position, watch & holdings', _Family.positional, 2),
  'Long-term' => const _Strategy('LONG-TERM',
      'Investing horizon · 52-week position, watch & holdings', _Family.positional, 2),
  _ => const _Strategy('EQUITY',
      'Set a room focus to tune this view · daily context shown',
      _Family.positional, 2),
};

/// How the displayed price was sourced. The UI shows exactly one, with its own
/// timestamp, and never presents a LIVE badge beside a price from another read.
enum _Basis { live, lastRecorded, snapshot, none }

class _Priced {
  const _Priced(this.price, this.basis, this.at, this.changePct, this.changeAbs,
      {this.status, this.refreshing = false});
  /// A refresh is pending; [basis] is the prior tick, shown as non-live.
  final bool refreshing;
  final num? price;
  final _Basis basis;
  final DateTime? at;
  final num? changePct;
  final num? changeAbs;

  /// The raw quote status when no price is available (MARKET_CLOSED, STALE, …).
  final String? status;
}

/// Strategy-aware equity trading desk for a Trading Room's Trade tab.
///
/// The same verified market APIs drive two deliberately different workspaces:
///
/// * Swing / Long-term rooms prioritise the holding horizon — a daily chart,
///   the 52-week position of the current price, watchlist and holding status,
///   and movement versus the previous close.
/// * Intraday rooms prioritise fast in-session decisions — a 5-minute chart,
///   live price and change, the day's open/high/low range, traded volume, and
///   an explicit session / data-freshness indicator.
///
/// This desk is the single price authority for the Trade tab: it fetches one
/// quote and one day-detail snapshot and shows them with their own timestamps.
/// The embedded chart renders with `showQuote: false` so two independently
/// fetched quotes can never display different prices for the same instrument.
/// Every value is server-sourced; a missing field renders a clear unavailable
/// state and never a fabricated number. There is no streaming feed on this
/// path, so prices are labelled LIVE / LAST RECORDED / SNAPSHOT with the time
/// they were observed and refreshed only on demand — never silently polled.
class MintTickRoomEquityDesk extends StatefulWidget {
  const MintTickRoomEquityDesk({
    required this.market,
    required this.focus,
    required this.initialInstrument,
    required this.onInstrument,
    required this.holdingFor,
    super.key,
  });

  final HttpCustomerMarketRepository market;

  /// Room horizon: 'Intraday', 'Swing', 'Long-term', 'Help me choose' or a
  /// legacy value. Resolved through [_strategyFor]; unknown values fall back.
  final String focus;

  final InstrumentRecord? initialInstrument;

  /// Bubbles the active instrument up so the order sheet opens pre-filled.
  final ValueChanged<InstrumentRecord?> onInstrument;

  /// Open quantity currently held for an instrument (0 when none). Lets the
  /// swing desk surface position/watch context without a second positions read.
  final int Function(int instrumentId) holdingFor;

  @override
  State<MintTickRoomEquityDesk> createState() => _MintTickRoomEquityDeskState();
}

class _MintTickRoomEquityDeskState extends State<MintTickRoomEquityDesk> {
  InstrumentRecord? _instrument;
  CustomerDayDetail? _detail;
  CustomerLiveQuote? _quote;
  bool _detailLoading = false;
  bool _detailFailed = false;
  /// The most recent session-quote request threw (network/decode). A prior
  /// LIVE value is demoted to last-recorded; it is never kept labelled LIVE.
  bool _feedError = false;
  /// A session-quote request is in flight. While true, a prior tick is never
  /// labelled LIVE: it is shown as "Refreshing · last tick HH:MM".
  bool _refreshing = false;
  bool? _watched;
  bool _watchBusy = false;
  int _generation = 0;

  _Strategy get _strategy => _strategyFor(widget.focus);

  @override
  void initState() {
    super.initState();
    _instrument = widget.initialInstrument;
    if (_instrument != null) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) _loadContext(_instrument!);
      });
    }
  }

  @override
  void dispose() {
    ++_generation;
    super.dispose();
  }

  void _onInstrument(InstrumentRecord? selected) {
    widget.onInstrument(selected);
    if (selected == null || selected.id == _instrument?.id) return;
    setState(() {
      _instrument = selected;
      _detail = null;
      _quote = null;
      _detailFailed = false;
      _feedError = false;
      _watched = null;
    });
    _loadContext(selected);
  }

  Future<void> _loadContext(InstrumentRecord instrument) async {
    final request = ++_generation;
    setState(() {
      _detailLoading = true;
      _detailFailed = false;
    });
    // The session quote and the day snapshot load independently: one failing
    // must never blank the other, and neither is ever estimated from the other.
    _loadSession(instrument, request);
    if (!_strategy.isIntraday) _loadWatchState(instrument, request);
    try {
      final detail = await widget.market.dayDetail(instrument.id);
      if (!mounted || request != _generation) return;
      setState(() => _detail = detail);
    } catch (_) {
      if (mounted && request == _generation) {
        setState(() {
          _detail = null;
          _detailFailed = true;
        });
      }
    } finally {
      if (mounted && request == _generation) {
        setState(() => _detailLoading = false);
      }
    }
  }

  Future<void> _loadSession(InstrumentRecord instrument, int request) async {
    if (mounted) setState(() => _refreshing = true);
    try {
      final quote = await widget.market.liveQuote(instrument.id);
      if (!mounted || request != _generation) return;
      if (quote.instrumentId == instrument.id) {
        // A fresh response — of any status — always replaces the prior quote,
        // so a previous LIVE tick can never survive a refresh that now returns
        // STALE / MARKET_CLOSED / UNAVAILABLE.
        setState(() {
          _quote = quote;
          _feedError = false;
          _refreshing = false;
        });
      }
    } catch (_) {
      if (!mounted || request != _generation) return;
      // The refresh failed. Never keep a prior LIVE value labelled LIVE: demote
      // it to its original last-recorded timestamp and non-live status, or drop
      // it entirely if it cannot be demoted.
      setState(() {
        _quote = _quote?.asLastRecorded();
        _feedError = true;
        _refreshing = false;
      });
    }
  }

  /// A human-readable live-feed condition to surface alongside a non-live price
  /// (e.g. a snapshot). Null when the feed is healthy LIVE or not yet loaded.
  String? _feedLabel() {
    if (_feedError) return 'Live feed unavailable · showing last known';
    return switch (_quote?.status) {
      'MARKET_CLOSED' => 'Market closed · live ticks paused',
      'STALE' => 'Live feed stale',
      'CONNECTING' => 'Live feed connecting…',
      'RECONNECTING' => 'Live feed reconnecting…',
      'BROKER_CONNECTION_REQUIRED' => 'Broker feed offline',
      'UNAVAILABLE' => 'Live feed unavailable',
      _ => null,
    };
  }

  Future<void> _loadWatchState(InstrumentRecord instrument, int request) async {
    try {
      final list = await widget.market.watchlist();
      if (!mounted || request != _generation) return;
      setState(() => _watched =
          list.items.any((item) => item.instrumentId == instrument.id));
    } catch (_) {
      if (mounted && request == _generation) {
        setState(() => _watched = null);
      }
    }
  }

  Future<void> _refresh() async {
    final instrument = _instrument;
    if (instrument != null) await _loadContext(instrument);
  }

  Future<void> _toggleWatch() async {
    final instrument = _instrument;
    final current = _watched;
    if (instrument == null || current == null || _watchBusy) return;
    setState(() => _watchBusy = true);
    try {
      if (current) {
        await widget.market.remove(instrument.id);
      } else {
        await widget.market.add(instrument.id);
      }
      if (mounted) setState(() => _watched = !current);
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
          content: Text('Watchlist could not be updated. Try again.')));
      }
    } finally {
      if (mounted) setState(() => _watchBusy = false);
    }
  }

  /// Resolves the one price to display from a single coherent source, with the
  /// change measured only against the verified previous close.
  _Priced _resolvePrice() {
    final quote = _quote;
    final detail = _detail;
    final prevClose = detail?.previousClose;
    num? pct(num price) =>
        prevClose != null && prevClose > 0 ? (price - prevClose) / prevClose * 100 : null;
    num? abs(num price) => prevClose != null ? price - prevClose : null;
    if (quote != null && quote.status == 'LIVE' && quote.ltp != null) {
      if (_refreshing) {
        // Pending refresh: the prior tick keeps its own timestamp but is not
        // presented as live until the fresh response arrives.
        return _Priced(quote.ltp, _Basis.lastRecorded, quote.exchangeAt,
            pct(quote.ltp!), abs(quote.ltp!), refreshing: true);
      }
      return _Priced(quote.ltp, _Basis.live, quote.exchangeAt,
          pct(quote.ltp!), abs(quote.ltp!));
    }
    if (quote != null && quote.status == 'LAST_CLOSE' && quote.ltp != null) {
      // The provider's own change-vs-last-close is preferred when supplied.
      return _Priced(quote.ltp, _Basis.lastRecorded, quote.exchangeAt,
          quote.changePercent ?? pct(quote.ltp!), abs(quote.ltp!));
    }
    if (detail != null) {
      // A rights-gated FULL quote snapshot: a verified price, but a one-time
      // fetch, never presented as live.
      return _Priced(detail.ltp, _Basis.snapshot, detail.exchangeAt,
          pct(detail.ltp), abs(detail.ltp));
    }
    return _Priced(null, _Basis.none, null, null, null, status: quote?.status);
  }

  @override
  Widget build(BuildContext context) {
    final strategy = _strategy;
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      _StrategyBanner(title: strategy.title, note: strategy.note),
      const SizedBox(height: 8),
      Expanded(child: MintTickRoomCandlestickDesk(
        market: widget.market,
        initialInstrument: widget.initialInstrument,
        initialFrame: strategy.frame,
        showQuote: false,
        onInstrument: _onInstrument,
      )),
      const SizedBox(height: 8),
      strategy.isIntraday ? _intradayStats(context) : _positionalStats(context),
    ]);
  }

  // ---- Intraday: live price / change, day range, volume, session ----------

  Widget _intradayStats(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final type = Theme.of(context).textTheme;
    final detail = _detail;
    final priced = _resolvePrice();
    return _StatsCard(child: Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.start, children: [
      _PriceHeader(priced: priced, feedLabel: _feedLabel(),
        loading: _detailLoading, onRefresh: _refresh),
      const SizedBox(height: 8),
      if (_detailFailed)
        _UnavailableNote('Day statistics are unavailable for this instrument.')
      else if (detail == null)
        _UnavailableNote(_detailLoading
          ? 'Loading verified day statistics…'
          : 'Select an instrument to see live day statistics.')
      else ...[
        Row(children: [
          Expanded(flex: 8, child: _StatCell(
            label: 'OPEN', value: '₹${detail.open.toStringAsFixed(2)}')),
          _CellDivider(color: scheme.outlineVariant),
          Expanded(flex: 8, child: _StatCell(
            label: 'AVG',
            value: detail.averagePrice == null ? '—'
              : '₹${detail.averagePrice!.toStringAsFixed(2)}')),
          _CellDivider(color: scheme.outlineVariant),
          Expanded(flex: 9, child: _StatCell(
            label: 'VOLUME', value: _compactInt(detail.volume))),
        ]),
        const SizedBox(height: 10),
        // Marker follows the price header's resolved price (live / last-recorded
        // where valid); the open/high/low/volume bounds are the day snapshot.
        _RangeBar(
          label: 'DAY RANGE', low: detail.low, high: detail.high,
          marker: priced.price ?? detail.ltp),
        const SizedBox(height: 6),
        Text('Open / high / low / volume are a snapshot · observed '
            '${_clock(detail.exchangeAt)}',
          style: type.labelSmall?.copyWith(
            fontSize: 9, color: scheme.onSurfaceVariant)),
      ],
    ]));
  }

  // ---- Swing / Long-term: 52-week position, prev close, watch, notes -------

  Widget _positionalStats(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final type = Theme.of(context).textTheme;
    final detail = _detail;
    final instrument = _instrument;
    final held = instrument == null ? 0 : widget.holdingFor(instrument.id);
    final priced = _resolvePrice();
    return _StatsCard(child: Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.start, children: [
      _PriceHeader(priced: priced, feedLabel: _feedLabel(),
        loading: _detailLoading, onRefresh: _refresh),
      const SizedBox(height: 10),
      Row(children: [
        Expanded(child: _StatCell(
          label: 'PREV CLOSE',
          value: detail == null ? '—' : '₹${detail.previousClose.toStringAsFixed(2)}')),
        _CellDivider(color: scheme.outlineVariant),
        Expanded(child: _StatCell(
          label: 'HOLDING',
          value: held > 0 ? '$held sh' : 'Not held',
          accent: held > 0)),
        const SizedBox(width: 6),
        _WatchButton(
          watched: _watched,
          busy: _watchBusy,
          onPressed: _watched == null ? null : _toggleWatch),
      ]),
      const SizedBox(height: 10),
      if (_detailFailed)
        _UnavailableNote('52-week range is unavailable for this instrument.')
      else if (detail == null)
        _UnavailableNote(_detailLoading
          ? 'Loading verified price history…'
          : 'Select an instrument to see its holding-horizon context.')
      else if (detail.week52Low == null || detail.week52High == null)
        _UnavailableNote('52-week range not published for this instrument.')
      else ...[
        _RangeBar(
          label: '52-WEEK RANGE',
          low: detail.week52Low!, high: detail.week52High!,
          marker: priced.price ?? detail.ltp),
        const SizedBox(height: 6),
        Text('52-week bounds are a snapshot · observed ${_clock(detail.exchangeAt)}',
          style: type.labelSmall?.copyWith(
            fontSize: 9, color: scheme.onSurfaceVariant)),
      ],
      const SizedBox(height: 8),
      Row(children: [
        Icon(Icons.sticky_note_2_outlined, size: 14, color: scheme.onSurfaceVariant),
        const SizedBox(width: 6),
        Expanded(child: Text(
          'Thesis & notes are not yet available for Trading Rooms.',
          maxLines: 1, overflow: TextOverflow.ellipsis,
          style: type.labelSmall?.copyWith(color: scheme.onSurfaceVariant))),
      ]),
    ]));
  }
}

String _compactInt(int value) {
  if (value >= 10000000) return '${(value / 10000000).toStringAsFixed(2)}Cr';
  if (value >= 100000) return '${(value / 100000).toStringAsFixed(2)}L';
  if (value >= 1000) return '${(value / 1000).toStringAsFixed(1)}K';
  return '$value';
}

String _clock(DateTime? value) {
  if (value == null) return '—';
  final local = value.toLocal();
  final h = local.hour.toString().padLeft(2, '0');
  final m = local.minute.toString().padLeft(2, '0');
  return '$h:$m';
}

/// The single authoritative price display: value, change versus previous close,
/// and a source badge (LIVE / LAST RECORDED / SNAPSHOT) with its own timestamp.
/// When no verified price exists it shows the honest session status instead of
/// a price, and never a LIVE badge.
class _PriceHeader extends StatelessWidget {
  const _PriceHeader(
      {required this.priced, required this.feedLabel,
       required this.loading, required this.onRefresh});
  final _Priced priced;

  /// Live-feed condition surfaced beside a non-live price (snapshot / last
  /// recorded) so a stale, closed, disconnected or unavailable feed stays
  /// visible even when a price is shown. Null when the feed is healthy LIVE.
  final String? feedLabel;
  final bool loading;
  final Future<void> Function() onRefresh;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final type = Theme.of(context).textTheme;
    final up = priced.changePct != null && priced.changePct! >= 0;
    final changeColor = priced.changePct == null
        ? scheme.onSurfaceVariant
        : up ? scheme.primary : scheme.error;
    final (String badge, bool liveBadge) = switch (priced.basis) {
      _Basis.live => ('LIVE · ${_clock(priced.at)}', true),
      _Basis.lastRecorded => (priced.refreshing
          ? 'REFRESHING · last tick ${_clock(priced.at)}'
          : 'LAST RECORDED · ${_clock(priced.at)}', false),
      _Basis.snapshot => ('SNAPSHOT · ${_clock(priced.at)}', false),
      _Basis.none => (_statusLabel(priced.status, loading), false),
    };
    return Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min, children: [
        Text(priced.price == null ? '—' : '₹${priced.price!.toStringAsFixed(2)}',
          key: const ValueKey('room-equity-price'),
          style: type.titleLarge?.copyWith(
            fontWeight: FontWeight.w800,
            fontFeatures: const [FontFeature.tabularFigures()])),
        if (priced.changePct != null)
          Text(
            '${up ? '+' : ''}${priced.changePct!.toStringAsFixed(2)}%'
            '${priced.changeAbs == null ? '' : ' · ${priced.changeAbs! >= 0 ? '+' : ''}₹${priced.changeAbs!.toStringAsFixed(2)}'}'
            ' vs prev close',
            maxLines: 1, overflow: TextOverflow.ellipsis,
            style: type.labelSmall?.copyWith(
              color: changeColor, fontWeight: FontWeight.w700, fontSize: 10)),
        if (priced.basis != _Basis.none && feedLabel != null)
          Text(feedLabel!, key: const ValueKey('room-equity-feed'),
            maxLines: 1, overflow: TextOverflow.ellipsis,
            style: type.labelSmall?.copyWith(
              color: scheme.error, fontWeight: FontWeight.w700, fontSize: 10)),
      ])),
      const SizedBox(width: 8),
      ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 160),
        child: Column(crossAxisAlignment: CrossAxisAlignment.end,
        mainAxisSize: MainAxisSize.min, children: [
        Row(mainAxisSize: MainAxisSize.min, children: [
          Container(width: 7, height: 7, decoration: BoxDecoration(
            color: liveBadge ? scheme.primary : scheme.onSurfaceVariant,
            shape: BoxShape.circle)),
          const SizedBox(width: 5),
          Flexible(child: Text(badge, maxLines: 1, overflow: TextOverflow.ellipsis,
            key: const ValueKey('room-equity-basis'),
            style: type.labelSmall?.copyWith(
              color: liveBadge ? scheme.primary : scheme.onSurfaceVariant,
              fontWeight: FontWeight.w700, fontSize: 10))),
        ]),
        SizedBox(height: 24, child: IconButton(
          key: const ValueKey('room-equity-refresh'),
          tooltip: 'Refresh price & statistics',
          visualDensity: VisualDensity.compact,
          padding: EdgeInsets.zero,
          constraints: const BoxConstraints.tightFor(width: 32, height: 24),
          onPressed: loading ? null : () => onRefresh(),
          icon: const Icon(Icons.refresh_rounded, size: 16))),
      ])),
    ]);
  }

  static String _statusLabel(String? status, bool loading) => switch (status) {
    'MARKET_CLOSED' => 'MARKET CLOSED',
    'STALE' => 'STALE · NO PRICE',
    'CONNECTING' => 'CONNECTING…',
    'RECONNECTING' => 'RECONNECTING…',
    'BROKER_CONNECTION_REQUIRED' => 'BROKER OFFLINE',
    'UNAVAILABLE' => 'PRICE UNAVAILABLE',
    _ => loading ? 'CHECKING…' : 'PRICE UNAVAILABLE',
  };
}

class _StrategyBanner extends StatelessWidget {
  const _StrategyBanner({required this.title, required this.note});
  final String title;
  final String note;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final type = Theme.of(context).textTheme;
    final intraday = title == 'INTRADAY';
    return Container(
      height: 26,
      padding: const EdgeInsets.symmetric(horizontal: 9),
      decoration: BoxDecoration(
        color: scheme.primary.withValues(alpha: 0.08),
        borderRadius: BorderRadius.circular(6),
        border: Border.all(color: scheme.primary.withValues(alpha: 0.22))),
      child: Row(children: [
        Icon(intraday ? Icons.bolt_rounded : Icons.timeline_rounded,
          size: 14, color: scheme.primary),
        const SizedBox(width: 6),
        Text(title, style: type.labelSmall?.copyWith(
          color: scheme.primary, fontWeight: FontWeight.w800, fontSize: 10)),
        const SizedBox(width: 8),
        Expanded(child: Text(note, maxLines: 1, overflow: TextOverflow.ellipsis,
          style: type.labelSmall?.copyWith(
            color: scheme.onSurfaceVariant, fontSize: 10))),
      ]),
    );
  }
}

class _StatsCard extends StatelessWidget {
  const _StatsCard({required this.child});
  final Widget child;
  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.fromLTRB(10, 9, 10, 10),
      decoration: BoxDecoration(
        color: scheme.surfaceContainerLow,
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: scheme.outlineVariant)),
      child: child,
    );
  }
}

class _StatCell extends StatelessWidget {
  const _StatCell({required this.label, required this.value, this.accent = false});
  final String label, value;
  final bool accent;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final type = Theme.of(context).textTheme;
    return Column(crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min, children: [
      Text(label, maxLines: 1, overflow: TextOverflow.ellipsis,
        style: type.labelSmall?.copyWith(fontSize: 9, color: scheme.onSurfaceVariant)),
      const SizedBox(height: 2),
      Text(value, maxLines: 1, overflow: TextOverflow.ellipsis,
        style: type.bodyMedium?.copyWith(
          fontWeight: FontWeight.w800,
          color: accent ? scheme.primary : scheme.onSurface,
          fontFeatures: const [FontFeature.tabularFigures()])),
    ]);
  }
}

class _CellDivider extends StatelessWidget {
  const _CellDivider({required this.color});
  final Color color;
  @override
  Widget build(BuildContext context) =>
      SizedBox(height: 30, child: VerticalDivider(color: color, width: 13));
}

/// Low—high range with a marker for the current price. Values are formatted
/// from verified day detail; the marker clamps to the bar without altering the
/// reported numbers.
class _RangeBar extends StatelessWidget {
  const _RangeBar({required this.label, required this.low,
    required this.high, required this.marker});
  final String label;
  final num low, high, marker;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final type = Theme.of(context).textTheme;
    final span = high - low;
    final fraction = span <= 0
        ? 0.5
        : ((marker - low) / span).clamp(0.0, 1.0).toDouble();
    return Column(crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min, children: [
      Text(label, style: type.labelSmall?.copyWith(
        fontSize: 9, color: scheme.onSurfaceVariant)),
      const SizedBox(height: 6),
      LayoutBuilder(builder: (context, constraints) {
        final width = constraints.maxWidth;
        final maxLeft = width - 10 > 0 ? width - 10 : 0.0;
        final left = (fraction * width - 5).clamp(0.0, maxLeft).toDouble();
        return SizedBox(height: 8, child: Stack(clipBehavior: Clip.none, children: [
          Align(alignment: Alignment.centerLeft, child: Container(
            height: 4, width: width,
            decoration: BoxDecoration(
              color: scheme.outlineVariant,
              borderRadius: BorderRadius.circular(2)))),
          Positioned(
            left: left,
            top: -1,
            child: Semantics(
              // Exposes the marker's actual input so tests can verify its source.
              label: 'Current price marker ₹${marker.toStringAsFixed(2)}',
              child: Container(width: 10, height: 10,
                decoration: BoxDecoration(
                  color: scheme.primary, shape: BoxShape.circle,
                  border: Border.all(color: scheme.surface, width: 1.5))))),
        ]));
      }),
      const SizedBox(height: 5),
      Row(children: [
        Text('₹${low.toStringAsFixed(2)}', style: type.labelSmall?.copyWith(
          fontSize: 10, color: scheme.onSurfaceVariant,
          fontFeatures: const [FontFeature.tabularFigures()])),
        const Spacer(),
        Text('₹${high.toStringAsFixed(2)}', style: type.labelSmall?.copyWith(
          fontSize: 10, color: scheme.onSurfaceVariant,
          fontFeatures: const [FontFeature.tabularFigures()])),
      ]),
    ]);
  }
}

class _WatchButton extends StatelessWidget {
  const _WatchButton({required this.watched, required this.busy, this.onPressed});
  final bool? watched;
  final bool busy;
  final VoidCallback? onPressed;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return IconButton(
      key: const ValueKey('room-equity-watch-toggle'),
      tooltip: watched == null ? 'Watchlist unavailable'
          : watched! ? 'Remove from watchlist' : 'Add to watchlist',
      visualDensity: VisualDensity.compact,
      constraints: const BoxConstraints.tightFor(width: 36, height: 36),
      onPressed: busy ? null : onPressed,
      icon: busy
        ? const SizedBox(width: 16, height: 16,
            child: CircularProgressIndicator(strokeWidth: 2))
        : Icon(watched == true ? Icons.star_rounded : Icons.star_border_rounded,
            size: 20,
            color: watched == true ? scheme.primary : scheme.onSurfaceVariant),
    );
  }
}

class _UnavailableNote extends StatelessWidget {
  const _UnavailableNote(this.message);
  final String message;
  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Text(message, maxLines: 2, overflow: TextOverflow.ellipsis,
        style: Theme.of(context).textTheme.labelSmall?.copyWith(
          color: scheme.onSurfaceVariant)),
    );
  }
}

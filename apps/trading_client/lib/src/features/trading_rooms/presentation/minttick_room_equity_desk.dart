import 'package:flutter/material.dart';

import '../../../data/instrument_repository.dart';
import '../data/customer_market_repository.dart';
import 'minttick_room_candlestick_desk.dart';

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
/// Every value shown here is server-sourced. When the backend cannot supply a
/// field (missing day detail, no 52-week range, unavailable session) the desk
/// renders a clear unavailable state and never fabricates a number.
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

  /// Room horizon: 'Intraday', 'Swing' or 'Long-term'.
  final String focus;

  final InstrumentRecord? initialInstrument;

  /// Bubbles the active instrument up so the order sheet opens pre-filled.
  final ValueChanged<InstrumentRecord?> onInstrument;

  /// Open quantity currently held for an instrument (0 when none). Lets the
  /// swing desk surface position/watch context without a second positions read.
  final int Function(int instrumentId) holdingFor;

  bool get _isIntraday => focus == 'Intraday';

  @override
  State<MintTickRoomEquityDesk> createState() => _MintTickRoomEquityDeskState();
}

class _MintTickRoomEquityDeskState extends State<MintTickRoomEquityDesk> {
  InstrumentRecord? _instrument;
  CustomerDayDetail? _detail;
  CustomerLiveQuote? _quote;
  bool _detailLoading = false;
  bool _detailFailed = false;
  bool? _watched;
  bool _watchBusy = false;
  int _generation = 0;

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
    // Day detail and the session quote load independently: one failing must
    // never blank the other. Unknown fields stay unavailable, never estimated.
    _loadSession(instrument, request);
    if (!widget._isIntraday) _loadWatchState(instrument, request);
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
    try {
      final quote = await widget.market.liveQuote(instrument.id);
      if (!mounted || request != _generation) return;
      if (quote.instrumentId == instrument.id) {
        setState(() => _quote = quote);
      }
    } catch (_) {
      // A missing session quote leaves the badge in its unavailable state.
    }
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

  @override
  Widget build(BuildContext context) {
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      _StrategyBanner(intraday: widget._isIntraday),
      const SizedBox(height: 8),
      Expanded(child: MintTickRoomCandlestickDesk(
        market: widget.market,
        initialInstrument: widget.initialInstrument,
        initialFrame: widget._isIntraday ? 0 : 2,
        onInstrument: _onInstrument,
      )),
      const SizedBox(height: 8),
      widget._isIntraday ? _intradayStats(context) : _swingStats(context),
    ]);
  }

  // ---- Intraday: live price / change, day range, volume, session ----------

  Widget _intradayStats(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final detail = _detail;
    final changePct = detail == null || detail.previousClose <= 0
        ? null
        : (detail.ltp - detail.previousClose) / detail.previousClose * 100;
    return _StatsCard(child: Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.start, children: [
      Row(children: [
        Expanded(child: _SessionBadge(quote: _quote, loading: _detailLoading)),
        if (detail != null)
          Text('Vol ${_compactInt(detail.volume)}',
            style: Theme.of(context).textTheme.labelSmall?.copyWith(
              color: scheme.onSurfaceVariant,
              fontFeatures: const [FontFeature.tabularFigures()])),
      ]),
      const SizedBox(height: 8),
      if (_detailFailed)
        _UnavailableNote('Day statistics are unavailable for this instrument.')
      else if (detail == null)
        _UnavailableNote(_detailLoading
          ? 'Loading verified day statistics…'
          : 'Select an instrument to see live day statistics.')
      else ...[
        Row(children: [
          Expanded(flex: 10, child: _StatCell(
            label: 'LAST', value: '₹${detail.ltp.toStringAsFixed(2)}',
            accent: changePct != null && changePct >= 0,
            down: changePct != null && changePct < 0,
            secondary: changePct == null ? null
              : '${changePct >= 0 ? '+' : ''}${changePct.toStringAsFixed(2)}% · ₹${(detail.ltp - detail.previousClose).toStringAsFixed(2)}')),
          _CellDivider(color: scheme.outlineVariant),
          Expanded(flex: 7, child: _StatCell(
            label: 'OPEN', value: '₹${detail.open.toStringAsFixed(2)}')),
          _CellDivider(color: scheme.outlineVariant),
          Expanded(flex: 8, child: _StatCell(
            label: 'AVG',
            value: detail.averagePrice == null ? '—'
              : '₹${detail.averagePrice!.toStringAsFixed(2)}')),
        ]),
        const SizedBox(height: 10),
        _RangeBar(
          label: "DAY RANGE",
          low: detail.low, high: detail.high, marker: detail.ltp),
      ],
    ]));
  }

  // ---- Swing: 52-week position, prev close, watch + holding, notes ---------

  Widget _swingStats(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final type = Theme.of(context).textTheme;
    final detail = _detail;
    final instrument = _instrument;
    final held = instrument == null ? 0 : widget.holdingFor(instrument.id);
    final changePct = detail == null || detail.previousClose <= 0
        ? null
        : (detail.ltp - detail.previousClose) / detail.previousClose * 100;
    return _StatsCard(child: Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.start, children: [
      Row(children: [
        Expanded(child: _StatCell(
          label: 'PREV CLOSE',
          value: detail == null ? '—' : '₹${detail.previousClose.toStringAsFixed(2)}',
          accent: changePct != null && changePct >= 0,
          down: changePct != null && changePct < 0,
          secondary: changePct == null ? null
            : '${changePct >= 0 ? '+' : ''}${changePct.toStringAsFixed(2)}% vs last close')),
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
      else
        _RangeBar(
          label: '52-WEEK RANGE',
          low: detail.week52Low!, high: detail.week52High!, marker: detail.ltp),
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

class _StrategyBanner extends StatelessWidget {
  const _StrategyBanner({required this.intraday});
  final bool intraday;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final type = Theme.of(context).textTheme;
    final title = intraday ? 'INTRADAY' : 'SWING';
    final note = intraday
        ? 'Session speed · live price, day range & volume'
        : 'Hold horizon · 52-week position, watch & holdings';
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
  const _StatCell({required this.label, required this.value,
    this.secondary, this.accent = false, this.down = false});
  final String label, value;
  final String? secondary;
  final bool accent, down;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final type = Theme.of(context).textTheme;
    final color = down ? scheme.error : accent ? scheme.primary : scheme.onSurface;
    return Column(crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min, children: [
      Text(label, maxLines: 1, overflow: TextOverflow.ellipsis,
        style: type.labelSmall?.copyWith(fontSize: 9, color: scheme.onSurfaceVariant)),
      const SizedBox(height: 2),
      Text(value, maxLines: 1, overflow: TextOverflow.ellipsis,
        style: type.bodyMedium?.copyWith(
          fontWeight: FontWeight.w800, color: color,
          fontFeatures: const [FontFeature.tabularFigures()])),
      if (secondary != null) Text(secondary!, maxLines: 1,
        overflow: TextOverflow.ellipsis,
        style: type.labelSmall?.copyWith(
          fontSize: 9,
          color: down ? scheme.error : accent ? scheme.primary : scheme.onSurfaceVariant)),
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
            child: Container(width: 10, height: 10,
              decoration: BoxDecoration(
                color: scheme.primary, shape: BoxShape.circle,
                border: Border.all(color: scheme.surface, width: 1.5)))),
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

/// Market/session and data-freshness indicator derived from the verified quote
/// status. Never implies a live session when the broker feed is unavailable.
class _SessionBadge extends StatelessWidget {
  const _SessionBadge({required this.quote, required this.loading});
  final CustomerLiveQuote? quote;
  final bool loading;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final type = Theme.of(context).textTheme;
    final status = quote?.status;
    final (String text, bool live, bool warn) = switch (status) {
      'LIVE' => ('LIVE · ${_clock(quote!.exchangeAt)}', true, false),
      'LAST_CLOSE' => ('LAST RECORDED · ${_clock(quote!.exchangeAt)}', false, false),
      'MARKET_CLOSED' => ('MARKET CLOSED', false, true),
      'STALE' => ('STALE DATA', false, true),
      'CONNECTING' => ('CONNECTING…', false, true),
      'RECONNECTING' => ('RECONNECTING…', false, true),
      'BROKER_CONNECTION_REQUIRED' => ('BROKER OFFLINE', false, true),
      'UNAVAILABLE' => ('DATA UNAVAILABLE', false, true),
      _ => (loading ? 'CHECKING SESSION…' : 'SESSION UNAVAILABLE', false, true),
    };
    final color = live ? scheme.primary
        : warn ? scheme.onSurfaceVariant : scheme.onSurface;
    return Row(mainAxisSize: MainAxisSize.min, children: [
      Container(width: 7, height: 7, decoration: BoxDecoration(
        color: live ? scheme.primary : scheme.onSurfaceVariant,
        shape: BoxShape.circle)),
      const SizedBox(width: 6),
      Flexible(child: Text(text, maxLines: 1, overflow: TextOverflow.ellipsis,
        style: type.labelSmall?.copyWith(
          color: color, fontWeight: FontWeight.w700, fontSize: 10))),
    ]);
  }

  static String _clock(DateTime? value) {
    if (value == null) return '—';
    final local = value.toLocal();
    final h = local.hour.toString().padLeft(2, '0');
    final m = local.minute.toString().padLeft(2, '0');
    return '$h:$m';
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

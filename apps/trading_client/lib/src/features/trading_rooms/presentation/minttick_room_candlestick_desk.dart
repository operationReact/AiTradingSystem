
import 'dart:math' as math;
import 'package:flutter/material.dart';
import '../../../data/instrument_repository.dart';
import '../data/customer_market_repository.dart';

/// Trading Rooms chart: the same compact geometry as the approved HTML mock.
/// No simulated market candles or prices are used by the Flutter client.
class MintTickRoomCandlestickDesk extends StatefulWidget {
  const MintTickRoomCandlestickDesk({
    required this.market,
    required this.onInstrument,
    super.key,
  });
  final HttpCustomerMarketRepository market;
  final ValueChanged<InstrumentRecord?> onInstrument;

  @override
  State<MintTickRoomCandlestickDesk> createState() =>
      _MintTickRoomCandlestickDeskState();
}

class _MintTickRoomCandlestickDeskState
    extends State<MintTickRoomCandlestickDesk> {
  static const _frames = [
    ('5m', 'FIVE_MINUTE', 5),
    ('1H', 'ONE_HOUR', 60),
    ('1D', 'ONE_DAY', 365),
  ];

  InstrumentRecord? _instrument;
  CustomerLiveQuote? _quote;
  List<CustomerCandle>? _candles;
  String? _error;
  int _frame = 2;
  int _generation = 0;
  bool _loading = false;

  @override
  void initState() {
    super.initState();
    _selectInitialStock();
  }

  @override
  void dispose() {
    _generation++;
    super.dispose();
  }

  Future<void> _selectInitialStock() async {
    try {
      final rows = await widget.market.searchPage(query: 'RELIANCE', page: 0);
      if (!mounted || _instrument != null) return;
      final matches = rows.content.where(_equity).toList();
      if (matches.isEmpty) return;
      final symbol = matches.where((item) =>
          item.tradingSymbol.toUpperCase().startsWith('RELIANCE')).firstOrNull
          ?? matches.first;
      _select(symbol);
    } catch (_) {
      if (mounted) setState(() =>
        _error = 'Search and select an equity to see historical candles.');
    }
  }

  static bool _equity(InstrumentRecord item) =>
      (item.exchange == 'NSE' || item.exchange == 'BSE') &&
      const {'EQ', 'EQUITY', 'STOCK'}
          .contains(item.instrumentType.toUpperCase());

  void _select(InstrumentRecord selected) {
    setState(() {
      _instrument = selected;
      _quote = null;
      _candles = null;
      _error = null;
    });
    widget.onInstrument(selected);
    _refresh();
  }

  static String _date(DateTime value) =>
      '${value.year.toString().padLeft(4, '0')}-'
      '${value.month.toString().padLeft(2, '0')}-'
      '${value.day.toString().padLeft(2, '0')}';

  Future<void> _refresh() async {
    final instrument = _instrument;
    if (instrument == null) return;
    final generation = ++_generation;
    final interval = _frames[_frame];
    final now = DateTime.now().toUtc()
        .add(const Duration(hours: 5, minutes: 30));
    setState(() {
      _loading = true;
      _error = null;
      _candles = null;
      _quote = null;
    });
    try {
      final page = await widget.market.candles(
        instrumentId: instrument.id,
        interval: interval.$2,
        fromDate: _date(now.subtract(Duration(days: interval.$3))),
        toDate: _date(now),
      );
      if (!mounted || generation != _generation) return;
      final sorted = [...page.items]
        ..sort((a, b) => a.candleTime.compareTo(b.candleTime));
      setState(() => _candles = sorted.length <= 80
          ? sorted : sorted.sublist(sorted.length - 80));
    } catch (_) {
      if (mounted && generation == _generation) {
        setState(() => _error =
            'Stored candles are unavailable for this instrument/interval.');
      }
    } finally {
      if (mounted && generation == _generation) {
        setState(() => _loading = false);
      }
    }
    try {
      final quote = await widget.market.liveQuote(instrument.id);
      if (!mounted || generation != _generation) return;
      if (quote.status == 'LIVE' && quote.instrumentId == instrument.id &&
          quote.ltp != null) {
        setState(() => _quote = quote);
      }
    } catch (_) {
      // History is valid independently of a missing LIVE broker tick.
    }
  }

  Future<void> _choose() async {
    final query = TextEditingController(text: _instrument?.tradingSymbol ?? '');
    var results = <InstrumentRecord>[];
    var searching = false;
    String? error;
    final chosen = await showModalBottomSheet<InstrumentRecord>(
      context: context,
      useSafeArea: true,
      isScrollControlled: true,
      backgroundColor: Theme.of(context).colorScheme.surfaceContainerHigh,
      builder: (context) => StatefulBuilder(builder: (context, update) {
        Future<void> search() async {
          if (query.text.trim().length < 2) return;
          update(() { searching = true; error = null; });
          try {
            final page = await widget.market.searchPage(
                query: query.text.trim(), page: 0);
            if (!context.mounted) return;
            update(() => results = page.content.where(_equity).toList());
          } catch (_) {
            if (context.mounted) update(() =>
              error = 'Instrument lookup unavailable.');
          } finally {
            if (context.mounted) update(() => searching = false);
          }
        }

        return Padding(
          padding: EdgeInsets.fromLTRB(18, 16, 18,
              MediaQuery.viewInsetsOf(context).bottom + 20),
          child: SizedBox(
            height: 330,
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              const Text('Select NSE / BSE equity',
                style: TextStyle(fontWeight: FontWeight.w800, fontSize: 17)),
              const SizedBox(height: 12),
              TextField(
                key: const ValueKey('room-symbol-search'),
                controller: query,
                autofocus: true,
                onSubmitted: (_) => search(),
                decoration: InputDecoration(
                  hintText: 'RELIANCE, SBIN, ICICI...',
                  suffixIcon: IconButton(
                    onPressed: searching ? null : search,
                    icon: const Icon(Icons.search_rounded),
                  ),
                ),
              ),
              const SizedBox(height: 8),
              if (searching) const LinearProgressIndicator(minHeight: 2),
              if (error != null) Text(error!),
              Expanded(child: ListView.builder(
                itemCount: results.length,
                itemBuilder: (context, index) {
                  final item = results[index];
                  return ListTile(
                    dense: true,
                    title: Text(item.tradingSymbol),
                    subtitle: Text(item.exchange),
                    onTap: () => Navigator.of(context).pop(item),
                  );
                },
              )),
            ]),
          ),
        );
      }),
    );
    query.dispose();
    if (chosen != null && mounted) _select(chosen);
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    final instrument = _instrument;
    final candles = _candles;
    final last = candles == null || candles.isEmpty ? null : candles.last;
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      SizedBox(height: 48, child: Row(children: [
        Expanded(child: InkWell(
          key: const ValueKey('room-instrument-picker'),
          onTap: _choose,
          borderRadius: BorderRadius.circular(6),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            crossAxisAlignment: CrossAxisAlignment.start, children: [
            Row(mainAxisSize: MainAxisSize.min, children: [
              Flexible(child: Text(instrument?.tradingSymbol ?? 'Choose stock',
                maxLines: 1, overflow: TextOverflow.ellipsis,
                style: text.titleMedium?.copyWith(fontWeight: FontWeight.w800))),
              const SizedBox(width: 3),
              Icon(Icons.keyboard_arrow_down_rounded,
                color: scheme.primary, size: 18),
            ]),
            Text(instrument == null ? 'NSE / BSE · Select equity'
                : '${instrument.exchange} · Equity · verified instrument',
              maxLines: 1, overflow: TextOverflow.ellipsis,
              style: text.labelSmall?.copyWith(color: scheme.onSurfaceVariant)),
          ]),
        )),
        const SizedBox(width: 8),
        Column(mainAxisAlignment: MainAxisAlignment.center,
          crossAxisAlignment: CrossAxisAlignment.end, children: [
            Text(_quote?.ltp == null ? '—' : '₹${_quote!.ltp!.toStringAsFixed(2)}',
              style: text.titleMedium?.copyWith(fontWeight: FontWeight.w700)),
            Text(_quote == null ? 'QUOTE UNAVAILABLE' : 'VERIFIED LIVE',
              style: text.labelSmall?.copyWith(color: _quote == null
                ? scheme.onSurfaceVariant : scheme.primary)),
          ]),
      ])),
      SizedBox(height: 34, child: Row(children: [
        for (var i = 0; i < _frames.length; i++)
          Padding(padding: const EdgeInsets.only(right: 4),
            child: InkWell(
              key: ValueKey('room-chart-frame-${_frames[i].$1}'),
              onTap: () { if (_loading) return; setState(() => _frame = i); _refresh(); },
              borderRadius: BorderRadius.circular(5),
              child: Container(
                padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 5),
                decoration: BoxDecoration(
                  borderRadius: BorderRadius.circular(5),
                  color: _frame == i ? scheme.primaryContainer
                    : Colors.transparent,
                ),
                child: Text(_frames[i].$1,
                  style: text.labelSmall?.copyWith(
                    color: _frame == i ? scheme.primary : scheme.onSurfaceVariant,
                    fontWeight: FontWeight.w700)),
              ),
            )),
        const Spacer(),
        Text(candles == null ? 'Historical' : '${candles.length} stored bars',
          style: text.labelSmall?.copyWith(color: scheme.onSurfaceVariant)),
        IconButton(
          tooltip: 'Refresh chart',
          onPressed: instrument == null || _loading ? null : _refresh,
          icon: const Icon(Icons.refresh_rounded, size: 18),
          visualDensity: VisualDensity.compact,
          constraints: const BoxConstraints.tightFor(width: 32, height: 32),
        ),
      ])),
      Expanded(child: Container(
        width: double.infinity,
        constraints: const BoxConstraints(minHeight: 100),
        padding: const EdgeInsets.all(8),
        decoration: BoxDecoration(
          color: scheme.surfaceContainerLow,
          borderRadius: BorderRadius.circular(8),
          border: Border.all(color: scheme.outlineVariant)),
        child: Semantics(
          label: last == null
            ? 'No historical candles available'
            : '${candles!.length} stored candles for ${instrument!.tradingSymbol}',
          child: _loading
            ? const Center(child: CircularProgressIndicator(strokeWidth: 2))
            : candles != null && candles.length >= 2
              ? CustomPaint(
                  painter: _CandlesPainter(
                      candles, scheme.primary, scheme.error, scheme.outlineVariant),
                  child: const SizedBox.expand())
              : Center(child: Text(_error ??
                  (instrument == null ? 'Select a stock to load the chart.'
                    : 'No stored candles for this timeframe.'),
                  textAlign: TextAlign.center,
                  style: text.bodySmall?.copyWith(color: scheme.onSurfaceVariant))),
        ),
      )),
      SizedBox(height: 30, child: Row(children: [
        Expanded(child: Text(
          last == null ? 'Verified stored candles only · no estimates'
            : 'Stored ${last.source} · ${last.fetchedAt.toLocal()}',
          maxLines: 1, overflow: TextOverflow.ellipsis,
          style: text.labelSmall?.copyWith(color: scheme.onSurfaceVariant)),
        Text(_frames[_frame].$1,
          style: text.labelSmall?.copyWith(color: scheme.onSurfaceVariant)),
      ])),
    ]);
  }
}

class _CandlesPainter extends CustomPainter {
  const _CandlesPainter(this.bars, this.rise, this.fall, this.grid);
  final List<CustomerCandle> bars;
  final Color rise, fall, grid;

  @override
  void paint(Canvas canvas, Size size) {
    final lo = bars.map((e) => e.low.toDouble()).reduce(math.min);
    final hi = bars.map((e) => e.high.toDouble()).reduce(math.max);
    final span = math.max(hi - lo, .01);
    double y(num price) => 9 + (hi - price) / span * (size.height - 18);
    final gridPaint = Paint()
      ..color = grid.withValues(alpha: 0.65)
      ..strokeWidth = .7;
    for (var i = 1; i < 4; i++) {
      final level = size.height * i / 4;
      canvas.drawLine(Offset(0, level), Offset(size.width, level), gridPaint);
    }
    final step = size.width / bars.length;
    final width = math.max(1.5, math.min(10.0, step * .58));
    for (var i = 0; i < bars.length; i++) {
      final item = bars[i];
      final x = (i + .5) * step;
      final paint = Paint()
        ..color = item.close >= item.open ? rise : fall;
      canvas.drawLine(Offset(x, y(item.low)), Offset(x, y(item.high)),
        paint..strokeWidth = 1);
      final top = math.min(y(item.open), y(item.close));
      final end = math.max(top + 1, math.max(y(item.open), y(item.close)));
      canvas.drawRect(Rect.fromLTRB(x - width / 2, top,
        x + width / 2, end), paint);
    }
  }

  @override
  bool shouldRepaint(covariant _CandlesPainter old) =>
    old.bars != bars || old.rise != rise ||
    old.fall != fall || old.grid != grid;
}

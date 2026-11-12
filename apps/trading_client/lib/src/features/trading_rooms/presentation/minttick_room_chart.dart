
import 'dart:math' as math;
import 'package:flutter/material.dart';
import '../../../data/instrument_repository.dart';
import '../data/customer_market_repository.dart';

/// Compact, genuine stored-candle chart. No synthetic bar generation and no
/// assumption that a historical candle is the broker's current LIVE quote.
class MintTickRoomChart extends StatefulWidget {
  const MintTickRoomChart({required this.market, super.key});
  final HttpCustomerMarketRepository market;

  @override
  State<MintTickRoomChart> createState() => _MintTickRoomChartState();
}

class _MintTickRoomChartState extends State<MintTickRoomChart> {
  final _query = TextEditingController();
  List<InstrumentRecord> _results = const [];
  InstrumentRecord? _selected;
  List<CustomerCandle>? _candles;
  int _timeframe = 2;
  int _serial = 0;
  bool _busy = false;
  String? _error;

  static const _frames = [
    ('5m', 'FIVE_MINUTE', 5),
    ('1H', 'ONE_HOUR', 60),
    ('1D', 'ONE_DAY', 365),
  ];

  @override
  void dispose() {
    _serial++;
    _query.dispose();
    super.dispose();
  }

  Future<void> _search() async {
    final query = _query.text.trim();
    if (query.length < 2) {
      setState(() => _error = 'Search with at least two characters.');
      return;
    }
    final serial = ++_serial;
    setState(() { _busy = true; _error = null; _results = []; _selected = null; _candles = null; });
    try {
      final page = await widget.market.searchPage(query: query, page: 0);
      if (!mounted || serial != _serial) return;
      setState(() => _results = page.content.where((stock) =>
        (stock.exchange == 'NSE' || stock.exchange == 'BSE') &&
        const {'EQ', 'EQUITY', 'STOCK'}.contains(stock.instrumentType.toUpperCase())
      ).toList(growable: false));
    } catch (_) {
      if (mounted && serial == _serial) {
        setState(() => _error = 'Instrument search unavailable. Try again.');
      }
    } finally {
      if (mounted && serial == _serial) setState(() => _busy = false);
    }
  }

  static String _date(DateTime value) =>
      '${value.year.toString().padLeft(4, '0')}-${value.month.toString().padLeft(2, '0')}-${value.day.toString().padLeft(2, '0')}';

  Future<void> _load() async {
    final stock = _selected;
    if (stock == null) return;
    final serial = ++_serial;
    final frame = _frames[_timeframe];
    final todayIst = DateTime.now().toUtc().add(const Duration(hours: 5, minutes: 30));
    setState(() { _busy = true; _error = null; _candles = null; });
    try {
      final page = await widget.market.candles(
        instrumentId: stock.id, interval: frame.$2,
        fromDate: _date(todayIst.subtract(Duration(days: frame.$3))),
        toDate: _date(todayIst),
      );
      if (!mounted || serial != _serial) return;
      final sorted = [...page.items]
        ..sort((a, b) => a.candleTime.compareTo(b.candleTime));
      setState(() => _candles = sorted.length <= 80
        ? sorted : sorted.sublist(sorted.length - 80));
    } catch (_) {
      if (mounted && serial == _serial) {
        setState(() => _error = 'Stored candles unavailable for this symbol and interval.');
      }
    } finally {
      if (mounted && serial == _serial) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final color = Theme.of(context).colorScheme;
    final styles = Theme.of(context).textTheme;
    final last = _candles?.isNotEmpty == true ? _candles!.last : null;
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Row(children: [
        Expanded(child: TextField(
          key: const ValueKey('room-chart-search'),
          controller: _query, onSubmitted: (_) => _search(),
          decoration: InputDecoration(
            isDense: true, hintText: 'Find NSE / BSE stock',
            prefixIcon: const Icon(Icons.search_rounded, size: 18),
            suffixIcon: IconButton(
              tooltip: 'Find stock', onPressed: _busy ? null : _search,
              icon: const Icon(Icons.arrow_forward_rounded, size: 18))),
        )),
        const SizedBox(width: 7),
        IconButton(onPressed: _selected == null || _busy ? null : _load,
          tooltip: 'Refresh stored candles', icon: const Icon(Icons.refresh_rounded, size: 20)),
      ]),
      if (_results.isNotEmpty) ...[
        const SizedBox(height: 6),
        DropdownButtonFormField<int>(
          key: const ValueKey('room-chart-symbol'),
          isExpanded: true,
          initialValue: _selected?.id,
          decoration: const InputDecoration(labelText: 'Instrument', isDense: true),
          items: [
            for (final stock in _results)
              DropdownMenuItem(value: stock.id,
                child: Text('${stock.tradingSymbol} · ${stock.exchange}',
                  overflow: TextOverflow.ellipsis)),
          ],
          onChanged: (id) {
            setState(() {
              _selected = _results.where((item) => item.id == id).firstOrNull;
              _candles = null;
            });
            _load();
          },
        ),
      ],
      const SizedBox(height: 9),
      Row(children: [
        Text(_selected?.tradingSymbol ?? 'Stored market chart',
          style: styles.titleSmall?.copyWith(fontWeight: FontWeight.w700)),
        const Spacer(),
        for (var index = 0; index < _frames.length; index++)
          Padding(padding: const EdgeInsets.only(left: 4),
            child: InkWell(
              key: ValueKey('room-chart-frame-${_frames[index].$1}'),
              onTap: () { if (_busy) return; setState(() => _timeframe = index); _load(); },
              borderRadius: BorderRadius.circular(5),
              child: Container(padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 5),
                decoration: BoxDecoration(
                  color: index == _timeframe ? color.primary.withValues(alpha: 0.15)
                    : color.surfaceContainerLow,
                  borderRadius: BorderRadius.circular(5)),
                child: Text(_frames[index].$1, style: styles.labelSmall?.copyWith(
                  color: index == _timeframe ? color.primary : color.onSurfaceVariant))),
            ),
          ),
      ]),
      const SizedBox(height: 8),
      Container(
        height: 218,
        padding: const EdgeInsets.all(8),
        decoration: BoxDecoration(color: color.surfaceContainerLow,
          borderRadius: BorderRadius.circular(10),
          border: Border.all(color: color.outlineVariant)),
        child: Semantics(
          label: last == null ? 'Historical candle chart unavailable'
            : '${_candles!.length} historical candles for ${_selected!.tradingSymbol}',
          child: _busy
            ? const Center(child: CircularProgressIndicator())
            : _candles != null && _candles!.length >= 2
              ? CustomPaint(painter: _RoomCandlePainter(
                  _candles!, color.primary, color.error, color.outlineVariant),
                  child: const SizedBox.expand())
              : Center(child: Text(_error ?? (_selected == null
                  ? 'Search and select an equity to view historical candles.'
                  : 'No stored candles for this range.'),
                textAlign: TextAlign.center, style: styles.bodySmall)),
        ),
      ),
      const SizedBox(height: 5),
      Text(last == null ? 'Historical data only · No estimated candles'
        : 'Stored ${last.source} candles · fetched ${last.fetchedAt.toLocal()}',
        maxLines: 1, overflow: TextOverflow.ellipsis,
        style: styles.labelSmall?.copyWith(color: color.onSurfaceVariant)),
    ]);
  }
}

class _RoomCandlePainter extends CustomPainter {
  const _RoomCandlePainter(this.bars, this.rise, this.fall, this.grid);
  final List<CustomerCandle> bars;
  final Color rise, fall, grid;

  @override
  void paint(Canvas canvas, Size size) {
    final lower = bars.map((e) => e.low.toDouble()).reduce(math.min);
    final upper = bars.map((e) => e.high.toDouble()).reduce(math.max);
    final span = math.max(upper - lower, 0.01);
    double y(num price) => 6 + (upper - price) / span * (size.height - 12);
    final gridPaint = Paint()..color = grid.withValues(alpha: 0.6)..strokeWidth = 0.7;
    for (var i = 1; i < 4; i++) {
      final level = size.height * i / 4;
      canvas.drawLine(Offset(0, level), Offset(size.width, level), gridPaint);
    }
    final step = size.width / bars.length;
    final width = math.max(1.5, math.min(10.0, step * .58));
    for (var i = 0; i < bars.length; i++) {
      final bar = bars[i];
      final x = (i + .5) * step;
      final p = Paint()..color = bar.close >= bar.open ? rise : fall;
      canvas.drawLine(Offset(x, y(bar.low)), Offset(x, y(bar.high)),
        p..strokeWidth = 1);
      final top = math.min(y(bar.open), y(bar.close));
      final bottom = math.max(y(bar.open), y(bar.close));
      canvas.drawRect(Rect.fromLTRB(x - width / 2, top,
        x + width / 2, math.max(top + 1, bottom)), p);
    }
  }

  @override
  bool shouldRepaint(covariant _RoomCandlePainter oldDelegate) =>
      oldDelegate.bars != bars || oldDelegate.rise != rise ||
      oldDelegate.fall != fall || oldDelegate.grid != grid;
}

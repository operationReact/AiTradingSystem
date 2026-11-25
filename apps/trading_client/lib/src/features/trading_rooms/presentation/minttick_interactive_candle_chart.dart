import 'dart:math' as math;
import 'package:flutter/material.dart';
import '../data/customer_market_repository.dart';

/// Read-only interactive OHLCV. Quotes are references only; no bars are
/// synthesized from ticks and no missing historical sessions are invented.
class MintTickInteractiveCandleChart extends StatefulWidget {
  const MintTickInteractiveCandleChart({
    required this.bars,
    required this.onSelect,
    this.selectedIndex,
    this.lastTradedPrice,
    this.liveQuote = false,
    super.key,
  });
  final List<CustomerCandle> bars;
  final ValueChanged<int> onSelect;
  final int? selectedIndex;
  final double? lastTradedPrice;
  final bool liveQuote;

  @override
  State<MintTickInteractiveCandleChart> createState() => _ChartState();
}

class _ChartState extends State<MintTickInteractiveCandleChart> {
  static const double left = 8, right = 58;
  int _first = 0, _count = 0, _startFirst = 0, _startCount = 0;
  double _startX = 0, _anchor = 0;
  bool _volume = true, _sma20 = false;
  int _touches = 0;
  int? _primaryPointer;
  Offset? _pointerStart;
  bool _moved = false;

  @override
  void initState() { super.initState(); _recent(); }

  @override
  void didUpdateWidget(covariant MintTickInteractiveCandleChart old) {
    super.didUpdateWidget(old);
    if (identical(old.bars, widget.bars)) return;
    // Replacing a verified server series must not reset a trader's viewport.
    // A right-anchored viewport follows new bars; a manually panned viewport
    // remains anchored to the original exchange timestamp.
    if (old.bars.isEmpty || widget.bars.isEmpty || _count == 0) {
      _recent();
      return;
    }
    final wasAtRight = _first + _count >= old.bars.length;
    final wasFitAll = _first == 0 && _count == old.bars.length;
    if (wasFitAll) {
      _first = 0;
      _count = widget.bars.length;
      return;
    }
    final oldest = old.bars[_first.clamp(0, old.bars.length - 1).toInt()]
        .candleTime.toUtc();
    _count = math.min(_count, widget.bars.length);
    if (wasAtRight) {
      _first = math.max(0, widget.bars.length - _count);
      return;
    }
    final anchorIndex = widget.bars.indexWhere(
        (b) => !b.candleTime.toUtc().isBefore(oldest));
    _first = (anchorIndex < 0 ? 0 : anchorIndex)
        .clamp(0, widget.bars.length - _count).toInt();
  }

  void _recent() {
    _count = math.min(42, widget.bars.length);
    _first = math.max(0, widget.bars.length - _count);
  }

  void _zoom(double factor) {
    if (widget.bars.length < 2) return;
    final end = _first + _count;
    final count = (_count * factor).round()
        .clamp(math.min(6, widget.bars.length), widget.bars.length).toInt();
    setState(() {
      _count = count;
      _first = (end - count).clamp(0, widget.bars.length - count).toInt();
    });
  }

  void _select(Offset pos, double width) {
    final fraction = ((pos.dx - left) / math.max(1.0, width - left - right))
        .clamp(0.0, 0.999999);
    final index = _first + (fraction * _count).floor();
    if (index >= 0 && index < widget.bars.length) widget.onSelect(index);
  }

  void _scaleStart(ScaleStartDetails details, double width) {
    _startFirst = _first;
    _startCount = _count;
    _startX = details.localFocalPoint.dx;
    final fraction = ((_startX - left) / math.max(1, width - left - right))
        .clamp(0.0, 1.0);
    _anchor = _startFirst + fraction * _startCount;
  }

  void _scaleUpdate(ScaleUpdateDetails details, double width) {
    if (_startCount == 0) return;
    final plotWidth = math.max(1.0, width - left - right);
    if (details.pointerCount > 1) {
      final count = (_startCount / math.max(0.05, details.scale)).round()
          .clamp(math.min(6, widget.bars.length), widget.bars.length).toInt();
      final fraction = ((details.localFocalPoint.dx - left) / plotWidth)
          .clamp(0.0, 1.0);
      final first = (_anchor - fraction * count).round()
          .clamp(0, widget.bars.length - count).toInt();
      if (first != _first || count != _count) {
        setState(() { _first = first; _count = count; });
      }
    } else {
      final delta = ((details.localFocalPoint.dx - _startX) *
          _startCount / plotWidth).round();
      final first = (_startFirst - delta)
          .clamp(0, widget.bars.length - _count).toInt();
      if (first != _first) setState(() => _first = first);
    }
  }

  void _fullscreen() {
    showDialog<void>(context: context, builder: (dialogContext) =>
      Dialog.fullscreen(child: SafeArea(child: Column(children: [
        Row(children: [
          IconButton(tooltip: 'Close expanded chart',
              onPressed: () => Navigator.pop(dialogContext),
              icon: const Icon(Icons.close)),
          const Expanded(child: Text('Historical OHLCV · expanded chart')),
        ]),
        Expanded(child: Padding(padding: const EdgeInsets.all(12),
          child: MintTickInteractiveCandleChart(
            bars: widget.bars, onSelect: widget.onSelect,
            selectedIndex: widget.selectedIndex,
            lastTradedPrice: widget.lastTradedPrice,
            liveQuote: widget.liveQuote,
          ))),
      ]))),
    );
  }

  Widget _action(IconData icon, String label, VoidCallback? callback, Key key) =>
      IconButton(key: key, tooltip: label, onPressed: callback, icon: Icon(icon),
        iconSize: 19, padding: EdgeInsets.zero,
        constraints: const BoxConstraints(minHeight: 32, minWidth: 32));

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final count = widget.bars.length;
    return Column(children: [
      SizedBox(height: 38, child: Row(children: [
        Expanded(child: Text(
          count == 0 ? 'No stored candles' : '${_first + 1}–${_first + _count} / $count bars',
          key: const ValueKey('stock-chart-visible-range'),
          style: Theme.of(context).textTheme.labelSmall
              ?.copyWith(color: scheme.onSurfaceVariant),
        )),
        _action(Icons.remove, 'Zoom out', count < 2 ? null : () => _zoom(1.5),
            const ValueKey('stock-chart-zoom-out')),
        _action(Icons.add, 'Zoom in', count < 2 ? null : () => _zoom(1 / 1.5),
            const ValueKey('stock-chart-zoom-in')),
        _action(Icons.first_page, 'Fit history', count < 2 ? null : () {
          setState(() { _first = 0; _count = count; });
        }, const ValueKey('stock-chart-fit')),
        _action(Icons.last_page, 'Jump to latest', count < 2 ? null : () =>
            setState(_recent), const ValueKey('stock-chart-latest')),
        _action(Icons.fullscreen, 'Expand chart', _fullscreen,
            const ValueKey('stock-chart-fullscreen')),
      ])),
      const SizedBox(height: 4),
      Expanded(child: LayoutBuilder(builder: (context, constraints) =>
        Listener(
          behavior: HitTestBehavior.opaque,
          onPointerDown: (event) {
            _touches++;
            if (_touches == 1) {
              _primaryPointer = event.pointer;
              _pointerStart = event.localPosition;
              _moved = false;
            } else {
              _moved = true;
            }
          },
          onPointerMove: (event) {
            if (_primaryPointer == event.pointer &&
                _pointerStart != null &&
                (event.localPosition - _pointerStart!).distance > 8) {
              _moved = true;
            }
          },
          onPointerUp: (event) {
            final inspect = _touches == 1 &&
                _primaryPointer == event.pointer && !_moved;
            _touches = math.max(0, _touches - 1);
            if (_primaryPointer == event.pointer) {
              _primaryPointer = null;
              _pointerStart = null;
            }
            if (inspect) _select(event.localPosition, constraints.maxWidth);
          },
          onPointerCancel: (event) {
            _touches = math.max(0, _touches - 1);
            if (_primaryPointer == event.pointer) {
              _primaryPointer = null;
              _pointerStart = null;
            }
          },
          child: GestureDetector(
            key: const ValueKey('stock-chart-gesture'),
            behavior: HitTestBehavior.opaque,
            onDoubleTap: () => setState(_recent),
          onScaleStart: (details) => _scaleStart(details, constraints.maxWidth),
          onScaleUpdate: (details) => _scaleUpdate(details, constraints.maxWidth),
          child: CustomPaint(
            painter: _CandleViewport(
              bars: widget.bars, first: _first, count: _count,
              selected: widget.selectedIndex,
              up: scheme.primary, down: scheme.error, grid: scheme.outlineVariant,
              text: scheme.onSurfaceVariant,
              quote: widget.lastTradedPrice, live: widget.liveQuote,
              volume: _volume, sma20: _sma20,
            ),
            child: const SizedBox.expand(),
          ),
          ),
        ))),
      SizedBox(height: 34, child: Row(
        mainAxisAlignment: MainAxisAlignment.end, children: [
          TextButton(key: const ValueKey('stock-chart-volume'),
            style: TextButton.styleFrom(
                foregroundColor: _volume ? scheme.primary : scheme.onSurfaceVariant,
                visualDensity: VisualDensity.compact),
            onPressed: () => setState(() => _volume = !_volume),
            child: Text(_volume ? 'Volume ✓' : 'Volume')),
          TextButton(key: const ValueKey('stock-chart-sma20'),
            style: TextButton.styleFrom(
                foregroundColor: _sma20 ? scheme.primary : scheme.onSurfaceVariant,
                visualDensity: VisualDensity.compact),
            onPressed: () => setState(() => _sma20 = !_sma20),
            child: Text(_sma20 ? 'MA20 ✓' : 'MA20')),
        ],
      )),
    ]);
  }
}

class _CandleViewport extends CustomPainter {
  const _CandleViewport({
    required this.bars, required this.first, required this.count,
    required this.selected, required this.up, required this.down,
    required this.grid, required this.text, required this.quote,
    required this.live, required this.volume, required this.sma20,
  });
  final List<CustomerCandle> bars;
  final int first, count;
  final int? selected;
  final Color up, down, grid, text;
  final double? quote;
  final bool live, volume, sma20;

  @override
  void paint(Canvas canvas, Size size) {
    if (count == 0 || size.width <= 85 || size.height <= 80) return;
    const left = 8.0, right = 58.0, top = 10.0;
    final w = size.width - left - right;
    final priceBottom = size.height * (volume ? 0.74 : 0.85);
    final volumeTop = size.height * 0.80;
    final volumeBottom = size.height * 0.93;
    final visible = bars.skip(first).take(count).toList(growable: false);
    if (visible.isEmpty) return;
    final low = visible.map((b) => b.low.toDouble()).reduce(math.min);
    final high = visible.map((b) => b.high.toDouble()).reduce(math.max);
    final padding = math.max(0.01, (high - low) * 0.08);
    final minP = math.max(0.000001, low - padding);
    final maxP = high + padding, span = maxP - minP;
    final gap = w / count;
    final barWidth = math.max(1.0, math.min(15.0, gap * 0.70));
    double x(int i) => left + gap * (i + 0.5);
    double y(num p) => top + (maxP - p.toDouble()) / span * (priceBottom - top);
    final maxV = math.max(1, visible.map((b) => b.volume).reduce(math.max));

    canvas.save();
    canvas.clipRect(Rect.fromLTWH(left, 0, w, size.height));
    for (var j = 0; j < 4; j++) {
      final yy = top + (priceBottom - top) * j / 3;
      canvas.drawLine(Offset(left, yy), Offset(left + w, yy),
        Paint()..color = grid..strokeWidth = 0.7);
    }
    for (var i = 0; i < visible.length; i++) {
      final bar = visible[i], px = x(i);
      final color = bar.close >= bar.open ? up : down;
      canvas.drawLine(Offset(px, y(bar.high)), Offset(px, y(bar.low)),
        Paint()..color = color..strokeWidth = 1);
      canvas.drawRect(Rect.fromLTWH(px - barWidth / 2,
          math.min(y(bar.open), y(bar.close)), barWidth,
          math.max(1.0, (y(bar.open) - y(bar.close)).abs())),
          Paint()..color = color);
      if (volume) {
        final height = (volumeBottom - volumeTop) * bar.volume / maxV;
        canvas.drawRect(Rect.fromLTWH(px - barWidth / 2,
          volumeBottom - height, barWidth, height),
          Paint()..color = color.withValues(alpha: 0.5));
      }
    }
    if (sma20 && bars.length >= 20) {
      final path = Path();
      var begun = false;
      for (var i = 0; i < visible.length; i++) {
        final idx = first + i;
        if (idx < 19) continue;
        var sum = 0.0;
        for (var j = idx - 19; j <= idx; j++) {
          sum += bars[j].close.toDouble();
        }
        if (begun) {
          path.lineTo(x(i), y(sum / 20));
        } else {
          path.moveTo(x(i), y(sum / 20));
          begun = true;
        }
      }
      canvas.drawPath(path, Paint()
        ..color = const Color(0xFFF3BB63)
        ..strokeWidth = 1.5
        ..style = PaintingStyle.stroke);
    }
    if (selected != null && selected! >= first && selected! < first + count) {
      final xx = x(selected! - first);
      canvas.drawLine(Offset(xx, top),
          Offset(xx, volume ? volumeBottom : priceBottom),
          Paint()..color = text..strokeWidth = 0.8);
      canvas.drawCircle(Offset(xx, y(bars[selected!].close)), 3, Paint()..color = text);
    }
    canvas.restore();
    for (var j = 0; j < 4; j++) {
      _label(canvas, '₹${(maxP - span * j / 3).toStringAsFixed(2)}',
          Offset(left + w + 3, top + (priceBottom - top) * j / 3 - 8), text);
    }
    if (quote != null && quote! > 0) {
      if (quote! >= minP && quote! <= maxP) {
        final yy = y(quote!);
        canvas.drawLine(Offset(left, yy), Offset(left + w, yy),
            Paint()..color = live ? up : text..strokeWidth = 1);
        _label(canvas, '₹${quote!.toStringAsFixed(2)}',
            Offset(left + w + 3, yy - 9), live ? up : text);
      } else {
        // Off-range quotes must not flatten the historical candles.
        _label(canvas,
            '${live ? 'LIVE' : 'LAST'} ₹${quote!.toStringAsFixed(2)} '
            '${quote! > maxP ? '↑' : '↓'} outside range',
            const Offset(left + 4, top + 2), text);
      }
    }
    for (final i in <int>{0, count ~/ 2, count - 1}) {
      if (i < 0 || i >= visible.length) continue;
      final d = visible[i].candleTime.toUtc()
          .add(const Duration(hours: 5, minutes: 30));
      final stamp = '${d.day}/${d.month}/${d.year.toString().substring(2)}';
      _label(canvas, stamp, Offset(
          (x(i) - (i == count - 1 ? 45 : 0)).clamp(left, left + w - 46),
          size.height - 14), text);
    }
  }

  void _label(Canvas canvas, String value, Offset location, Color color) {
    final painter = TextPainter(
      text: TextSpan(text: value, style: TextStyle(color: color, fontSize: 10)),
      textDirection: TextDirection.ltr,
    )..layout();
    painter.paint(canvas, location);
  }

  @override
  bool shouldRepaint(covariant _CandleViewport previous) =>
      !identical(bars, previous.bars) || first != previous.first ||
      count != previous.count || selected != previous.selected ||
      up != previous.up || down != previous.down || grid != previous.grid ||
      text != previous.text || quote != previous.quote ||
      live != previous.live || volume != previous.volume ||
      sma20 != previous.sma20;
}

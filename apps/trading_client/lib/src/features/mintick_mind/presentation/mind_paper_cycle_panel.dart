import 'package:flutter/material.dart';

import '../data/mind_paper_cycle_repository.dart';
import 'mind_widgets.dart';

String _inr(int paise) {
  final negative = paise < 0;
  final abs = paise.abs();
  final rupees = abs ~/ 100, rem = (abs % 100).toString().padLeft(2, '0');
  return '${negative ? '-' : ''}₹$rupees.$rem';
}

Color _outcomeColor(MindPaperOutcome o) => switch (o) {
      MindPaperOutcome.filled => MindPalette.mint,
      MindPaperOutcome.noEntry => MindPalette.blue,
      MindPaperOutcome.failedUnknown => MindPalette.red,
      _ => MindPalette.yellow,
    };

/// Real Mind PAPER strategy cycle (issue #998 PR C) inside Trading Room Detail.
class MindPaperCyclePanel extends StatefulWidget {
  const MindPaperCyclePanel({required this.repository, super.key});
  final MindPaperCycleRepository repository;

  @override
  State<MindPaperCyclePanel> createState() => _MindPaperCyclePanelState();
}

class _MindPaperCyclePanelState extends State<MindPaperCyclePanel> {
  late final Future<(List<MindPaperRoom>, List<MindPaperInstrument>)> _setup = _load();
  String? _roomId;
  int? _instrumentId;
  String _interval = 'FIFTEEN_MINUTE';
  bool _running = false;
  MindPaperCycleResult? _result;
  List<MindPaperCycle> _recent = const [];
  String? _error;

  Future<(List<MindPaperRoom>, List<MindPaperInstrument>)> _load() async {
    final rooms = await widget.repository.rooms();
    final instruments = await widget.repository.instruments();
    if (mounted) {
      setState(() {
        _roomId = rooms.isEmpty ? null : rooms.first.id;
        _instrumentId = instruments.isEmpty ? null : instruments.first.instrumentId;
      });
      if (_roomId != null) await _loadRecent();
    }
    return (rooms, instruments);
  }

  Future<void> _loadRecent() async {
    final room = _roomId;
    if (room == null) return;
    try {
      final recent = await widget.repository.recent(room);
      if (mounted && room == _roomId) setState(() => _recent = recent);
    } catch (_) {
      if (mounted) setState(() => _recent = const []);
    }
  }

  Future<void> _run() async {
    final room = _roomId, instrument = _instrumentId;
    if (_running || room == null || instrument == null) return;
    setState(() { _running = true; _error = null; });
    try {
      final result = await widget.repository.run(roomId: room, instrumentId: instrument, interval: _interval);
      if (!mounted) return;
      setState(() { _result = result; _running = false; });
      await _loadRecent();
    } catch (_) {
      // Never auto-retry: the owner checks history/orders before trying again.
      if (mounted) {
        setState(() {
          _running = false;
          _error = 'Cycle request failed or outcome unknown. Not retried — check history below before running again.';
        });
      }
      await _loadRecent();
    }
  }

  @override
  Widget build(BuildContext context) => MindPanel(
        key: const ValueKey('mind-paper-cycle-panel'),
        highlight: true,
        child: FutureBuilder<(List<MindPaperRoom>, List<MindPaperInstrument>)>(
          future: _setup,
          builder: (context, snap) {
            final header = Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              const Row(children: [
                Icon(Icons.play_circle_outline, color: MindPalette.mint, size: 19),
                SizedBox(width: 7),
                Expanded(child: Text('Mind PAPER strategy cycle',
                    style: TextStyle(color: MindPalette.text, fontSize: 15, fontWeight: FontWeight.w800))),
                MindTag('PAPER · VIRTUAL', color: MindPalette.yellow),
              ]),
              const SizedBox(height: 4),
              const Text('SMA_TREND_VOLUME_V1 on stored broker candles → verified live quote → automatic risk '
                  'sizing → existing PAPER engine. No broker order is ever placed.',
                  style: TextStyle(color: MindPalette.muted, fontSize: 11)),
              const SizedBox(height: 10),
            ]);
            if (snap.connectionState != ConnectionState.done) {
              return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [header,
                const Text('Loading rooms and watchlist…', style: TextStyle(color: MindPalette.muted, fontSize: 12))]);
            }
            if (snap.hasError || snap.data == null) {
              return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [header,
                const Text('Rooms or watchlist unavailable. Nothing was run.',
                    key: ValueKey('mind-paper-setup-error'), style: TextStyle(color: MindPalette.red, fontSize: 12))]);
            }
            final (rooms, instruments) = snap.data!;
            if (rooms.isEmpty || instruments.isEmpty) {
              return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [header,
                Text(rooms.isEmpty ? 'Fund a PAPER room first (Trading Rooms → Activate PAPER wallet).'
                    : 'Add an NSE/BSE cash equity to your watchlist first.',
                    key: const ValueKey('mind-paper-setup-missing'),
                    style: const TextStyle(color: MindPalette.text, fontSize: 12))]);
            }
            return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
              header,
              _dropdown<String>('Room', _roomId, [for (final r in rooms) (r.id, r.name)],
                  (v) { setState(() { _roomId = v; _result = null; _recent = const []; }); _loadRecent(); },
                  const ValueKey('mind-paper-room')),
              const SizedBox(height: 8),
              _dropdown<int>('Instrument', _instrumentId, [for (final i in instruments) (i.instrumentId, i.label)],
                  (v) => setState(() => _instrumentId = v), const ValueKey('mind-paper-instrument')),
              const SizedBox(height: 8),
              Wrap(spacing: 6, children: [
                for (final (code, label) in const [('FIVE_MINUTE', '5m'), ('FIFTEEN_MINUTE', '15m'), ('ONE_HOUR', '1h'), ('ONE_DAY', '1D')])
                  ChoiceChip(label: Text(label), selected: _interval == code,
                      onSelected: (_) => setState(() => _interval = code),
                      backgroundColor: MindPalette.card, selectedColor: MindPalette.panel,
                      side: const BorderSide(color: MindPalette.border),
                      labelStyle: TextStyle(fontSize: 11, color: _interval == code ? MindPalette.mint : MindPalette.text)),
              ]),
              const SizedBox(height: 10),
              FilledButton.icon(
                key: const ValueKey('mind-paper-run'),
                onPressed: _running ? null : _run,
                style: FilledButton.styleFrom(backgroundColor: MindPalette.mint, foregroundColor: Colors.black),
                icon: _running
                    ? const SizedBox(width: 14, height: 14, child: CircularProgressIndicator(strokeWidth: 2))
                    : const Icon(Icons.play_arrow_rounded, size: 18),
                label: Text(_running ? 'Running PAPER cycle…' : 'Run PAPER strategy cycle'),
              ),
              if (_error != null) ...[
                const SizedBox(height: 8),
                Text(_error!, key: const ValueKey('mind-paper-error'), style: const TextStyle(color: MindPalette.red, fontSize: 11.5)),
              ],
              if (_result != null) ...[const SizedBox(height: 12), _resultView(_result!)],
              if (_recent.isNotEmpty) ...[
                const SizedBox(height: 12),
                const Text('RECENT CYCLES', style: TextStyle(color: MindPalette.muted, fontSize: 10.5,
                    fontWeight: FontWeight.w700, letterSpacing: .6)),
                for (final c in _recent.take(8))
                  Padding(padding: const EdgeInsets.only(top: 5), child: Row(children: [
                    Expanded(child: Text(
                      '${c.createdAt.toUtc().toIso8601String().substring(5, 16)}Z  ${c.tradingSymbol}  '
                      '${c.side ?? c.signalAction ?? ''} ${c.quantity ?? ''}  ${c.reasons.take(2).join(', ')}',
                      maxLines: 1, overflow: TextOverflow.ellipsis,
                      style: const TextStyle(color: MindPalette.text, fontSize: 10.5))),
                    const SizedBox(width: 6),
                    MindTag(c.outcome.label, color: _outcomeColor(c.outcome)),
                  ])),
              ],
            ]);
          },
        ),
      );

  Widget _dropdown<T>(String label, T? value, List<(T, String)> options, ValueChanged<T> onChanged, Key key) =>
      DropdownButtonFormField<T>(
        key: key,
        initialValue: value,
        isExpanded: true,
        dropdownColor: MindPalette.panel,
        decoration: InputDecoration(labelText: label, isDense: true,
            labelStyle: const TextStyle(color: MindPalette.muted, fontSize: 12)),
        style: const TextStyle(color: MindPalette.text, fontSize: 13),
        items: [for (final (v, text) in options) DropdownMenuItem<T>(value: v, child: Text(text, overflow: TextOverflow.ellipsis))],
        onChanged: (v) { if (v != null) onChanged(v); },
      );

  Widget _resultView(MindPaperCycleResult r) {
    final c = r.cycle;
    final unrealized = r.holdings.fold<int?>(0, (sum, h) =>
        sum == null || h.unrealizedPnlPaise == null ? null : sum + h.unrealizedPnlPaise!);
    Widget kv(String k, String v, {Color color = MindPalette.text}) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 2.5),
        child: Row(children: [
          Expanded(child: Text(k, style: const TextStyle(color: MindPalette.muted, fontSize: 11.5))),
          Flexible(child: Text(v, textAlign: TextAlign.right, style: TextStyle(color: color, fontSize: 11.5, fontWeight: FontWeight.w700))),
        ]));
    return Container(
      key: const ValueKey('mind-paper-result'),
      padding: const EdgeInsets.all(11),
      decoration: BoxDecoration(color: MindPalette.card, borderRadius: BorderRadius.circular(12),
          border: Border.all(color: MindPalette.border)),
      child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        Row(children: [
          Expanded(child: Text('${c.tradingSymbol} · ${c.signalAction ?? 'NO SIGNAL'}',
              style: const TextStyle(color: MindPalette.text, fontSize: 13, fontWeight: FontWeight.w800))),
          MindTag(c.outcome.label, color: _outcomeColor(c.outcome)),
        ]),
        const SizedBox(height: 6),
        for (final s in c.stages)
          Padding(padding: const EdgeInsets.symmetric(vertical: 1.5), child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
            SizedBox(width: 96, child: Text(s.code, style: const TextStyle(color: MindPalette.muted, fontSize: 10))),
            SizedBox(width: 68, child: Text(s.result, style: TextStyle(fontSize: 10, fontWeight: FontWeight.w800,
                color: s.result == 'PASSED' || s.result == 'FILLED' || s.result == 'EVALUATED' || s.result == 'POSTED'
                    ? MindPalette.mint : MindPalette.yellow))),
            Expanded(child: Text(s.detail, style: const TextStyle(color: MindPalette.text, fontSize: 10))),
          ])),
        const Divider(color: MindPalette.border, height: 16),
        if (c.outcome == MindPaperOutcome.filled)
          kv('Virtual fill', '${c.side} ${c.quantity} @ ${r.fillPrice ?? c.limitPrice}', color: MindPalette.mint),
        if (c.stopPrice != null) kv('Stop / target', '${c.stopPrice} / ${c.targetPrice ?? '-'}'),
        kv('Virtual cash', _inr(r.availableCashPaise)),
        kv('Realized P&L', _inr(r.realizedPnlPaise), color: r.realizedPnlPaise < 0 ? MindPalette.red : MindPalette.text),
        kv('Unrealized P&L', unrealized == null ? 'Mark unavailable' : _inr(unrealized)),
        kv('Open positions', '${r.holdings.length}'),
        const SizedBox(height: 4),
        Text('Data: ${c.dataLabel}', style: const TextStyle(color: MindPalette.muted, fontSize: 9.5)),
      ]),
    );
  }
}

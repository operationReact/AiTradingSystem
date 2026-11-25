import 'package:flutter/material.dart';
import '../data/mind_opening_range_replay_repository.dart';
import 'mind_widgets.dart';

/// Historical ORB evidence. Reproduction never becomes an executable signal.
class MindOpeningRangeReplayPanel extends StatefulWidget {
  const MindOpeningRangeReplayPanel({required this.repository, super.key});
  final MindOpeningRangeReplayRepository repository;

  @override
  State<MindOpeningRangeReplayPanel> createState() =>
      _MindOpeningRangeReplayPanelState();
}

class _MindOpeningRangeReplayPanelState
    extends State<MindOpeningRangeReplayPanel> {
  late Future<MindOpeningRangeReplay> _request;

  @override
  void initState() {
    super.initState();
    _request = widget.repository.load();
  }

  @override
  void didUpdateWidget(covariant MindOpeningRangeReplayPanel oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!identical(widget.repository, oldWidget.repository)) {
      _request = widget.repository.load();
    }
  }

  void _refresh() => setState(() => _request = widget.repository.load());

  @override
  Widget build(BuildContext context) => MindPanel(
    key: const ValueKey('mind-opening-range-replay'),
    child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Row(children: [
        const Icon(Icons.candlestick_chart_outlined,
            color: MindPalette.mint, size: 20),
        const SizedBox(width: 8),
        const Expanded(child: Text('Intraday opening-range replay',
            style: TextStyle(color: MindPalette.text,
                fontWeight: FontWeight.w800, fontSize: 14))),
        IconButton(
          tooltip: 'Refresh opening-range replay',
          onPressed: _refresh,
          icon: const Icon(Icons.refresh, color: MindPalette.muted),
        ),
      ]),
      const SizedBox(height: 5),
      const Text('NSE/BSE cash-equity research · 09:15–09:30 IST · '
          '1, 3 or 5-minute bars',
          style: TextStyle(color: MindPalette.muted, fontSize: 11)),
      const SizedBox(height: 11),
      FutureBuilder<MindOpeningRangeReplay>(
        future: _request,
        builder: (context, snapshot) {
          if (snapshot.connectionState != ConnectionState.done) {
            return const Row(children: [
              SizedBox(width: 16, height: 16,
                  child: CircularProgressIndicator(strokeWidth: 2)),
              SizedBox(width: 10),
              Expanded(child: Text('Reproducing captured ORB inputs…',
                  style: TextStyle(color: MindPalette.muted, fontSize: 12))),
            ]);
          }
          if (!snapshot.hasData || snapshot.hasError) {
            return const Text('Opening-range evidence unavailable. '
                'No paper trade or broker order was submitted.',
                key: ValueKey('mind-opening-range-unavailable'),
                style: TextStyle(color: MindPalette.yellow, fontSize: 12));
          }
          final replay = snapshot.data!;
          return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Row(children: [
              Expanded(child: Text('Replay: ${replay.replayStatus}',
                  style: const TextStyle(color: MindPalette.text,
                      fontWeight: FontWeight.w700, fontSize: 12))),
              Text(replay.decisionPosture,
                  key: const ValueKey('mind-opening-range-posture'),
                  style: const TextStyle(color: MindPalette.yellow,
                      fontWeight: FontWeight.w800, fontSize: 12)),
            ]),
            if (replay.instrument != null) ...[
              const SizedBox(height: 5),
              Text('${replay.instrument} · ${replay.interval}',
                  style: const TextStyle(color: MindPalette.text,
                      fontWeight: FontWeight.w700, fontSize: 13)),
            ],
            if (replay.signalAt != null)
              Text('Signal cutoff: ${replay.signalAt!.toUtc().toIso8601String()} UTC',
                  style: const TextStyle(color: MindPalette.muted, fontSize: 10)),
            const SizedBox(height: 8),
            const Text('Input custody: required · Capital gate: NOT EVALUATED',
                style: TextStyle(color: MindPalette.muted, fontSize: 11)),
            const Text('Paper/shadow: NOT ENROLLED · Execution: DISABLED',
                style: TextStyle(color: MindPalette.muted, fontSize: 11)),
            const SizedBox(height: 8),
            for (final reason in replay.blockingReasons)
              Padding(
                padding: const EdgeInsets.only(bottom: 3),
                child: Text('• ${reason.replaceAll('_', ' ')}',
                    style: const TextStyle(color: MindPalette.yellow,
                        fontSize: 10)),
              ),
            const SizedBox(height: 7),
            const Text('Reproduced research is not a buy signal. No orders submitted.',
                key: ValueKey('mind-opening-range-no-execution'),
                style: TextStyle(color: MindPalette.muted, fontSize: 11,
                    fontWeight: FontWeight.w700)),
          ]);
        },
      ),
    ]),
  );
}

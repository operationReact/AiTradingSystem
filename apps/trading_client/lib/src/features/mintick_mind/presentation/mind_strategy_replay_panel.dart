import 'package:flutter/material.dart';
import '../data/mind_strategy_replay_repository.dart';
import 'mind_widgets.dart';

/// A historical deterministic replay, never an executable trading decision.
class MindStrategyReplayPanel extends StatefulWidget {
  const MindStrategyReplayPanel({required this.repository, super.key});
  final MindStrategyReplayRepository repository;

  @override
  State<MindStrategyReplayPanel> createState() => _MindStrategyReplayPanelState();
}

class _MindStrategyReplayPanelState extends State<MindStrategyReplayPanel> {
  late Future<MindStrategyReplay> _request;

  @override
  void initState() {
    super.initState();
    _request = widget.repository.load();
  }

  @override
  void didUpdateWidget(covariant MindStrategyReplayPanel oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!identical(widget.repository, oldWidget.repository)) {
      _request = widget.repository.load();
    }
  }

  void _refresh() => setState(() => _request = widget.repository.load());

  @override
  Widget build(BuildContext context) => MindPanel(
    key: const ValueKey('mind-live-strategy-replay'),
    child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Row(children: [
        const Icon(Icons.rule_outlined, color: MindPalette.mint, size: 20),
        const SizedBox(width: 8),
        const Expanded(child: Text('Deterministic strategy replay',
            style: TextStyle(color: MindPalette.text,
                fontWeight: FontWeight.w800, fontSize: 14))),
        IconButton(
          tooltip: 'Refresh strategy replay',
          onPressed: _refresh,
          icon: const Icon(Icons.refresh, color: MindPalette.muted),
        ),
      ]),
      const SizedBox(height: 5),
      const Text('Recorded 5-minute Indian cash-equity candles · '
          'Moving Average Reclaim v1',
          style: TextStyle(color: MindPalette.muted, fontSize: 11)),
      const SizedBox(height: 11),
      FutureBuilder<MindStrategyReplay>(
        future: _request,
        builder: (context, snapshot) {
          if (snapshot.connectionState != ConnectionState.done) {
            return const Row(children: [
              SizedBox(width: 16, height: 16,
                  child: CircularProgressIndicator(strokeWidth: 2)),
              SizedBox(width: 10),
              Expanded(child: Text('Replaying persisted research…',
                  style: TextStyle(color: MindPalette.muted, fontSize: 12))),
            ]);
          }
          if (!snapshot.hasData || snapshot.hasError) {
            return const Text('Strategy evidence unavailable. '
                'No replay, paper trade or broker order was submitted.',
                key: ValueKey('mind-replay-unavailable'),
                style: TextStyle(color: MindPalette.yellow, fontSize: 12));
          }
          final replay = snapshot.data!;
          return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Row(children: [
              Expanded(child: Text('Replay: ${replay.replayStatus}',
                  style: const TextStyle(color: MindPalette.text,
                      fontWeight: FontWeight.w700, fontSize: 12))),
              Text(replay.decisionPosture,
                  key: const ValueKey('mind-replay-posture'),
                  style: const TextStyle(color: MindPalette.yellow,
                      fontWeight: FontWeight.w800, fontSize: 12)),
            ]),
            if (replay.instrument != null) ...[
              const SizedBox(height: 5),
              Text(replay.instrument!,
                  style: const TextStyle(color: MindPalette.text,
                    fontWeight: FontWeight.w700, fontSize: 13)),
            ],
            if (replay.signalAt != null)
              Text('Historical signal: ${replay.signalAt!.toUtc().toIso8601String()} UTC',
                  style: const TextStyle(color: MindPalette.muted, fontSize: 10)),
            const SizedBox(height: 8),
            const Text('Capital gate: NOT EVALUATED · Paper/shadow: NOT ENROLLED',
                style: TextStyle(color: MindPalette.muted, fontSize: 11)),
            const Text('Independent predictive validation: NOT VERIFIED',
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
            const Text('No orders submitted. A matched replay is NOT a buy signal.',
                key: ValueKey('mind-replay-no-execution'),
                style: TextStyle(color: MindPalette.muted, fontSize: 11,
                    fontWeight: FontWeight.w700)),
          ]);
        },
      ),
    ]),
  );
}

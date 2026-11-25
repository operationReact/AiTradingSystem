import 'package:flutter/material.dart';

import '../data/mind_readiness_repository.dart';
import 'mind_widgets.dart';

/// First production-data Mind surface. It never falls back to the simulated
/// Agent Council, decisions or backtest fixtures on transport/parse failure.
class MindReadinessPanel extends StatefulWidget {
  const MindReadinessPanel({required this.repository, super.key});
  final MindReadinessRepository repository;

  @override
  State<MindReadinessPanel> createState() => _MindReadinessPanelState();
}

class _MindReadinessPanelState extends State<MindReadinessPanel> {
  late Future<MindReadiness> _pending;

  @override
  void initState() {
    super.initState();
    _pending = widget.repository.load();
  }

  @override
  void didUpdateWidget(covariant MindReadinessPanel oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!identical(oldWidget.repository, widget.repository)) {
      _pending = widget.repository.load();
    }
  }

  void _refresh() => setState(() => _pending = widget.repository.load());

  @override
  Widget build(BuildContext context) => MindPanel(
    key: const ValueKey('mind-server-readiness-panel'),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(children: [
          const Icon(Icons.verified_user_outlined,
              size: 20, color: MindPalette.mint),
          const SizedBox(width: 8),
          const Expanded(child: Text('Server evidence',
              style: TextStyle(color: MindPalette.text,
                  fontSize: 14, fontWeight: FontWeight.w800))),
          IconButton(
            tooltip: 'Refresh Mind evidence',
            onPressed: _refresh,
            icon: const Icon(Icons.refresh, color: MindPalette.muted, size: 20),
          ),
        ]),
        const SizedBox(height: 5),
        const Text('Authenticated, read-only observations. Not a trading signal.',
          style: TextStyle(color: MindPalette.muted, fontSize: 11)),
        const SizedBox(height: 11),
        FutureBuilder<MindReadiness>(
          future: _pending,
          builder: (context, snapshot) {
            if (snapshot.connectionState != ConnectionState.done) {
              return const Row(children: [
                SizedBox(width: 14, height: 14,
                    child: CircularProgressIndicator(strokeWidth: 2)),
                SizedBox(width: 9),
                Expanded(child: Text('Checking source-backed evidence…',
                    style: TextStyle(color: MindPalette.muted, fontSize: 12))),
              ]);
            }
            if (snapshot.hasError || !snapshot.hasData) {
              return const Text(
                'Mind evidence unavailable. No verified decisions or orders. '
                'Retry after checking the backend connection.',
                key: ValueKey('mind-readiness-unavailable'),
                style: TextStyle(color: MindPalette.yellow, fontSize: 12),
              );
            }
            final evidence = snapshot.data!;
            return Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                LayoutBuilder(builder: (context, bounds) {
                  final market = _source('Market feed', evidence.market);
                  final research = _source('Research scans', evidence.research);
                  if (bounds.maxWidth >= 500) {
                    return Row(crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Expanded(child: market),
                        const SizedBox(width: 12),
                        Expanded(child: research),
                      ]);
                  }
                  return Column(children: [
                    market,
                    const SizedBox(height: 10),
                    research,
                  ]);
                }),
                const SizedBox(height: 12),
                const Row(children: [
                  Icon(Icons.lock_outline, size: 16, color: MindPalette.yellow),
                  SizedBox(width: 7),
                  Expanded(child: Text(
                    'ABSTAIN · No validated trade decision · Execution disabled',
                    key: ValueKey('mind-readiness-abstain'),
                    style: TextStyle(color: MindPalette.yellow,
                      fontSize: 11, fontWeight: FontWeight.w700),
                  )),
                ]),
              ],
            );
          },
        ),
      ],
    ),
  );

  Widget _source(String title, MindEvidence evidence) {
    final status = switch (evidence.status) {
      'STORED' => 'Stored observation · not live',
      'RECORDED' => 'Completed scan · not a signal',
      'STALE' => 'Stale',
      'PARTIAL' => 'Partial · unusable',
      'FAILED' => 'Failed · unusable',
      _ => 'Unavailable',
    };
    final timestamp = evidence.asOf?.toUtc().toIso8601String();
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Text(title, style: const TextStyle(color: MindPalette.text,
          fontWeight: FontWeight.w700, fontSize: 12)),
      const SizedBox(height: 4),
      Text(status, style: const TextStyle(color: MindPalette.muted, fontSize: 11)),
      if (timestamp != null)
        Text('Source as-of: $timestamp UTC',
            style: const TextStyle(color: MindPalette.muted, fontSize: 10)),
    ]);
  }
}

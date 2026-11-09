import 'package:flutter/material.dart';

import '../data/mind_prospective_cohort_repository.dart';
import 'mind_widgets.dart';

/// Forward-only research cohort. The signed-in app cannot trigger registration
/// or create positions; only the explicit ADMIN endpoint can freeze membership.
class MindProspectiveCohortPanel extends StatefulWidget {
  const MindProspectiveCohortPanel({required this.repository, super.key});

  final MindProspectiveCohortRepository repository;

  @override
  State<MindProspectiveCohortPanel> createState() =>
      _MindProspectiveCohortPanelState();
}

class _MindProspectiveCohortPanelState extends State<MindProspectiveCohortPanel> {
  late Future<MindProspectiveCohort> _pending;
  bool _expanded = false;

  @override
  void initState() {
    super.initState();
    _pending = widget.repository.load();
  }

  @override
  void didUpdateWidget(covariant MindProspectiveCohortPanel oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!identical(oldWidget.repository, widget.repository)) {
      _pending = widget.repository.load();
    }
  }

  void _refresh() => setState(() => _pending = widget.repository.load());

  @override
  Widget build(BuildContext context) => MindPanel(
    key: const ValueKey('mind-prospective-cohort'),
    child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Row(children: [
        const Icon(Icons.science_outlined, color: MindPalette.mint, size: 21),
        const SizedBox(width: 8),
        const Expanded(child: Text('Prospective multi-stock study',
          style: TextStyle(color: MindPalette.text, fontSize: 14,
            fontWeight: FontWeight.w800))),
        IconButton(
          onPressed: _refresh,
          tooltip: 'Refresh prospective research',
          icon: const Icon(Icons.refresh, color: MindPalette.muted, size: 20),
        ),
      ]),
      const SizedBox(height: 4),
      const Text('Fixed NSE cash-equity sample · future observations only · '
        'Moving Average Reclaim v1',
        style: TextStyle(color: MindPalette.muted, fontSize: 11)),
      const SizedBox(height: 12),
      FutureBuilder<MindProspectiveCohort>(
        future: _pending,
        builder: (context, snapshot) {
          if (snapshot.connectionState != ConnectionState.done) {
            return const Row(children: [
              SizedBox(width: 16, height: 16,
                child: CircularProgressIndicator(strokeWidth: 2)),
              SizedBox(width: 9),
              Expanded(child: Text('Checking prospective cohort status…',
                style: TextStyle(color: MindPalette.muted, fontSize: 11))),
            ]);
          }
          if (snapshot.hasError || !snapshot.hasData) {
            return const Text('Prospective research unavailable. '
                'No observation coverage, performance or orders are verified.',
              key: ValueKey('mind-prospective-error'),
              style: TextStyle(color: MindPalette.yellow, fontSize: 11));
          }
          return _status(snapshot.data!);
        },
      ),
    ]),
  );

  Widget _status(MindProspectiveCohort cohort) => Column(
    crossAxisAlignment: CrossAxisAlignment.start, children: [
      Row(children: [
        Expanded(child: Text(_state(cohort.sourceStatus),
          key: const ValueKey('mind-prospective-status'),
          style: const TextStyle(color: MindPalette.text,
            fontSize: 12, fontWeight: FontWeight.w700))),
        const SizedBox(width: 8),
        const Text('ABSTAIN', style: TextStyle(
          color: MindPalette.yellow, fontSize: 11,
          fontWeight: FontWeight.w800)),
      ]),
      const SizedBox(height: 6),
      if (cohort.sourceStatus == 'UNREGISTERED' ||
          cohort.sourceStatus == 'UNAVAILABLE')
        Text(cohort.sourceStatus == 'UNAVAILABLE'
            ? 'Prospective source evidence is unavailable. '
              'No observations or trading decisions are verified.'
            : 'Research cohort not yet registered. '
              'Only a permitted administrator can freeze a future-only cohort.',
          key: ValueKey('mind-prospective-unregistered'),
          style: TextStyle(color: MindPalette.muted, fontSize: 11))
      else ...[
        Text('${cohort.completedSessions}/${cohort.targetSessions} '
            'completed sessions · ${cohort.readyMembers}/${cohort.memberCount} '
            'stocks have sufficient source coverage',
          style: const TextStyle(color: MindPalette.muted, fontSize: 11)),
        if (cohort.registeredAt != null)
          Text('Frozen: ${_utc(cohort.registeredAt!)}',
            style: const TextStyle(color: MindPalette.muted, fontSize: 10)),
        if (cohort.observationStartsAt != null)
          Text('Observation starts: ${_utc(cohort.observationStartsAt!)}',
            style: const TextStyle(color: MindPalette.muted, fontSize: 10)),
        const SizedBox(height: 8),
        TextButton.icon(
          key: const ValueKey('mind-prospective-expand'),
          onPressed: () => setState(() => _expanded = !_expanded),
          icon: Icon(_expanded ? Icons.expand_less : Icons.expand_more,
            color: MindPalette.mint, size: 17),
          label: Text(_expanded ? 'Hide stock coverage' : 'Review 20-stock cohort',
            style: const TextStyle(color: MindPalette.mint, fontSize: 11)),
        ),
        if (_expanded)
          for (final member in cohort.members)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 4),
              child: Row(children: [
                Expanded(child: Text(member.symbol,
                  style: const TextStyle(color: MindPalette.text,
                    fontSize: 11, fontWeight: FontWeight.w600))),
                const SizedBox(width: 7),
                Text('${member.validCandles}/${member.expectedCandles} bars · '
                    '${member.coveragePercent.toStringAsFixed(1)}%',
                  style: const TextStyle(color: MindPalette.muted, fontSize: 10)),
              ]),
            ),
      ],
      const SizedBox(height: 9),
      const Text('Source coverage is NOT a validated trading edge. '
          'No account risk, PAPER order or LIVE order has been authorized.',
        key: ValueKey('mind-prospective-no-execution'),
        style: TextStyle(color: MindPalette.yellow,
          fontSize: 11, fontWeight: FontWeight.w700)),
      const SizedBox(height: 6),
      const Text('Cohort is deterministic, not liquidity-weighted or '
          'independently exchange-attested. Transaction cost and '
          'prospective outcome evaluation remain pending.',
        style: TextStyle(color: MindPalette.muted, fontSize: 10)),
    ],
  );

  static String _utc(DateTime instant) =>
      '${instant.toUtc().toIso8601String()} UTC';

  static String _state(String status) => switch (status) {
    'UNAVAILABLE' => 'Prospective source evidence unavailable',
    'AWAITING_FUTURE_SESSION' => 'Awaiting future market session',
    'COLLECTING' => 'Collecting future market observations',
    'INSUFFICIENT_COVERAGE' => 'Source coverage incomplete',
    'SOURCE_COVERAGE_READY_RESEARCH_ONLY' =>
      'Source coverage recorded · research only',
    _ => 'Prospective research unregistered',
  };
}

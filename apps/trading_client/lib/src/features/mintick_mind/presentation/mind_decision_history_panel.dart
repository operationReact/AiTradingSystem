import 'package:flutter/material.dart';

import '../data/mind_decision_history_repository.dart';
import '../data/mind_verified_news_repository.dart';
import 'mind_verified_news_panel.dart';
import 'mind_widgets.dart';

/// Persisted research candidate history. Research evidence only: no order, no
/// execution and no live money. Search filters the loaded records on the client.
class MindDecisionHistoryPanel extends StatefulWidget {
  const MindDecisionHistoryPanel({required this.repository, this.verifiedNewsRepository, super.key});

  final MindDecisionHistoryRepository repository;
  final MindVerifiedNewsRepository? verifiedNewsRepository;

  @override
  State<MindDecisionHistoryPanel> createState() => _MindDecisionHistoryPanelState();
}

/// Compact, advisory news impact for one candidate. Evidence direction is not a trade
/// recommendation and never changes the candidate's posture. Every state is explicit: absent
/// context is "not recorded", never "no news".
class _NewsImpact extends StatelessWidget {
  const _NewsImpact({required this.news, super.key});

  final MindNewsContext? news;

  @override
  Widget build(BuildContext context) {
    final type = Theme.of(context).textTheme;
    final n = news;
    if (n == null) {
      return Text(
        'News impact: not recorded for this candidate. This is not the same as no news.',
        style: type.labelSmall);
    }
    switch (n.status) {
      case MindNewsStatus.untrustworthy:
        return Text('News impact: stored news context failed validation and is not used.',
          style: type.labelSmall);
      case MindNewsStatus.unavailable:
        return Text('News impact: lookup was unavailable at scan time. Nothing is inferred.',
          style: type.labelSmall);
      case MindNewsStatus.includedEvidence:
      case MindNewsStatus.noIncludedEvidence:
      case MindNewsStatus.noCoverage:
        break;
    }
    final direction = n.netDirection == null
        ? 'unknown (no included evidence)'
        : '${n.direction.toLowerCase()} (net ${n.netDirection!.toStringAsFixed(2)})';
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('NEWS IMPACT · ADVISORY · NOT A TRADE SIGNAL', style: type.labelSmall),
        Text(
          'Evidence direction: $direction · uncertainty ${n.uncertainty.toLowerCase()} · '
          'included ${n.includedCount} · excluded ${n.excludedCount} · '
          'as of ${n.asOf?.toIso8601String() ?? '—'}',
          style: type.bodySmall),
        if (n.status == MindNewsStatus.noCoverage)
          Text('No analyzed article mentioned this instrument as of the signal.',
            style: type.bodySmall),
        for (final e in n.evidence.take(3))
          Text(
            '${e.source}: ${e.title} · published ${e.publishedAt.toIso8601String()} · '
            'analyzed ${e.analysisAvailableAt.toIso8601String()} · '
            'valid until ${e.validUntil.toIso8601String()} · '
            '${e.included ? 'included' : 'excluded (${e.status.toLowerCase()})'}',
            style: type.labelSmall),
      ],
    );
  }
}

class _MindDecisionHistoryPanelState extends State<MindDecisionHistoryPanel> {
  late Future<MindDecisionHistory> _request;
  String _query = '';

  @override
  void initState() {
    super.initState();
    _request = widget.repository.load();
  }

  @override
  Widget build(BuildContext context) {
    final type = Theme.of(context).textTheme;
    return FutureBuilder<MindDecisionHistory>(
      future: _request,
      builder: (context, snapshot) {
        if (snapshot.connectionState != ConnectionState.done) {
          return const Padding(
            padding: EdgeInsets.symmetric(vertical: 12),
            child: Center(child: CircularProgressIndicator(strokeWidth: 2)));
        }
        if (snapshot.hasError || snapshot.data == null) {
          return Text('Decision history unavailable. Nothing is shown.',
            key: const ValueKey('mind-decision-history-error'), style: type.bodySmall);
        }
        final history = snapshot.data!;
        final needle = _query.trim().toLowerCase();
        final shown = needle.isEmpty
            ? history.decisions
            : history.decisions.where((d) =>
                d.strategy.toLowerCase().contains(needle) ||
                d.tradingSymbol.toLowerCase().contains(needle) ||
                d.candidateFingerprint.toLowerCase().contains(needle)).toList();
        return Column(
          key: const ValueKey('mind-decision-history'),
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const MindTag('RECORDED CANDIDATES · RESEARCH ONLY · NO ORDER',
              color: MindPalette.blue),
            const SizedBox(height: 8),
            TextField(
              key: const ValueKey('mind-decision-search'),
              decoration: const InputDecoration(
                labelText: 'Search strategy, symbol or fingerprint', isDense: true),
              onChanged: (value) => setState(() => _query = value),
            ),
            const SizedBox(height: 8),
            if (shown.isEmpty)
              Text(history.decisions.isEmpty
                  ? 'No recorded candidates from succeeded scan runs.'
                  : 'No recorded candidates match this search.',
                style: type.bodySmall)
            else
              for (final d in shown)
                Padding(
                  key: ValueKey('mind-decision-${d.candidateFingerprint}'),
                  padding: const EdgeInsets.only(bottom: 8),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        '${d.strategy} v${d.strategyVersion ?? '—'} · ${d.tradingSymbol} · ${d.interval}',
                        style: type.labelLarge),
                      Text(
                        'Signal ${d.signalCandleTime.toIso8601String()} · '
                        'data cutoff ${d.dataCutoffAt.toIso8601String()}',
                        style: type.bodySmall),
                      Text(
                        'Reasons: ${d.reasons.isEmpty ? 'none recorded' : d.reasons.join(', ')}',
                        style: type.bodySmall),
                      _NewsImpact(
                        key: ValueKey('mind-news-impact-${d.candidateFingerprint}'),
                        news: d.news),
                      if (widget.verifiedNewsRepository != null && d.instrumentId != null)
                        TextButton.icon(
                          key: ValueKey('mind-verified-news-${d.candidateFingerprint}'),
                          icon: const Icon(Icons.article_outlined, size: 16),
                          label: const Text('Verified issuer news'),
                          onPressed: () async {
                            final repository = widget.verifiedNewsRepository!;
                            await Navigator.of(context).push<void>(MaterialPageRoute<void>(
                              builder: (_) => Scaffold(
                                appBar: AppBar(title: const Text('Verified issuer news')),
                                body: SafeArea(
                                  child: SingleChildScrollView(
                                    padding: const EdgeInsets.all(16),
                                    child: MindVerifiedNewsPanel(
                                      instrumentId: d.instrumentId!,
                                      asOf: d.dataCutoffAt,
                                      repository: repository,
                                    ),
                                  ),
                                ),
                              ),
                            ));
                          },
                        )
                      else if (widget.verifiedNewsRepository != null)
                        Text('Verified issuer news unavailable: original instrument ID not recorded.',
                          style: type.labelSmall),
                      Text(
                        'Fingerprint ${d.candidateFingerprint.length > 12 ? d.candidateFingerprint.substring(0, 12) : d.candidateFingerprint}…'
                        '${d.catalogueVersion == null ? '' : ' · catalogue ${d.catalogueVersion}'}',
                        style: type.labelSmall),
                    ],
                  ),
                ),
            const SizedBox(height: 4),
            for (final limit in history.limitations)
              Text('• $limit', style: type.labelSmall),
          ],
        );
      },
    );
  }
}

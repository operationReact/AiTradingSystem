import 'package:flutter/material.dart';

import 'mock_trade_intelligence_model.dart';

class MockTradeIntelligenceReport extends StatefulWidget {
  const MockTradeIntelligenceReport({super.key, required this.record});

  final MockTradeIntelligenceRecord record;

  @override
  State<MockTradeIntelligenceReport> createState() => _MockTradeIntelligenceReportState();
}

class _MockTradeIntelligenceReportState extends State<MockTradeIntelligenceReport> {
  String _tab = 'Decision';

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFF020817),
      body: SafeArea(
        child: Column(
          children: [
            _header(context),
            _tabBar(),
            Expanded(
              child: SingleChildScrollView(
                padding: const EdgeInsets.fromLTRB(16, 14, 16, 28),
                child: switch (_tab) {
                  'Decision' => _decision(),
                  'Evidence' => _evidence(),
                  'Confidence' => _confidence(),
                  'Outcome' => _outcome(),
                  _ => _audit(),
                },
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _header(BuildContext context) {
    final tone = widget.record.cancelled
        ? _C.amber
        : widget.record.isLoss
            ? _C.red
            : widget.record.closed
                ? _C.green
                : _C.cyan;
    return Padding(
      padding: const EdgeInsets.fromLTRB(10, 8, 14, 6),
      child: Row(
        children: [
          IconButton(onPressed: () => Navigator.of(context).pop(), icon: const Icon(Icons.arrow_back_rounded)),
          const SizedBox(width: 2),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text('Trade Intelligence Report', style: TextStyle(fontSize: 20, fontWeight: FontWeight.w900)),
                const SizedBox(height: 2),
                Text('${widget.record.symbol} · ${widget.record.tradeId}', maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(color: _C.muted, fontSize: 10)),
              ],
            ),
          ),
          _Pill(widget.record.status, tone),
        ],
      ),
    );
  }

  Widget _tabBar() {
    const tabs = ['Decision', 'Evidence', 'Confidence', 'Outcome', 'Audit'];
    return Container(
      height: 44,
      margin: const EdgeInsets.symmetric(horizontal: 12),
      padding: const EdgeInsets.all(3),
      decoration: BoxDecoration(
        color: const Color(0xFF071529),
        borderRadius: BorderRadius.circular(13),
        border: Border.all(color: const Color(0xFF183050)),
      ),
      child: Row(
        children: tabs
            .map(
              (tab) => Expanded(
                child: Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 1),
                  child: Material(
                    color: _tab == tab ? _C.cyan.withValues(alpha: .12) : Colors.transparent,
                    borderRadius: BorderRadius.circular(9),
                    child: InkWell(
                      onTap: () => setState(() => _tab = tab),
                      borderRadius: BorderRadius.circular(9),
                      child: Center(
                        child: Text(
                          tab,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            color: _tab == tab ? _C.cyan : _C.muted,
                            fontSize: 9,
                            fontWeight: _tab == tab ? FontWeight.w900 : FontWeight.w700,
                          ),
                        ),
                      ),
                    ),
                  ),
                ),
              ),
            )
            .toList(),
      ),
    );
  }

  Widget _decision() {
    final record = widget.record;
    final tone = record.cancelled
        ? _C.amber
        : record.isLoss
            ? _C.red
            : record.closed
                ? _C.green
                : _C.cyan;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _Glow(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  const Icon(Icons.psychology_alt_rounded, color: _C.cyan),
                  const SizedBox(width: 9),
                  Expanded(child: Text('${record.side} ${record.symbol}', style: const TextStyle(fontSize: 22, fontWeight: FontWeight.w900))),
                  _Pill('${record.initialConfidence}% PRE-TRADE', _C.cyan),
                ],
              ),
              const SizedBox(height: 8),
              Text(record.decision, style: TextStyle(color: tone, fontSize: 18, fontWeight: FontWeight.w900)),
              const SizedBox(height: 7),
              Text(record.primaryReason, style: const TextStyle(color: _C.muted, height: 1.45)),
            ],
          ),
        ),
        const SizedBox(height: 10),
        Row(
          children: [
            Expanded(child: _Metric(label: 'Entry', value: '₹${record.entryPrice.toStringAsFixed(2)}', tone: _C.cyan)),
            const SizedBox(width: 8),
            Expanded(child: _Metric(label: 'Stop', value: '₹${record.stopPrice.toStringAsFixed(2)}', tone: _C.red)),
            const SizedBox(width: 8),
            Expanded(child: _Metric(label: 'Target', value: '₹${record.targetPrice.toStringAsFixed(2)}', tone: _C.green)),
          ],
        ),
        const SizedBox(height: 10),
        const _Section('Why the system made this decision'),
        const SizedBox(height: 8),
        _Card(child: Text(record.decisionSummary, style: const TextStyle(color: _C.muted, height: 1.5))),
        const SizedBox(height: 10),
        _Card(
          child: Column(
            children: [
              _InfoRow(label: 'Trading mode', value: record.tradingMode),
              const Divider(height: 18),
              _InfoRow(label: 'Execution mode', value: record.executionMode),
              const Divider(height: 18),
              _InfoRow(label: 'Initiated by', value: record.initiatedBy),
            ],
          ),
        ),
        const SizedBox(height: 10),
        const _Section('Risk Engine justification'),
        const SizedBox(height: 8),
        _Card(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Row(children: [Icon(Icons.verified_user_outlined, color: _C.green, size: 18), SizedBox(width: 8), Expanded(child: Text('Mandatory risk boundary', maxLines: 1, overflow: TextOverflow.ellipsis, style: TextStyle(fontWeight: FontWeight.w900)))]),
              const SizedBox(height: 8),
              Text(record.riskSummary, style: const TextStyle(color: _C.muted, height: 1.45)),
            ],
          ),
        ),
        const SizedBox(height: 10),
        _Notice('This report must be created from the frozen pre-trade snapshot. Later price moves or news may explain an outcome, but they must never rewrite what the system originally knew.'),
      ],
    );
  }

  Widget _evidence() {
    final record = widget.record;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const _Section('Evidence used before / during the decision'),
        const SizedBox(height: 5),
        const Text('Every contributing analysis remains visible, including disagreement. A fused score must not hide weak or contradictory evidence.', style: TextStyle(color: _C.muted, fontSize: 12, height: 1.45)),
        const SizedBox(height: 12),
        ...record.evidence.map(
          (fact) => Padding(
            padding: const EdgeInsets.only(bottom: 9),
            child: _EvidenceCard(fact: fact),
          ),
        ),
        const SizedBox(height: 2),
        _Notice('Production evidence should include real source IDs, data timestamps, feature schema, model versions and freshness. The values shown here are review fixtures.'),
      ],
    );
  }

  Widget _confidence() {
    final record = widget.record;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _Glow(
          child: Row(
            children: [
              const Icon(Icons.insights_rounded, color: _C.cyan),
              const SizedBox(width: 10),
              Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [const Text('Confidence evolution', style: TextStyle(fontWeight: FontWeight.w900, fontSize: 18)), const SizedBox(height: 3), Text('${record.initialConfidence}% → ${record.currentConfidence}%', style: const TextStyle(color: _C.muted))])),
              Text('${record.currentConfidence}%', style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 24, color: _C.cyan)),
            ],
          ),
        ),
        const SizedBox(height: 12),
        ...record.confidenceTimeline.asMap().entries.map(
          (entry) => _TimelineRow(event: entry.value, last: entry.key == record.confidenceTimeline.length - 1),
        ),
        const SizedBox(height: 12),
        const _Section('What would invalidate the trade?'),
        const SizedBox(height: 8),
        _Card(
          child: Column(
            children: record.invalidationRules
                .map((rule) => Padding(
                      padding: const EdgeInsets.only(bottom: 10),
                      child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [const Icon(Icons.cancel_outlined, color: _C.amber, size: 17), const SizedBox(width: 8), Expanded(child: Text(rule, style: const TextStyle(color: _C.muted, fontSize: 11, height: 1.4)))]),
                    ))
                .toList(),
          ),
        ),
        const SizedBox(height: 10),
        _Notice('Confidence is a calibrated probability estimate, not a promise. A trade can lose even when the original decision was reasonable.'),
      ],
    );
  }

  Widget _outcome() {
    final record = widget.record;
    final tone = record.cancelled
        ? _C.amber
        : record.isLoss
            ? _C.red
            : record.isProfit
                ? _C.green
                : _C.cyan;
    final heading = record.cancelled
        ? 'Why the trade did not execute'
        : record.isLoss
            ? 'Why this trade failed'
            : record.isProfit
                ? 'Why this trade likely worked'
                : 'Current thesis health';
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _Glow(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(heading, style: TextStyle(color: tone, fontSize: 20, fontWeight: FontWeight.w900)),
              const SizedBox(height: 7),
              Text(record.outcomeSummary, style: const TextStyle(color: _C.muted, height: 1.5)),
            ],
          ),
        ),
        const SizedBox(height: 10),
        _Card(
          child: Column(
            children: [
              _InfoRow(label: 'Failure classification', value: record.failureClassification),
              const Divider(height: 18),
              _InfoRow(label: 'Exit / current state', value: record.exitReason),
              const Divider(height: 18),
              _InfoRow(label: 'Last price', value: '₹${record.lastPrice.toStringAsFixed(2)}'),
              if (record.exitPrice != null) ...[
                const Divider(height: 18),
                _InfoRow(label: 'Exit price', value: '₹${record.exitPrice!.toStringAsFixed(2)}'),
              ],
              if (record.realizedPnl != null) ...[
                const Divider(height: 18),
                _InfoRow(label: 'Realized P&L', value: '${record.realizedPnl! >= 0 ? '+' : '-'}₹${record.realizedPnl!.abs().toStringAsFixed(0)}'),
              ],
            ],
          ),
        ),
        const SizedBox(height: 10),
        if (!record.cancelled) ...[
          const _Section('Likely contributing factors'),
          const SizedBox(height: 8),
          _Card(
            child: Column(
              children: record.outcomeFactors
                  .map(
                    (factor) => Padding(
                      padding: const EdgeInsets.only(bottom: 12),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Row(children: [Expanded(child: Text(factor.label, style: const TextStyle(fontWeight: FontWeight.w800))), Text('${factor.weight}%', style: TextStyle(color: factor.helped ? _C.green : _C.red, fontWeight: FontWeight.w900))]),
                          const SizedBox(height: 5),
                          LinearProgressIndicator(value: factor.weight / 100, minHeight: 6, borderRadius: BorderRadius.circular(99), backgroundColor: const Color(0xFF17243A), color: factor.helped ? _C.green : _C.red),
                          const SizedBox(height: 4),
                          Text(factor.detail, style: const TextStyle(color: _C.muted, fontSize: 11, height: 1.35)),
                        ],
                      ),
                    ),
                  )
                  .toList(),
            ),
          ),
          const SizedBox(height: 10),
        ],
        _Notice(record.isLoss
            ? 'A loss should be classified into changed market evidence, model/strategy error, risk/exit behavior and execution quality. Never label the model “wrong” only because P&L was negative.'
            : 'Attribution is explanatory, not causal proof. A profitable trade does not prove every signal or model was correct.'),
      ],
    );
  }

  Widget _audit() {
    final record = widget.record;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const _Glow(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(children: [Icon(Icons.lock_clock_rounded, color: _C.cyan), SizedBox(width: 9), Expanded(child: Text('Immutable provenance', style: TextStyle(fontSize: 18, fontWeight: FontWeight.w900)))]),
              SizedBox(height: 7),
              Text('This is the audit chain that lets a user verify what data, model, policy and evidence produced the decision.', style: TextStyle(color: _C.muted, height: 1.45)),
            ],
          ),
        ),
        const SizedBox(height: 10),
        ...record.auditReferences.map(
          (item) => Padding(
            padding: const EdgeInsets.only(bottom: 8),
            child: _Card(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(children: [Expanded(child: Text(item.label, style: const TextStyle(fontWeight: FontWeight.w900))), const _Pill('RECORDED', _C.green)]),
                  const SizedBox(height: 5),
                  Text(item.value, style: const TextStyle(color: _C.cyan, fontWeight: FontWeight.w800, fontSize: 12)),
                  const SizedBox(height: 5),
                  Text(item.detail, style: const TextStyle(color: _C.muted, fontSize: 11, height: 1.4)),
                ],
              ),
            ),
          ),
        ),
        const SizedBox(height: 2),
        _Notice('Customer UI should expose understandable evidence and provenance, not raw prompts, tensors, chain-of-thought, secrets or internal implementation details.'),
      ],
    );
  }
}

class _EvidenceCard extends StatelessWidget {
  const _EvidenceCard({required this.fact});
  final MockEvidenceFact fact;

  @override
  Widget build(BuildContext context) {
    final tone = fact.supportsTrade ? _C.green : _C.amber;
    return _Card(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              _Pill(fact.category, _C.cyan),
              const SizedBox(width: 8),
              Expanded(child: Text(fact.title, style: const TextStyle(fontWeight: FontWeight.w900))),
              Text('${fact.score}', style: TextStyle(color: tone, fontWeight: FontWeight.w900, fontSize: 18)),
            ],
          ),
          const SizedBox(height: 7),
          Row(children: [Icon(fact.supportsTrade ? Icons.check_circle_outline_rounded : Icons.warning_amber_rounded, color: tone, size: 16), const SizedBox(width: 6), Text(fact.verdict, style: TextStyle(color: tone, fontWeight: FontWeight.w800, fontSize: 11))]),
          const SizedBox(height: 7),
          Text(fact.detail, style: const TextStyle(color: _C.muted, fontSize: 11, height: 1.45)),
          const SizedBox(height: 7),
          Text(
            fact.influenceWeightPercent == 0
                ? 'Decision role: mandatory gate (not part of fused score)'
                : 'Decision influence: ${fact.influenceWeightPercent}% of fused evidence',
            style: const TextStyle(color: _C.cyan, fontSize: 10, fontWeight: FontWeight.w800),
          ),
          const SizedBox(height: 8),
          Text(fact.provenance, style: const TextStyle(color: Color(0xFF6F86AA), fontSize: 9, fontFamily: 'monospace')),
        ],
      ),
    );
  }
}

class _TimelineRow extends StatelessWidget {
  const _TimelineRow({required this.event, required this.last});
  final MockConfidenceEvent event;
  final bool last;

  @override
  Widget build(BuildContext context) => Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: 26,
            child: Column(
              children: [
                Container(width: 11, height: 11, decoration: const BoxDecoration(color: _C.cyan, shape: BoxShape.circle)),
                if (!last) Container(width: 2, height: 62, color: const Color(0xFF22405E)),
              ],
            ),
          ),
          const SizedBox(width: 7),
          Expanded(
            child: Padding(
              padding: const EdgeInsets.only(bottom: 12),
              child: _Card(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(children: [Expanded(child: Text(event.stage, style: const TextStyle(fontWeight: FontWeight.w900))), Text('${event.confidence}%', style: const TextStyle(color: _C.cyan, fontWeight: FontWeight.w900))]),
                    const SizedBox(height: 5),
                    Text(event.reason, style: const TextStyle(color: _C.muted, fontSize: 11, height: 1.4)),
                  ],
                ),
              ),
            ),
          ),
        ],
      );
}

class _Card extends StatelessWidget {
  const _Card({required this.child});
  final Widget child;
  @override
  Widget build(BuildContext context) => Material(
        color: const Color(0xFF08162A),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16), side: const BorderSide(color: Color(0xFF183050))),
        clipBehavior: Clip.antiAlias,
        child: Padding(padding: const EdgeInsets.all(14), child: child),
      );
}

class _Glow extends StatelessWidget {
  const _Glow({required this.child});
  final Widget child;
  @override
  Widget build(BuildContext context) => Container(
        width: double.infinity,
        padding: const EdgeInsets.all(16),
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(18),
          gradient: const LinearGradient(begin: Alignment.topLeft, end: Alignment.bottomRight, colors: [Color(0xFF241349), Color(0xFF0A2446), Color(0xFF07162A)]),
          border: Border.all(color: const Color(0xFF553E87)),
        ),
        child: child,
      );
}

class _Metric extends StatelessWidget {
  const _Metric({required this.label, required this.value, required this.tone});
  final String label;
  final String value;
  final Color tone;
  @override
  Widget build(BuildContext context) => _Card(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [Text(label, style: const TextStyle(color: _C.muted, fontSize: 10)), const SizedBox(height: 5), Text(value, maxLines: 1, overflow: TextOverflow.ellipsis, style: TextStyle(color: tone, fontWeight: FontWeight.w900, fontSize: 12))]));
}

class _InfoRow extends StatelessWidget {
  const _InfoRow({required this.label, required this.value});
  final String label;
  final String value;
  @override
  Widget build(BuildContext context) => Row(crossAxisAlignment: CrossAxisAlignment.start, children: [Expanded(child: Text(label, style: const TextStyle(color: _C.muted, fontSize: 11))), const SizedBox(width: 10), Expanded(child: Text(value, textAlign: TextAlign.right, style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 11)))]);
}

class _Pill extends StatelessWidget {
  const _Pill(this.text, this.tone);
  final String text;
  final Color tone;
  @override
  Widget build(BuildContext context) => Container(padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 4), decoration: BoxDecoration(color: tone.withValues(alpha: .12), borderRadius: BorderRadius.circular(99), border: Border.all(color: tone.withValues(alpha: .25))), child: Text(text, style: TextStyle(color: tone, fontSize: 9, fontWeight: FontWeight.w900)));
}

class _Section extends StatelessWidget {
  const _Section(this.text);
  final String text;
  @override
  Widget build(BuildContext context) => Text(text, style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w900));
}

class _Notice extends StatelessWidget {
  const _Notice(this.text);
  final String text;
  @override
  Widget build(BuildContext context) => Container(
        width: double.infinity,
        padding: const EdgeInsets.all(13),
        decoration: BoxDecoration(color: const Color(0xFF0A1930), borderRadius: BorderRadius.circular(13), border: Border.all(color: const Color(0xFF1E3D62))),
        child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [const Icon(Icons.info_outline_rounded, color: _C.cyan, size: 18), const SizedBox(width: 9), Expanded(child: Text(text, style: const TextStyle(color: _C.muted, fontSize: 11, height: 1.45)))]),
      );
}

class _C {
  static const cyan = Color(0xFF1DE7FF);
  static const green = Color(0xFF39F59A);
  static const red = Color(0xFFFF6B7D);
  static const amber = Color(0xFFFFC857);
  static const muted = Color(0xFF8FA4C2);
}

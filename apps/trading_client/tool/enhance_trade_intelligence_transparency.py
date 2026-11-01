from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MODEL = ROOT / 'lib/src/presentation/review/mock_trade_intelligence_model.dart'
REPORT = ROOT / 'lib/src/presentation/review/mock_trade_intelligence_report.dart'

model = MODEL.read_text()

anchor = "  final bool supportsTrade;\n}\n"
addition = """  final bool supportsTrade;

  int get influenceWeightPercent => switch (title) {
        'Trend / structure' => 18,
        'Momentum' => 12,
        'Volume confirmation' => 10,
        'Quantitative baseline' => 18,
        'Learned model' => 16,
        'News / event intelligence' => 10,
        'Fundamental / session context' => 6,
        'Market regime' => 10,
        'Risk Engine' => 0,
        _ => 0,
      };
}
"""
if addition not in model:
    if anchor not in model:
        raise SystemExit('missing model anchor: evidence influence')
    model = model.replace(anchor, addition, 1)

getter_anchor = "  bool get isOpen => executed && !closed;\n"
getter_add = """  bool get isOpen => executed && !closed;
  String get tradingMode => 'PAPER';
  String get executionMode => 'MANUAL';
  String get initiatedBy => cancelled
      ? 'User cancelled before execution'
      : executed
          ? 'User-confirmed AI opportunity'
          : 'User reviewing AI opportunity';
"""
if getter_add not in model:
    if getter_anchor not in model:
        raise SystemExit('missing model anchor: initiation context')
    model = model.replace(getter_anchor, getter_add, 1)

fundamental = """      const MockEvidenceFact(
        category: 'FUNDAMENTAL',
        title: 'Fundamental / session context',
        verdict: 'Neutral',
        score: 55,
        detail: 'No short-horizon fundamental catalyst was strong enough to drive the trade by itself. The intraday setup therefore relied more heavily on price, volume and model evidence.',
        provenance: 'fundamental-context-review-v1 · no material contradiction',
        supportsTrade: false,
      ),
"""
regime_anchor = """      MockEvidenceFact(
        category: 'REGIME',
"""
if fundamental not in model:
    if regime_anchor not in model:
        raise SystemExit('missing model anchor: fundamental evidence')
    model = model.replace(regime_anchor, fundamental + regime_anchor, 1)

MODEL.write_text(model)

report = REPORT.read_text()

risk_section = """        const SizedBox(height: 10),
        const _Section('Risk Engine justification'),
"""
context_section = """        const SizedBox(height: 10),
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
"""
if context_section not in report:
    if risk_section not in report:
        raise SystemExit('missing report anchor: initiation card')
    report = report.replace(risk_section, context_section, 1)

provenance_line = """          const SizedBox(height: 8),
          Text(fact.provenance, style: const TextStyle(color: Color(0xFF6F86AA), fontSize: 9, fontFamily: 'monospace')),
"""
influence_line = """          const SizedBox(height: 7),
          Text(
            fact.influenceWeightPercent == 0
                ? 'Decision role: mandatory gate (not part of fused score)'
                : 'Decision influence: ${fact.influenceWeightPercent}% of fused evidence',
            style: const TextStyle(color: _C.cyan, fontSize: 10, fontWeight: FontWeight.w800),
          ),
          const SizedBox(height: 8),
          Text(fact.provenance, style: const TextStyle(color: Color(0xFF6F86AA), fontSize: 9, fontFamily: 'monospace')),
"""
if influence_line not in report:
    if provenance_line not in report:
        raise SystemExit('missing report anchor: evidence influence label')
    report = report.replace(provenance_line, influence_line, 1)

REPORT.write_text(report)

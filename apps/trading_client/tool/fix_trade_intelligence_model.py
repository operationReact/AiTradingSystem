from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MODEL = ROOT / 'lib/src/presentation/review/mock_trade_intelligence_model.dart'
text = MODEL.read_text()
text = text.replace(
    "confidence: (opportunity.confidence - 3).clamp(0, 100),",
    "confidence: (opportunity.confidence - 3).clamp(0, 100).toInt(),",
)
text = text.replace(
    "MockAuditReference(label: 'Trade decision', value: 'TRD-${symbol.toUpperCase()}-1042', detail: 'Immutable decision identifier linking evidence, risk, execution and outcome.'),",
    "MockAuditReference(label: 'Trade decision', value: 'TRD-${symbol.toUpperCase()}-${side.substring(0, 1)}-${entry.toStringAsFixed(0)}-1042', detail: 'Immutable decision identifier linking evidence, risk, execution and outcome.'),",
)
text = text.replace(
    "tradeId: 'TRD-${symbol.toUpperCase()}-1042',",
    "tradeId: 'TRD-${symbol.toUpperCase()}-${side.substring(0, 1)}-${entry.toStringAsFixed(0)}-1042',",
)
MODEL.write_text(text)

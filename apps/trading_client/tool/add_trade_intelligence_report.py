from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / 'lib/src/presentation/review/mock_review_app.dart'
JOURNEY = ROOT / 'lib/src/presentation/review/mock_trade_journey.dart'


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise SystemExit(f'missing patch anchor: {label}')
    return text.replace(old, new, 1)


app = APP.read_text()
app = replace_once(
    app,
    "import 'mock_trade_journey.dart';\n",
    "import 'mock_trade_journey.dart';\nimport 'mock_trade_intelligence_model.dart';\nimport 'mock_trade_intelligence_report.dart';\n",
    'app imports',
)

helper = """
void _openTradeIntelligence(
  BuildContext context,
  MockReviewStore store,
  String symbol, {
  String side = 'BUY',
  double? entryPrice,
  double? exitPrice,
  double? realizedPnl,
  bool executed = true,
  bool closed = false,
  bool cancelled = false,
  String exitReason = 'Position still open',
}) {
  final record = MockTradeIntelligenceRecord.fromStore(
    store: store,
    symbol: symbol,
    side: side,
    entryPrice: entryPrice,
    exitPrice: exitPrice,
    realizedPnl: realizedPnl,
    executed: executed,
    closed: closed,
    cancelled: cancelled,
    exitReason: exitReason,
  );
  Navigator.of(context).push(
    MaterialPageRoute<void>(
      builder: (_) => MockTradeIntelligenceReport(record: record),
    ),
  );
}

"""
if helper not in app:
    anchor = "enum MockReviewScreen {\n"
    if anchor not in app:
        raise SystemExit('missing patch anchor: report helper')
    app = app.replace(anchor, helper + anchor, 1)

app = replace_once(
    app,
    "...widget.store.positions.map((position) => Padding(padding: const EdgeInsets.only(bottom: 8), child: _PositionTile(position: position))),",
    "...widget.store.positions.map((position) => Padding(\n                  padding: const EdgeInsets.only(bottom: 8),\n                  child: _PositionTile(\n                    position: position,\n                    onTap: () => _openTradeIntelligence(\n                      context,\n                      widget.store,\n                      position.symbol,\n                      entryPrice: position.averagePrice,\n                      executed: true,\n                      closed: false,\n                      exitReason: position.pnl < 0 ? 'Open · thesis under pressure' : 'Open · thesis active',\n                    ),\n                  ),\n                )),",
    'portfolio report navigation',
)

app = replace_once(
    app,
    "..._orders.map((order) => Padding(padding: const EdgeInsets.only(bottom: 9), child: _OrderTile(order: order))),",
    "..._orders.map((order) {\n              final isClosedExit = order.status == 'Completed' && order.side == 'SELL';\n              final pnl = isClosedExit ? (order.symbol == 'TCS' ? 620.0 : -410.0) : null;\n              return Padding(\n                padding: const EdgeInsets.only(bottom: 9),\n                child: _OrderTile(\n                  order: order,\n                  onTap: () => _openTradeIntelligence(\n                    context,\n                    widget.store,\n                    order.symbol,\n                    side: order.side,\n                    entryPrice: order.averagePrice,\n                    exitPrice: isClosedExit ? order.averagePrice : null,\n                    realizedPnl: pnl,\n                    executed: order.status == 'Completed',\n                    closed: isClosedExit,\n                    cancelled: order.status == 'Cancelled',\n                    exitReason: order.status == 'Cancelled'\n                        ? 'Order cancelled before execution'\n                        : isClosedExit\n                            ? 'Mock strategy exit completed'\n                            : 'Order executed · position/audit record available',\n                  ),\n                ),\n              );\n            }),",
    'orders report navigation',
)

old_position = """class _PositionTile extends StatelessWidget {
  const _PositionTile({required this.position});
  final MockPosition position;
  @override
  Widget build(BuildContext context) => _ReviewCard(child: Row(children: [_SymbolAvatar(position.symbol), const SizedBox(width: 10), Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [Text(position.symbol, style: const TextStyle(fontWeight: FontWeight.w900)), Text('${position.quantity} qty · Avg ₹${position.averagePrice.toStringAsFixed(2)}', style: const TextStyle(color: _Ui.muted, fontSize: 11))])), Column(crossAxisAlignment: CrossAxisAlignment.end, children: [Text('₹${position.marketValue.toStringAsFixed(0)}', style: const TextStyle(fontWeight: FontWeight.w900)), Text('${position.pnl >= 0 ? '+' : ''}₹${position.pnl.toStringAsFixed(0)} (${position.pnlPercent.toStringAsFixed(1)}%)', style: TextStyle(color: position.pnl >= 0 ? _Ui.green : _Ui.red, fontSize: 11, fontWeight: FontWeight.w800))])]));
}
"""
new_position = """class _PositionTile extends StatelessWidget {
  const _PositionTile({required this.position, required this.onTap});
  final MockPosition position;
  final VoidCallback onTap;
  @override
  Widget build(BuildContext context) => _ReviewCard(
        onTap: onTap,
        child: Row(
          children: [
            _SymbolAvatar(position.symbol),
            const SizedBox(width: 10),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(position.symbol, style: const TextStyle(fontWeight: FontWeight.w900)),
                  Text('${position.quantity} qty · Avg ₹${position.averagePrice.toStringAsFixed(2)}', style: const TextStyle(color: _Ui.muted, fontSize: 11)),
                  const SizedBox(height: 4),
                  const Text('Tap for full trade analysis', style: TextStyle(color: _Ui.cyan, fontSize: 9, fontWeight: FontWeight.w800)),
                ],
              ),
            ),
            Column(crossAxisAlignment: CrossAxisAlignment.end, children: [Text('₹${position.marketValue.toStringAsFixed(0)}', style: const TextStyle(fontWeight: FontWeight.w900)), Text('${position.pnl >= 0 ? '+' : ''}₹${position.pnl.toStringAsFixed(0)} (${position.pnlPercent.toStringAsFixed(1)}%)', style: TextStyle(color: position.pnl >= 0 ? _Ui.green : _Ui.red, fontSize: 11, fontWeight: FontWeight.w800))]),
            const SizedBox(width: 4),
            const Icon(Icons.chevron_right_rounded, color: _Ui.muted, size: 18),
          ],
        ),
      );
}
"""
app = replace_once(app, old_position, new_position, 'position tile')

old_order = """class _OrderTile extends StatelessWidget {
  const _OrderTile({required this.order});
  final MockOrder order;
  @override
  Widget build(BuildContext context) {
    final statusColor = order.status == 'Completed' ? _Ui.green : order.status == 'Cancelled' ? _Ui.red : _Ui.cyan;
    return _ReviewCard(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [Row(children: [Text(order.symbol, style: const TextStyle(fontWeight: FontWeight.w900)), const Spacer(), _Pill(text: order.status, color: statusColor)]), const SizedBox(height: 5), Text('${order.side} · PAPER', style: TextStyle(color: order.side == 'BUY' ? _Ui.cyan : _Ui.magenta, fontSize: 11, fontWeight: FontWeight.w800)), const SizedBox(height: 5), Row(children: [Expanded(child: Text('Qty: ${order.quantity} · Avg: ₹${order.averagePrice.toStringAsFixed(2)}', style: const TextStyle(color: _Ui.muted, fontSize: 11))), Text(order.time, style: const TextStyle(color: _Ui.muted, fontSize: 10))])]));
  }
}
"""
new_order = """class _OrderTile extends StatelessWidget {
  const _OrderTile({required this.order, required this.onTap});
  final MockOrder order;
  final VoidCallback onTap;
  @override
  Widget build(BuildContext context) {
    final statusColor = order.status == 'Completed' ? _Ui.green : order.status == 'Cancelled' ? _Ui.red : _Ui.cyan;
    return _ReviewCard(
      onTap: onTap,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(children: [Text(order.symbol, style: const TextStyle(fontWeight: FontWeight.w900)), const Spacer(), _Pill(text: order.status, color: statusColor)]),
          const SizedBox(height: 5),
          Text('${order.side} · PAPER', style: TextStyle(color: order.side == 'BUY' ? _Ui.cyan : _Ui.magenta, fontSize: 11, fontWeight: FontWeight.w800)),
          const SizedBox(height: 5),
          Row(children: [Expanded(child: Text('Qty: ${order.quantity} · Avg: ₹${order.averagePrice.toStringAsFixed(2)}', style: const TextStyle(color: _Ui.muted, fontSize: 11))), Text(order.time, style: const TextStyle(color: _Ui.muted, fontSize: 10))]),
          const SizedBox(height: 6),
          const Row(children: [Icon(Icons.fact_check_outlined, color: _Ui.cyan, size: 14), SizedBox(width: 5), Text('Full trade analysis', style: TextStyle(color: _Ui.cyan, fontSize: 9, fontWeight: FontWeight.w800)), Spacer(), Icon(Icons.chevron_right_rounded, color: _Ui.muted, size: 16)]),
        ],
      ),
    );
  }
}
"""
app = replace_once(app, old_order, new_order, 'order tile')
APP.write_text(app)

journey = JOURNEY.read_text()
journey = replace_once(
    journey,
    "import 'mock_transparency_journey.dart';\n",
    "import 'mock_transparency_journey.dart';\nimport 'mock_trade_intelligence_model.dart';\nimport 'mock_trade_intelligence_report.dart';\n",
    'journey imports',
)

method_anchor = """  @override
  Widget build(BuildContext context) {
"""
method = """  void _openFullTradeAnalysis(BuildContext context) {
    final record = MockTradeIntelligenceRecord.fromStore(
      store: widget.store,
      symbol: _quote.symbol,
      side: _side,
      entryPrice: _entryPrice,
      exitPrice: _closed ? _exitPrice : null,
      realizedPnl: _closed ? _realizedPnl : null,
      executed: _executed,
      closed: _closed,
      exitReason: _closed ? _exitReason : (_executed ? 'Open · AI thesis monitoring active' : 'Pre-trade review'),
    );
    Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (_) => MockTradeIntelligenceReport(record: record),
      ),
    );
  }

"""
if method not in journey:
    if method_anchor not in journey:
        raise SystemExit('missing patch anchor: full analysis method')
    journey = journey.replace(method_anchor, method + method_anchor, 1)

button_anchor = """            const SizedBox(height: 12),
            _screen(),
"""
button_new = """            const SizedBox(height: 8),
            OutlinedButton.icon(
              onPressed: () => _openFullTradeAnalysis(context),
              icon: const Icon(Icons.fact_check_outlined, size: 18),
              label: const Text('Full Trade Intelligence Report'),
              style: OutlinedButton.styleFrom(
                foregroundColor: _C.green,
                side: const BorderSide(color: Color(0xFF245B4C)),
                minimumSize: const Size.fromHeight(42),
              ),
            ),
            const SizedBox(height: 12),
            _screen(),
"""
journey = replace_once(journey, button_anchor, button_new, 'full analysis button')
JOURNEY.write_text(journey)

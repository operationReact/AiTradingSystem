from pathlib import Path

path = Path('lib/src/presentation/review/mock_trade_journey.dart')
text = path.read_text()


def replace_once(old: str, new: str, label: str) -> None:
    global text
    if new in text:
        return
    if old not in text:
        raise SystemExit(f'missing patch anchor: {label}')
    text = text.replace(old, new, 1)


replace_once(
    "import 'mock_review_data.dart';\n",
    "import 'mock_review_data.dart';\nimport 'mock_transparency_journey.dart';\n",
    'transparency import',
)

anchor = """  @override\n  Widget build(BuildContext context) {\n    return ColoredBox(\n      color: const Color(0xFF020817),\n      child: _TradePage(\n        title: _title,\n        subtitle: _subtitle,\n        onBack: _back,\n        child: _screen(),\n      ),\n    );\n  }\n"""
replacement = """  MockTransparencyStart get _transparencyStart {\n    if (!_executed) return MockTransparencyStart.preTrade;\n    if (!_closed) return MockTransparencyStart.live;\n    return MockTransparencyStart.outcome;\n  }\n\n  String get _whyLabel {\n    if (!_executed) return 'Why this trade?';\n    if (!_closed) return 'Why are we still holding?';\n    return _realizedPnl >= 0 ? 'Why did this trade profit?' : 'Why did this trade lose?';\n  }\n\n  void _openTransparency(BuildContext context) {\n    Navigator.of(context).push(\n      MaterialPageRoute<void>(\n        builder: (_) => MockTransparencyJourney(\n          quote: _quote,\n          opportunity: _opportunity,\n          side: _side,\n          quantity: _quantity,\n          entryPrice: _entryPrice,\n          exitPrice: _exitPrice,\n          realizedPnl: _realizedPnl,\n          executed: _executed,\n          closed: _closed,\n          monitorStage: _monitorStage,\n          exitReason: _exitReason,\n          start: _transparencyStart,\n        ),\n      ),\n    );\n  }\n\n  @override\n  Widget build(BuildContext context) {\n    return ColoredBox(\n      color: const Color(0xFF020817),\n      child: _TradePage(\n        title: _title,\n        subtitle: _subtitle,\n        onBack: _back,\n        child: Column(\n          crossAxisAlignment: CrossAxisAlignment.start,\n          children: [\n            OutlinedButton.icon(\n              onPressed: () => _openTransparency(context),\n              icon: const Icon(Icons.visibility_outlined, size: 18),\n              label: Text(_whyLabel),\n              style: OutlinedButton.styleFrom(\n                foregroundColor: _C.cyan,\n                side: const BorderSide(color: Color(0xFF24557A)),\n                minimumSize: const Size.fromHeight(42),\n              ),\n            ),\n            const SizedBox(height: 12),\n            _screen(),\n          ],\n        ),\n      ),\n    );\n  }\n"""
replace_once(anchor, replacement, 'persistent why control')

path.write_text(text)

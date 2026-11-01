from pathlib import Path

path = Path('lib/src/presentation/review/mock_live_trading.dart')
text = path.read_text()

text = text.replace('_MockLiveHolding', 'MockLiveHolding')

needle = 'class MockLiveTradingStore extends ChangeNotifier {\n'
if needle in text and '  void refresh() => notifyListeners();\n' not in text:
    text = text.replace(
        needle,
        needle + '  void refresh() => notifyListeners();\n\n',
        1,
    )

text = text.replace('store.notifyListeners();', 'store.refresh();')
text = text.replace(
    'const _LiveOutlineButton({required this.label, required this.icon, required this.onTap, this.danger = false});',
    'const _LiveOutlineButton({super.key, required this.label, required this.icon, required this.onTap, this.danger = false});',
)
text = text.replace(
    "Text(subtitle, style: const TextStyle(color: _LiveUi.muted, height: 1.4))",
    "Text(subtitle, style: _LiveUi.muted.copyWith(height: 1.4))",
)
text = text.replace(
    "Text(text, style: const TextStyle(color: _LiveUi.muted, height: 1.35))",
    "Text(text, style: _LiveUi.muted.copyWith(height: 1.35))",
)

path.write_text(text)

from pathlib import Path

app_path = Path('lib/src/presentation/review/mock_review_app.dart')
data_path = Path('lib/src/presentation/review/mock_review_data.dart')

app = app_path.read_text()
start = app.index('class _GradientButton extends StatelessWidget {')
end = app.index('class _ScanRow extends StatelessWidget {')
correct_gradient_button = '''class _GradientButton extends StatelessWidget {
  const _GradientButton({required this.label, required this.onTap, this.icon});

  final String label;
  final VoidCallback onTap;
  final IconData? icon;

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(13),
        gradient: const LinearGradient(
          colors: [_Ui.cyan, Color(0xFF5DE1FF), _Ui.violet, _Ui.magenta],
        ),
      ),
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          onTap: onTap,
          borderRadius: BorderRadius.circular(13),
          child: SizedBox(
            height: 50,
            width: double.infinity,
            child: Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                if (icon != null) ...[
                  Icon(icon, color: const Color(0xFF03111F), size: 20),
                  const SizedBox(width: 8),
                ],
                Flexible(
                  child: Text(
                    label,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                      color: Color(0xFF03111F),
                      fontWeight: FontWeight.w900,
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

'''
app = app[:start] + correct_gradient_button + app[end:]
app = app.replace('store.notifyListeners();', 'store.refresh();')
app = app.replace(
    'DropdownButtonFormField<String>(value: value,',
    'DropdownButtonFormField<String>(initialValue: value,',
)
app = app.replace(
    '      if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);',
    '      if (i == 0) {\n        path.moveTo(x, y);\n      } else {\n        path.lineTo(x, y);\n      }',
)
app = app.replace('if (trailing != null) trailing!', '?trailing')

pulse_header = '''                Row(
                  children: [
                    const Icon(Icons.auto_awesome_rounded, color: _Ui.cyan, size: 18),
                    const SizedBox(width: 8),
                    const Text('AI Market Pulse', style: TextStyle(fontSize: 20, fontWeight: FontWeight.w900)),
                    const Spacer(),
                    Container(
                      width: 8,
                      height: 8,
                      decoration: const BoxDecoration(color: Color(0xFF60FFB1), shape: BoxShape.circle),
                    ),
                  ],
                ),'''
pulse_header_responsive = '''                Row(
                  children: [
                    const Icon(Icons.auto_awesome_rounded, color: _Ui.cyan, size: 18),
                    const SizedBox(width: 8),
                    const Expanded(
                      child: Text(
                        'AI Market Pulse',
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(fontSize: 18, fontWeight: FontWeight.w900),
                      ),
                    ),
                    const SizedBox(width: 8),
                    Container(
                      width: 8,
                      height: 8,
                      decoration: const BoxDecoration(color: Color(0xFF60FFB1), shape: BoxShape.circle),
                    ),
                  ],
                ),'''
app = app.replace(pulse_header, pulse_header_responsive)

portfolio_header = "                  Row(children: [const Text('PAPER Portfolio', style: TextStyle(fontSize: 21, fontWeight: FontWeight.w900)), const Spacer(), _Pill(text: 'PAPER MODE', color: _Ui.magenta)]),"
portfolio_header_responsive = """                  Row(
                    children: [
                      const Expanded(
                        child: Text(
                          'PAPER Portfolio',
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(fontSize: 19, fontWeight: FontWeight.w900),
                        ),
                      ),
                      const SizedBox(width: 8),
                      const _Pill(text: 'PAPER MODE', color: _Ui.magenta),
                    ],
                  ),"""
app = app.replace(portfolio_header, portfolio_header_responsive)

broker_header = "                Row(children: [const _AngelOneMark(), const SizedBox(width: 12), const Text('Angel One', style: TextStyle(fontSize: 21, fontWeight: FontWeight.w900)), const Spacer(), if (store.brokerConnected) const _Pill(text: 'CONNECTED', color: _Ui.green)]),"
broker_header_responsive = """                Row(
                  children: [
                    const _AngelOneMark(),
                    const SizedBox(width: 10),
                    const Expanded(
                      child: Text(
                        'Angel One',
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(fontSize: 19, fontWeight: FontWeight.w900),
                      ),
                    ),
                    if (store.brokerConnected) ...[
                      const SizedBox(width: 8),
                      const _Pill(text: 'CONNECTED', color: _Ui.green),
                    ],
                  ],
                ),"""
app = app.replace(broker_header, broker_header_responsive)

app_path.write_text(app)

data = data_path.read_text()
needle = 'class MockReviewStore extends ChangeNotifier {\n  MockReviewStore();\n'
replacement = '''class MockReviewStore extends ChangeNotifier {
  MockReviewStore();

  /// Notifies review-mode widgets after an intentionally local mock setting
  /// changes. Production repositories are not involved in this review store.
  void refresh() => notifyListeners();
'''
if 'void refresh() => notifyListeners();' not in data:
    data = data.replace(needle, replacement, 1)
data_path.write_text(data)

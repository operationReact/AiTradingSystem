from pathlib import Path

app_path = Path('lib/src/presentation/review/mock_review_app.dart')
report_path = Path('lib/src/presentation/review/mock_trade_intelligence_report.dart')

app = app_path.read_text()
report = report_path.read_text()

if "import 'mock_transparency_completion.dart';" not in app:
    anchor = "import 'mock_trade_intelligence_report.dart';\n"
    if anchor not in app:
        raise SystemExit('mock_review_app import anchor not found')
    app = app.replace(anchor, anchor + "import 'mock_transparency_completion.dart';\n", 1)

if '  transparencyCenter,\n' not in app:
    anchor = '  tradeJourney,\n'
    if anchor not in app:
        raise SystemExit('MockReviewScreen enum anchor not found')
    app = app.replace(anchor, anchor + '  transparencyCenter,\n', 1)

if 'MockReviewScreen.transparencyCenter => MockTransparencyCompletionHub' not in app:
    anchor = '        MockReviewScreen.portfolio => _PortfolioReviewPage(\n'
    if anchor not in app:
        raise SystemExit('screen switch anchor not found')
    block = """        MockReviewScreen.transparencyCenter => MockTransparencyCompletionHub(
            record: MockTradeIntelligenceRecord.fromStore(
              store: _store,
              symbol: 'RELIANCE',
              entryPrice: 2948,
              executed: true,
              closed: false,
              exitReason: 'Position still open',
            ),
            onBack: () => _open(MockReviewScreen.more),
          ),
"""
    app = app.replace(anchor, block + anchor, 1)

if "'Transparency 360'" not in app:
    anchor = "      (Icons.receipt_long_outlined, 'Orders', 'Review PAPER orders', MockReviewScreen.orders),\n"
    if anchor not in app:
        raise SystemExit('More menu anchor not found')
    line = "      (Icons.visibility_outlined, 'Transparency 360', 'Portfolio, model, event and audit transparency', MockReviewScreen.transparencyCenter),\n"
    app = app.replace(anchor, line + anchor, 1)

if "import 'mock_transparency_completion.dart';" not in report:
    anchor = "import 'mock_trade_intelligence_model.dart';\n"
    if anchor not in report:
        raise SystemExit('trade report import anchor not found')
    report = report.replace(anchor, anchor + "import 'mock_transparency_completion.dart';\n", 1)

if "Key('trade-intelligence-transparency-360')" not in report:
    anchor = "        _Notice('This report must be created from the frozen pre-trade snapshot. Later price moves or news may explain an outcome, but they must never rewrite what the system originally knew.'),\n"
    if anchor not in report:
        raise SystemExit('trade report CTA anchor not found')
    cta = """        SizedBox(
          width: double.infinity,
          child: FilledButton.icon(
            key: const Key('trade-intelligence-transparency-360'),
            onPressed: () => Navigator.of(context).push(
              MaterialPageRoute<void>(builder: (_) => MockTransparencyCompletionHub(record: record)),
            ),
            icon: const Icon(Icons.visibility_outlined),
            label: const Text('Open Transparency 360'),
            style: FilledButton.styleFrom(minimumSize: const Size.fromHeight(48)),
          ),
        ),
        const SizedBox(height: 10),
"""
    report = report.replace(anchor, cta + anchor, 1)

app_path.write_text(app)
report_path.write_text(report)

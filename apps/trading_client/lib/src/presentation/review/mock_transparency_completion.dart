import 'package:flutter/material.dart';

import 'mock_trade_intelligence_model.dart';

class MockTransparencyCompletionHub extends StatelessWidget {
  const MockTransparencyCompletionHub({
    super.key,
    required this.record,
    this.onBack,
  });

  final MockTradeIntelligenceRecord record;
  final VoidCallback? onBack;

  @override
  Widget build(BuildContext context) {
    final items = <_SuiteItem>[
      _SuiteItem(Icons.account_tree_outlined, 'Portfolio Risk', 'Understand combined exposure, correlation and capital-at-risk.', () => _push(context, _PortfolioRiskPage(record: record))),
      _SuiteItem(Icons.stacked_line_chart_rounded, 'Scenario Lab', 'See how this trade behaves under market, gap and volatility shocks.', () => _push(context, _ScenarioLabPage(record: record))),
      _SuiteItem(Icons.model_training_outlined, 'Model Health', 'Check calibration, regime performance and recent degradation.', () => _push(context, _ModelHealthPage(record: record))),
      _SuiteItem(Icons.history_rounded, 'Similar Trades', 'Compare this setup with historical analogs and failure patterns.', () => _push(context, _SimilarTradesPage(record: record))),
      _SuiteItem(Icons.calculate_outlined, 'Position Sizing', 'See exactly why this quantity and risk budget were selected.', () => _push(context, _PositionSizingPage(record: record))),
      _SuiteItem(Icons.event_note_outlined, 'Event Risk', 'Review upcoming catalysts that can invalidate the thesis.', () => _push(context, _EventRiskPage(record: record))),
      _SuiteItem(Icons.notifications_active_outlined, 'Evidence Alerts', 'Track what changed after entry and why confidence moved.', () => _push(context, _EvidenceAlertsPage(record: record))),
      _SuiteItem(Icons.menu_book_outlined, 'Trade Journal', 'Turn the full decision and outcome into a reusable learning record.', () => _push(context, _TradeJournalPage(record: record))),
      _SuiteItem(Icons.picture_as_pdf_outlined, 'Audit Export', 'Preview the customer-readable report that can be exported for review.', () => _push(context, _AuditExportPage(record: record))),
    ];

    return Scaffold(
      backgroundColor: _X.bg,
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.fromLTRB(16, 10, 16, 28),
          children: [
            _Header(title: 'Transparency 360', onBack: onBack ?? () => Navigator.of(context).pop()),
            const SizedBox(height: 14),
            _Hero(
              icon: Icons.visibility_outlined,
              title: '${record.symbol} · complete decision transparency',
              body: 'Everything needed to understand the trade before entry, while open and after exit — including portfolio context, model reliability, event risk and learning evidence.',
            ),
            const SizedBox(height: 12),
            _Card(
              child: Row(
                children: [
                  const Icon(Icons.lock_clock_outlined, color: _X.cyan, size: 20),
                  const SizedBox(width: 10),
                  Expanded(
                    child: Text(
                      'Decision ${record.tradeId} is anchored to the frozen pre-trade snapshot. Later evidence can explain the outcome, but cannot rewrite the original reasoning.',
                      style: const TextStyle(color: _X.muted, fontSize: 11, height: 1.45),
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 14),
            ...items.map(
              (item) => Padding(
                padding: const EdgeInsets.only(bottom: 9),
                child: _NavCard(item: item),
              ),
            ),
            const SizedBox(height: 4),
            const _Notice('These surfaces complete the UI contract. Production must replace every fixture with timestamped, versioned evidence from the real market-data, model, Gemini, Risk Engine and broker pipelines.'),
          ],
        ),
      ),
    );
  }

  void _push(BuildContext context, Widget page) {
    Navigator.of(context).push(MaterialPageRoute<void>(builder: (_) => page));
  }
}

class _PortfolioRiskPage extends StatelessWidget {
  const _PortfolioRiskPage({required this.record});
  final MockTradeIntelligenceRecord record;

  @override
  Widget build(BuildContext context) {
    return _DetailScaffold(
      title: 'Portfolio Risk',
      children: [
        _Hero(icon: Icons.account_tree_outlined, title: 'Good trade ≠ good portfolio', body: '${record.symbol} is individually inside limits, but the system also checks how it changes the account-level risk picture.'),
        const SizedBox(height: 12),
        const Row(children: [Expanded(child: _Metric('Risk at stop', '₹4,860', _X.amber)), SizedBox(width: 8), Expanded(child: _Metric('Portfolio risk', '1.62%', _X.cyan)), SizedBox(width: 8), Expanded(child: _Metric('Limit', '2.00%', _X.green))]),
        const SizedBox(height: 12),
        const _Section('Exposure map'),
        const SizedBox(height: 8),
        const _Card(child: Column(children: [
          _ProgressRow('Energy / RELIANCE', 0.31, '31%', _X.amber),
          SizedBox(height: 12),
          _ProgressRow('Financials', 0.27, '27%', _X.cyan),
          SizedBox(height: 12),
          _ProgressRow('Technology', 0.18, '18%', _X.purple),
          SizedBox(height: 12),
          _ProgressRow('Cash / unallocated', 0.24, '24%', _X.green),
        ])),
        const SizedBox(height: 12),
        const _Section('Correlation checks'),
        const SizedBox(height: 8),
        const _Card(child: Column(children: [
          _Line('RELIANCE ↔ NIFTY 50', '+0.68', 'Moderate'),
          Divider(height: 20),
          _Line('RELIANCE ↔ Energy basket', '+0.82', 'High'),
          Divider(height: 20),
          _Line('New trade incremental risk', '+₹720', 'Within budget'),
        ])),
        const SizedBox(height: 10),
        const _Notice('A trade may be blocked even when its standalone signal is strong if sector concentration, correlated exposure or account risk exceeds policy.'),
      ],
    );
  }
}

class _ScenarioLabPage extends StatefulWidget {
  const _ScenarioLabPage({required this.record});
  final MockTradeIntelligenceRecord record;

  @override
  State<_ScenarioLabPage> createState() => _ScenarioLabPageState();
}

class _ScenarioLabPageState extends State<_ScenarioLabPage> {
  String scenario = 'NIFTY -1%';

  @override
  Widget build(BuildContext context) {
    final result = switch (scenario) {
      'Gap -2%' => ('₹${(widget.record.entryPrice * .976).toStringAsFixed(0)}', '-₹1,420', 'STOP / GAP RISK'),
      'Volatility +30%' => ('₹${(widget.record.entryPrice * .993).toStringAsFixed(0)}', '-₹610', 'THESIS STRESSED'),
      _ => ('₹${(widget.record.entryPrice * .989).toStringAsFixed(0)}', '-₹760', 'NEAR STOP'),
    };
    return _DetailScaffold(
      title: 'Scenario Lab',
      children: [
        _Hero(icon: Icons.stacked_line_chart_rounded, title: 'What if the market moves against us?', body: 'Stress the decision before capital is exposed. These are scenario estimates, not forecasts.'),
        const SizedBox(height: 12),
        Wrap(spacing: 7, runSpacing: 7, children: ['NIFTY -1%', 'Gap -2%', 'Volatility +30%'].map((value) => ChoiceChip(label: Text(value), selected: scenario == value, onSelected: (_) => setState(() => scenario = value))).toList()),
        const SizedBox(height: 12),
        Row(children: [Expanded(child: _Metric('Stress price', result.$1, _X.cyan)), const SizedBox(width: 8), Expanded(child: _Metric('Est. P&L', result.$2, _X.red)), const SizedBox(width: 8), Expanded(child: _Metric('State', result.$3, _X.amber))]),
        const SizedBox(height: 12),
        const _Card(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text('Risk interpretation', style: TextStyle(fontWeight: FontWeight.w900)),
          SizedBox(height: 7),
          Text('The planned stop limits normal downside, but overnight gaps and fast markets can exceed the stop distance. Position size therefore includes a gap/volatility buffer rather than assuming perfect fills.', style: TextStyle(color: _X.muted, fontSize: 11, height: 1.45)),
        ])),
        const SizedBox(height: 10),
        const _Notice('Scenario analysis helps users understand risk ranges. It must never be presented as a guaranteed maximum loss.'),
      ],
    );
  }
}

class _ModelHealthPage extends StatelessWidget {
  const _ModelHealthPage({required this.record});
  final MockTradeIntelligenceRecord record;

  @override
  Widget build(BuildContext context) {
    return _DetailScaffold(
      title: 'Model Health',
      children: [
        _Hero(icon: Icons.model_training_outlined, title: 'Can we trust an ${record.initialConfidence}% confidence?', body: 'Confidence is useful only when calibration and recent regime performance support it.'),
        const SizedBox(height: 12),
        const Row(children: [Expanded(child: _Metric('Calibration', 'GOOD', _X.green)), SizedBox(width: 8), Expanded(child: _Metric('Recent hit rate', '61%', _X.cyan)), SizedBox(width: 8), Expanded(child: _Metric('Drift', 'LOW', _X.green))]),
        const SizedBox(height: 12),
        const _Section('Calibration buckets'),
        const SizedBox(height: 8),
        const _Card(child: Column(children: [
          _ProgressRow('60–69% predicted → 64% realized', .64, '64%', _X.cyan),
          SizedBox(height: 12),
          _ProgressRow('70–79% predicted → 73% realized', .73, '73%', _X.green),
          SizedBox(height: 12),
          _ProgressRow('80–89% predicted → 79% realized', .79, '79%', _X.amber),
        ])),
        const SizedBox(height: 12),
        const _Section('Regime performance'),
        const SizedBox(height: 8),
        const _Card(child: Column(children: [
          _Line('Trending sessions', '+1.42R avg', 'Strong'),
          Divider(height: 20),
          _Line('Range-bound sessions', '+0.18R avg', 'Weak'),
          Divider(height: 20),
          _Line('High-volatility sessions', '-0.11R avg', 'Caution'),
        ])),
        const SizedBox(height: 10),
        const _Notice('If calibration deteriorates or drift rises, the system should reduce confidence, shadow the model, or block automated promotion rather than quietly continuing.'),
      ],
    );
  }
}

class _SimilarTradesPage extends StatelessWidget {
  const _SimilarTradesPage({required this.record});
  final MockTradeIntelligenceRecord record;

  @override
  Widget build(BuildContext context) {
    return _DetailScaffold(
      title: 'Similar Trades',
      children: [
        _Hero(icon: Icons.history_rounded, title: 'How did setups like this behave?', body: 'Historical analogs add context without pretending the future must repeat the past.'),
        const SizedBox(height: 12),
        const Row(children: [Expanded(child: _Metric('Similar setups', '37', _X.cyan)), SizedBox(width: 8), Expanded(child: _Metric('Win rate', '59%', _X.green)), SizedBox(width: 8), Expanded(child: _Metric('Median R', '+0.72R', _X.green))]),
        const SizedBox(height: 12),
        const _Card(child: Column(children: [
          _TradeAnalog('2026-10-22', 'Breakout + volume + supportive regime', '+1.8R', _X.green),
          Divider(height: 20),
          _TradeAnalog('2026-10-09', 'Late entry; regime reversed', '-1.0R', _X.red),
          Divider(height: 20),
          _TradeAnalog('2026-09-26', 'Momentum held; target reached', '+2.1R', _X.green),
          Divider(height: 20),
          _TradeAnalog('2026-09-11', 'News contradiction after entry', '-0.7R', _X.red),
        ])),
        const SizedBox(height: 10),
        const _Notice('Historical similarity is supporting evidence, not a promise. Production matching must be point-in-time and must avoid selecting only favorable examples.'),
      ],
    );
  }
}

class _PositionSizingPage extends StatefulWidget {
  const _PositionSizingPage({required this.record});
  final MockTradeIntelligenceRecord record;

  @override
  State<_PositionSizingPage> createState() => _PositionSizingPageState();
}

class _PositionSizingPageState extends State<_PositionSizingPage> {
  double riskBudget = 720;

  @override
  Widget build(BuildContext context) {
    final stopDistance = (widget.record.entryPrice - widget.record.stopPrice).abs().clamp(1, double.infinity);
    final quantity = (riskBudget / stopDistance).floor().clamp(1, 999);
    return _DetailScaffold(
      title: 'Position Sizing',
      children: [
        _Hero(icon: Icons.calculate_outlined, title: 'Why $quantity shares?', body: 'Quantity comes from the permitted risk budget and stop distance — not from AI confidence alone.'),
        const SizedBox(height: 12),
        _Card(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text('Risk budget · ₹${riskBudget.toStringAsFixed(0)}', style: const TextStyle(fontWeight: FontWeight.w900)),
          Slider(value: riskBudget, min: 360, max: 1080, divisions: 6, label: '₹${riskBudget.toStringAsFixed(0)}', onChanged: (value) => setState(() => riskBudget = value)),
          const Text('Review control only — production sizing is server-authoritative.', style: TextStyle(color: _X.muted, fontSize: 10)),
        ])),
        const SizedBox(height: 12),
        _Card(child: Column(children: [
          _Line('Entry', '₹${widget.record.entryPrice.toStringAsFixed(2)}', 'Frozen'),
          const Divider(height: 20),
          _Line('Stop', '₹${widget.record.stopPrice.toStringAsFixed(2)}', 'Mandatory'),
          const Divider(height: 20),
          _Line('Risk / share', '₹${stopDistance.toStringAsFixed(2)}', 'Entry − stop'),
          const Divider(height: 20),
          _Line('Calculated quantity', '$quantity shares', '₹${riskBudget.toStringAsFixed(0)} ÷ ₹${stopDistance.toStringAsFixed(2)}'),
          const Divider(height: 20),
          const _Line('Portfolio/exposure cap', 'PASS', 'No further reduction'),
        ])),
        const SizedBox(height: 10),
        const _Notice('If exposure, liquidity, daily-loss or concentration limits are tighter than the risk-budget quantity, the Risk Engine must reduce or reject the size.'),
      ],
    );
  }
}

class _EventRiskPage extends StatelessWidget {
  const _EventRiskPage({required this.record});
  final MockTradeIntelligenceRecord record;

  @override
  Widget build(BuildContext context) {
    return _DetailScaffold(
      title: 'Event Risk',
      children: [
        _Hero(icon: Icons.event_note_outlined, title: 'What can invalidate ${record.symbol}?', body: 'Technical and model evidence can be overridden by new material information. Event risk is therefore monitored separately.'),
        const SizedBox(height: 12),
        const _Card(child: Column(children: [
          _EventRow('12:30', 'Sector policy headline window', 'LOW', _X.green),
          Divider(height: 20),
          _EventRow('14:00', 'Management / corporate update watch', 'MEDIUM', _X.amber),
          Divider(height: 20),
          _EventRow('15:30', 'Session close / liquidity transition', 'MEDIUM', _X.amber),
          Divider(height: 20),
          _EventRow('Upcoming', 'Earnings / material exchange filing', 'HIGH IF ANNOUNCED', _X.red),
        ])),
        const SizedBox(height: 12),
        const _Section('Current evidence status'),
        const SizedBox(height: 8),
        const _Card(child: Column(children: [
          _Line('Gemini materiality', '0.28', 'No adverse catalyst'),
          Divider(height: 20),
          _Line('Contradiction flag', 'FALSE', 'No source conflict'),
          Divider(height: 20),
          _Line('Freshness', '2m 14s', 'Inside policy'),
        ])),
        const SizedBox(height: 10),
        const _Notice('Production event intelligence must show source IDs and timestamps. If evidence is stale, ambiguous or contradictory, the UI should say so rather than manufacture certainty.'),
      ],
    );
  }
}

class _EvidenceAlertsPage extends StatefulWidget {
  const _EvidenceAlertsPage({required this.record});
  final MockTradeIntelligenceRecord record;

  @override
  State<_EvidenceAlertsPage> createState() => _EvidenceAlertsPageState();
}

class _EvidenceAlertsPageState extends State<_EvidenceAlertsPage> {
  bool pushAlerts = true;
  bool materialOnly = true;

  @override
  Widget build(BuildContext context) {
    return _DetailScaffold(
      title: 'Evidence Alerts',
      children: [
        _Hero(icon: Icons.notifications_active_outlined, title: 'Tell me what changed — and why', body: 'Alerts explain the evidence change, confidence impact and action taken instead of only saying “price moved”.'),
        const SizedBox(height: 12),
        _Card(child: Column(children: [
          SwitchListTile(contentPadding: EdgeInsets.zero, title: const Text('Explainable push alerts'), subtitle: const Text('Include the reason and confidence impact.', style: TextStyle(color: _X.muted, fontSize: 11)), value: pushAlerts, onChanged: (value) => setState(() => pushAlerts = value)),
          const Divider(height: 1),
          SwitchListTile(contentPadding: EdgeInsets.zero, title: const Text('Material changes only'), subtitle: const Text('Suppress low-value noise.', style: TextStyle(color: _X.muted, fontSize: 11)), value: materialOnly, onChanged: (value) => setState(() => materialOnly = value)),
        ])),
        const SizedBox(height: 12),
        const _AlertCard(time: '10:37:18', title: 'Confidence 81% → 72%', reason: 'Volume confirmation weakened from 1.6× to 1.2×. Thesis remains valid; no action taken.', tone: _X.amber),
        const SizedBox(height: 8),
        const _AlertCard(time: '10:44:05', title: 'Regime turned adverse', reason: 'Broad-market breadth deteriorated. Stop unchanged; AI monitor moved thesis health to WATCH.', tone: _X.red),
        const SizedBox(height: 8),
        const _AlertCard(time: '10:51:32', title: 'Risk action: stop tightened', reason: 'Volatility expanded while price remained profitable. Risk policy reduced open downside.', tone: _X.cyan),
        const SizedBox(height: 10),
        const _Notice('Every automated action alert should deep-link back to the exact evidence and Risk Engine decision that caused it.'),
      ],
    );
  }
}

class _TradeJournalPage extends StatelessWidget {
  const _TradeJournalPage({required this.record});
  final MockTradeIntelligenceRecord record;

  @override
  Widget build(BuildContext context) {
    return _DetailScaffold(
      title: 'Trade Journal',
      children: [
        _Hero(icon: Icons.menu_book_outlined, title: '${record.symbol} · decision journal', body: 'The journal preserves process quality separately from P&L, so a profitable bad process and a losing good process are not confused.'),
        const SizedBox(height: 12),
        const _Card(child: Column(children: [
          _JournalRow('Setup followed?', 'YES', _X.green),
          Divider(height: 20),
          _JournalRow('Risk policy followed?', 'YES', _X.green),
          Divider(height: 20),
          _JournalRow('Entry inside planned zone?', 'YES', _X.green),
          Divider(height: 20),
          _JournalRow('Exit matched rule?', 'YES / REVIEW', _X.amber),
          Divider(height: 20),
          _JournalRow('Hindsight rewrite detected?', 'NO', _X.green),
        ])),
        const SizedBox(height: 12),
        const _Section('Learning notes'),
        const SizedBox(height: 8),
        const _Card(child: Text('Keep the original breakout thesis. Investigate whether late-session regime deterioration should reduce position size earlier. This is a research hypothesis for replay/backtest — not an automatic rule change.', style: TextStyle(color: _X.muted, fontSize: 11, height: 1.5))),
        const SizedBox(height: 10),
        const _Notice('A journal entry can feed the evaluation dataset, but one trade must never trigger blind retraining or model promotion.'),
      ],
    );
  }
}

class _AuditExportPage extends StatefulWidget {
  const _AuditExportPage({required this.record});
  final MockTradeIntelligenceRecord record;

  @override
  State<_AuditExportPage> createState() => _AuditExportPageState();
}

class _AuditExportPageState extends State<_AuditExportPage> {
  bool includeTechnical = true;
  bool includeOutcome = true;

  @override
  Widget build(BuildContext context) {
    return _DetailScaffold(
      title: 'Audit Export',
      children: [
        _Hero(icon: Icons.picture_as_pdf_outlined, title: 'Customer-readable trade report', body: 'Export the evidence, risk decision and outcome without leaking credentials, raw prompts, tensors or hidden reasoning.'),
        const SizedBox(height: 12),
        _Card(child: Column(children: [
          _Line('Trade', '${widget.record.side} ${widget.record.symbol}', widget.record.tradeId),
          const Divider(height: 20),
          _Line('Decision', widget.record.decision, '${widget.record.initialConfidence}% pre-trade'),
          const Divider(height: 20),
          _Line('Risk decision', 'APPROVED', 'risk-policy-v5'),
          const Divider(height: 20),
          _Line('Data cutoff', '10:31:06 IST', 'Frozen'),
        ])),
        const SizedBox(height: 12),
        _Card(child: Column(children: [
          SwitchListTile(contentPadding: EdgeInsets.zero, title: const Text('Include technical provenance'), value: includeTechnical, onChanged: (value) => setState(() => includeTechnical = value)),
          const Divider(height: 1),
          SwitchListTile(contentPadding: EdgeInsets.zero, title: const Text('Include post-trade outcome'), value: includeOutcome, onChanged: (value) => setState(() => includeOutcome = value)),
        ])),
        const SizedBox(height: 12),
        FilledButton.icon(onPressed: () => ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Review export generated from mock evidence.'))), icon: const Icon(Icons.download_rounded), label: const Text('Generate review report'), style: FilledButton.styleFrom(minimumSize: const Size.fromHeight(48))),
        const SizedBox(height: 10),
        const _Notice('Production export must be generated from the same immutable backend record displayed in-app. Never regenerate a plausible explanation after the outcome is known.'),
      ],
    );
  }
}

class _DetailScaffold extends StatelessWidget {
  const _DetailScaffold({required this.title, required this.children});
  final String title;
  final List<Widget> children;

  @override
  Widget build(BuildContext context) => Scaffold(
        backgroundColor: _X.bg,
        body: SafeArea(
          child: ListView(
            padding: const EdgeInsets.fromLTRB(16, 10, 16, 28),
            children: [
              _Header(title: title, onBack: () => Navigator.of(context).pop()),
              const SizedBox(height: 14),
              ...children,
            ],
          ),
        ),
      );
}

class _SuiteItem {
  const _SuiteItem(this.icon, this.title, this.subtitle, this.onTap);
  final IconData icon;
  final String title;
  final String subtitle;
  final VoidCallback onTap;
}

class _NavCard extends StatelessWidget {
  const _NavCard({required this.item});
  final _SuiteItem item;

  @override
  Widget build(BuildContext context) => _Card(
        onTap: item.onTap,
        child: Row(
          children: [
            Container(width: 38, height: 38, decoration: BoxDecoration(color: _X.cyan.withValues(alpha: .10), borderRadius: BorderRadius.circular(12)), child: Icon(item.icon, color: _X.cyan, size: 20)),
            const SizedBox(width: 11),
            Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [Text(item.title, style: const TextStyle(fontWeight: FontWeight.w900)), const SizedBox(height: 3), Text(item.subtitle, style: const TextStyle(color: _X.muted, fontSize: 10, height: 1.35))])),
            const SizedBox(width: 8),
            const Icon(Icons.chevron_right_rounded, color: _X.muted),
          ],
        ),
      );
}

class _Header extends StatelessWidget {
  const _Header({required this.title, required this.onBack});
  final String title;
  final VoidCallback onBack;
  @override
  Widget build(BuildContext context) => Row(children: [IconButton(onPressed: onBack, icon: const Icon(Icons.arrow_back_rounded)), const SizedBox(width: 2), Expanded(child: Text(title, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w900)))]);
}

class _Hero extends StatelessWidget {
  const _Hero({required this.icon, required this.title, required this.body});
  final IconData icon;
  final String title;
  final String body;
  @override
  Widget build(BuildContext context) => Container(
        width: double.infinity,
        padding: const EdgeInsets.all(16),
        decoration: BoxDecoration(borderRadius: BorderRadius.circular(18), gradient: const LinearGradient(colors: [Color(0xFF25134A), Color(0xFF0A2446), Color(0xFF07162A)]), border: Border.all(color: const Color(0xFF553E87))),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [Row(children: [Icon(icon, color: _X.cyan), const SizedBox(width: 9), Expanded(child: Text(title, style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w900)))]), const SizedBox(height: 8), Text(body, style: const TextStyle(color: _X.muted, fontSize: 11, height: 1.45))]),
      );
}

class _Card extends StatelessWidget {
  const _Card({required this.child, this.onTap});
  final Widget child;
  final VoidCallback? onTap;
  @override
  Widget build(BuildContext context) => Material(
        color: const Color(0xFF08162A),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16), side: const BorderSide(color: Color(0xFF183050))),
        clipBehavior: Clip.antiAlias,
        child: InkWell(onTap: onTap, child: Padding(padding: const EdgeInsets.all(14), child: child)),
      );
}

class _Metric extends StatelessWidget {
  const _Metric(this.label, this.value, this.tone);
  final String label;
  final String value;
  final Color tone;
  @override
  Widget build(BuildContext context) => _Card(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [Text(label, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(color: _X.muted, fontSize: 9)), const SizedBox(height: 5), Text(value, maxLines: 1, overflow: TextOverflow.ellipsis, style: TextStyle(color: tone, fontWeight: FontWeight.w900, fontSize: 12))]));
}

class _Section extends StatelessWidget {
  const _Section(this.text);
  final String text;
  @override
  Widget build(BuildContext context) => Text(text, style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w900));
}

class _Line extends StatelessWidget {
  const _Line(this.label, this.value, this.note);
  final String label;
  final String value;
  final String note;
  @override
  Widget build(BuildContext context) => Row(crossAxisAlignment: CrossAxisAlignment.start, children: [Expanded(flex: 4, child: Text(label, style: const TextStyle(color: _X.muted, fontSize: 10))), const SizedBox(width: 8), Expanded(flex: 3, child: Column(crossAxisAlignment: CrossAxisAlignment.end, children: [Text(value, textAlign: TextAlign.right, style: const TextStyle(fontSize: 11, fontWeight: FontWeight.w900)), const SizedBox(height: 2), Text(note, textAlign: TextAlign.right, style: const TextStyle(color: _X.muted, fontSize: 8))]))]);
}

class _ProgressRow extends StatelessWidget {
  const _ProgressRow(this.label, this.value, this.trailing, this.tone);
  final String label;
  final double value;
  final String trailing;
  final Color tone;
  @override
  Widget build(BuildContext context) => Column(crossAxisAlignment: CrossAxisAlignment.start, children: [Row(children: [Expanded(child: Text(label, style: const TextStyle(fontSize: 10, fontWeight: FontWeight.w700))), Text(trailing, style: TextStyle(color: tone, fontSize: 10, fontWeight: FontWeight.w900))]), const SizedBox(height: 5), LinearProgressIndicator(value: value, minHeight: 6, borderRadius: BorderRadius.circular(99), backgroundColor: const Color(0xFF17243A), color: tone)]);
}

class _TradeAnalog extends StatelessWidget {
  const _TradeAnalog(this.date, this.setup, this.result, this.tone);
  final String date;
  final String setup;
  final String result;
  final Color tone;
  @override
  Widget build(BuildContext context) => Row(crossAxisAlignment: CrossAxisAlignment.start, children: [SizedBox(width: 76, child: Text(date, style: const TextStyle(color: _X.muted, fontSize: 9))), Expanded(child: Text(setup, style: const TextStyle(fontSize: 10, height: 1.35))), const SizedBox(width: 8), Text(result, style: TextStyle(color: tone, fontSize: 11, fontWeight: FontWeight.w900))]);
}

class _EventRow extends StatelessWidget {
  const _EventRow(this.time, this.event, this.risk, this.tone);
  final String time;
  final String event;
  final String risk;
  final Color tone;
  @override
  Widget build(BuildContext context) => Row(crossAxisAlignment: CrossAxisAlignment.start, children: [SizedBox(width: 58, child: Text(time, style: const TextStyle(color: _X.cyan, fontSize: 9, fontWeight: FontWeight.w900))), Expanded(child: Text(event, style: const TextStyle(fontSize: 10, height: 1.35))), const SizedBox(width: 8), Text(risk, style: TextStyle(color: tone, fontSize: 8, fontWeight: FontWeight.w900))]);
}

class _AlertCard extends StatelessWidget {
  const _AlertCard({required this.time, required this.title, required this.reason, required this.tone});
  final String time;
  final String title;
  final String reason;
  final Color tone;
  @override
  Widget build(BuildContext context) => _Card(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [Row(children: [Text(time, style: const TextStyle(color: _X.muted, fontSize: 9)), const Spacer(), Container(width: 7, height: 7, decoration: BoxDecoration(color: tone, shape: BoxShape.circle))]), const SizedBox(height: 5), Text(title, style: TextStyle(color: tone, fontWeight: FontWeight.w900)), const SizedBox(height: 4), Text(reason, style: const TextStyle(color: _X.muted, fontSize: 10, height: 1.4))]));
}

class _JournalRow extends StatelessWidget {
  const _JournalRow(this.label, this.value, this.tone);
  final String label;
  final String value;
  final Color tone;
  @override
  Widget build(BuildContext context) => Row(children: [Expanded(child: Text(label, style: const TextStyle(color: _X.muted, fontSize: 10))), Text(value, style: TextStyle(color: tone, fontSize: 10, fontWeight: FontWeight.w900))]);
}

class _Notice extends StatelessWidget {
  const _Notice(this.text);
  final String text;
  @override
  Widget build(BuildContext context) => Container(width: double.infinity, padding: const EdgeInsets.all(13), decoration: BoxDecoration(color: const Color(0xFF0A1930), borderRadius: BorderRadius.circular(13), border: Border.all(color: const Color(0xFF1E3D62))), child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [const Icon(Icons.info_outline_rounded, color: _X.cyan, size: 17), const SizedBox(width: 8), Expanded(child: Text(text, style: const TextStyle(color: _X.muted, fontSize: 10, height: 1.45)))]));
}

class _X {
  static const bg = Color(0xFF020817);
  static const cyan = Color(0xFF1DE7FF);
  static const green = Color(0xFF39F59A);
  static const red = Color(0xFFFF6B7D);
  static const amber = Color(0xFFFFC857);
  static const purple = Color(0xFF9D6BFF);
  static const muted = Color(0xFF8FA4C2);
}

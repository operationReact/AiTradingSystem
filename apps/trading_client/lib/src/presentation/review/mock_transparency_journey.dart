import 'package:flutter/material.dart';

import 'mock_review_data.dart';

enum MockTransparencyStart { preTrade, live, outcome }

enum _AuditStep {
  preTradeSnapshot,
  decisionThresholds,
  modelAgreement,
  counterfactual,
  tradeReplay,
  liveThesis,
  expectedVsActual,
  outcomeAttribution,
  executionQuality,
  learningRecord,
}

class MockTransparencyJourney extends StatefulWidget {
  const MockTransparencyJourney({
    super.key,
    required this.quote,
    required this.opportunity,
    required this.side,
    required this.quantity,
    required this.entryPrice,
    required this.exitPrice,
    required this.realizedPnl,
    required this.executed,
    required this.closed,
    required this.monitorStage,
    required this.exitReason,
    this.start = MockTransparencyStart.preTrade,
  });

  final MockQuote quote;
  final MockOpportunity opportunity;
  final String side;
  final int quantity;
  final double entryPrice;
  final double exitPrice;
  final double realizedPnl;
  final bool executed;
  final bool closed;
  final int monitorStage;
  final String exitReason;
  final MockTransparencyStart start;

  @override
  State<MockTransparencyJourney> createState() => _MockTransparencyJourneyState();
}

class _MockTransparencyJourneyState extends State<MockTransparencyJourney> {
  late _AuditStep _step;
  late bool _showLossScenario;

  static const _steps = _AuditStep.values;

  @override
  void initState() {
    super.initState();
    _showLossScenario = widget.realizedPnl < 0;
    _step = switch (widget.start) {
      MockTransparencyStart.preTrade => _AuditStep.preTradeSnapshot,
      MockTransparencyStart.live => _AuditStep.liveThesis,
      MockTransparencyStart.outcome => _AuditStep.expectedVsActual,
    };
  }

  int get _index => _steps.indexOf(_step);
  bool get _isProfit => !_showLossScenario;
  double get _stopDistance => (widget.entryPrice - widget.opportunity.stop).abs();
  double get _maxRisk => _stopDistance * widget.quantity;
  double get _actualMovePct => ((widget.exitPrice - widget.entryPrice) / widget.entryPrice) * 100;

  void _go(_AuditStep step) => setState(() => _step = step);

  void _back() {
    if (_index == 0) {
      Navigator.of(context).pop();
      return;
    }
    _go(_steps[_index - 1]);
  }

  void _next() {
    if (_index >= _steps.length - 1) {
      Navigator.of(context).pop();
      return;
    }
    _go(_steps[_index + 1]);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFF020817),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.fromLTRB(16, 12, 16, 30),
          child: Center(
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 620),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  _header(),
                  const SizedBox(height: 12),
                  _phaseNavigation(),
                  const SizedBox(height: 16),
                  _screen(),
                  const SizedBox(height: 18),
                  _footerNavigation(),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _header() {
    return Row(
      children: [
        IconButton(
          onPressed: _back,
          padding: EdgeInsets.zero,
          alignment: Alignment.centerLeft,
          icon: const Icon(Icons.arrow_back_rounded),
        ),
        const SizedBox(width: 4),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(_title, style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w900)),
              Text(_subtitle, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(color: _AuditC.muted, fontSize: 11)),
            ],
          ),
        ),
        const _AuditPill('DECISION AUDIT', _AuditC.cyan),
      ],
    );
  }

  Widget _phaseNavigation() {
    return Wrap(
      spacing: 7,
      runSpacing: 7,
      children: [
        _AuditPhaseChip(
          label: 'PRE-TRADE',
          selected: _index <= 4,
          onTap: () => _go(_AuditStep.preTradeSnapshot),
        ),
        _AuditPhaseChip(
          label: 'LIVE THESIS',
          selected: _step == _AuditStep.liveThesis,
          onTap: () => _go(_AuditStep.liveThesis),
        ),
        _AuditPhaseChip(
          label: 'OUTCOME',
          selected: _index >= 6,
          onTap: () => _go(_AuditStep.expectedVsActual),
        ),
      ],
    );
  }

  String get _title => switch (_step) {
        _AuditStep.preTradeSnapshot => 'Pre-Trade Snapshot',
        _AuditStep.decisionThresholds => 'Decision Thresholds',
        _AuditStep.modelAgreement => 'Model Agreement',
        _AuditStep.counterfactual => 'Why Trade vs Wait?',
        _AuditStep.tradeReplay => 'Trade Replay',
        _AuditStep.liveThesis => 'Live Thesis Monitor',
        _AuditStep.expectedVsActual => 'Expected vs Actual',
        _AuditStep.outcomeAttribution => _isProfit ? 'Profit Attribution' : 'Loss Attribution',
        _AuditStep.executionQuality => 'Execution Quality',
        _AuditStep.learningRecord => 'Learning Record',
      };

  String get _subtitle => switch (_step) {
        _AuditStep.preTradeSnapshot => 'Frozen evidence available when the decision was made',
        _AuditStep.decisionThresholds => 'Exactly which gates crossed the execution boundary',
        _AuditStep.modelAgreement => 'Where models agreed, disagreed or stayed neutral',
        _AuditStep.counterfactual => 'Conditions that would have prevented this trade',
        _AuditStep.tradeReplay => 'Market signal → decision → risk → execution → outcome',
        _AuditStep.liveThesis => 'Whether the original reasons for holding remain valid',
        _AuditStep.expectedVsActual => 'Original forecast compared with realized behavior',
        _AuditStep.outcomeAttribution => 'Likely contributors, not a claim of certain causality',
        _AuditStep.executionQuality => 'Separate idea quality from broker/execution quality',
        _AuditStep.learningRecord => 'What is stored, evaluated and never auto-promoted',
      };

  Widget _screen() => switch (_step) {
        _AuditStep.preTradeSnapshot => _preTradeSnapshot(),
        _AuditStep.decisionThresholds => _decisionThresholds(),
        _AuditStep.modelAgreement => _modelAgreement(),
        _AuditStep.counterfactual => _counterfactual(),
        _AuditStep.tradeReplay => _tradeReplay(),
        _AuditStep.liveThesis => _liveThesis(),
        _AuditStep.expectedVsActual => _expectedVsActual(),
        _AuditStep.outcomeAttribution => _outcomeAttribution(),
        _AuditStep.executionQuality => _executionQuality(),
        _AuditStep.learningRecord => _learningRecord(),
      };

  Widget _preTradeSnapshot() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _AuditGlow(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  const Icon(Icons.lock_clock_rounded, color: _AuditC.cyan),
                  const SizedBox(width: 9),
                  const Expanded(child: Text('Frozen at 10:31:02', style: TextStyle(fontSize: 18, fontWeight: FontWeight.w900))),
                  _AuditPill('${widget.opportunity.confidence}% AI', _AuditC.green),
                ],
              ),
              const SizedBox(height: 9),
              Text('${widget.side} ${widget.quote.symbol} · ₹${widget.entryPrice.toStringAsFixed(2)}', style: const TextStyle(fontSize: 25, fontWeight: FontWeight.w900)),
              const SizedBox(height: 6),
              const Text('This snapshot represents what the system knew before the order decision. Later information must never be back-filled into it.', style: TextStyle(color: _AuditC.muted, height: 1.45)),
            ],
          ),
        ),
        const SizedBox(height: 10),
        _AuditCard(
          child: Column(
            children: [
              _AuditInfoRow(label: 'Last price', value: '₹${widget.entryPrice.toStringAsFixed(2)}'),
              const Divider(height: 18),
              const _AuditInfoRow(label: 'VWAP', value: '₹2,931.80'),
              const Divider(height: 18),
              const _AuditInfoRow(label: 'RSI (14)', value: '62.4'),
              const Divider(height: 18),
              const _AuditInfoRow(label: 'MACD', value: 'Positive crossover'),
              const Divider(height: 18),
              const _AuditInfoRow(label: 'Volume', value: '1.6× 20-min average'),
              const Divider(height: 18),
              const _AuditInfoRow(label: 'Volatility', value: 'Normal · ATR 0.71%'),
              const Divider(height: 18),
              const _AuditInfoRow(label: 'Market regime', value: 'Bullish / supportive'),
              const Divider(height: 18),
              const _AuditInfoRow(label: 'Gemini evidence', value: '+0.72 · positive'),
            ],
          ),
        ),
        const SizedBox(height: 10),
        const _AuditCard(
          child: Column(
            children: [
              _AuditInfoRow(label: 'Feature snapshot', value: 'review-fixture-1042'),
              Divider(height: 18),
              _AuditInfoRow(label: 'Model bundle', value: 'fusion-review-v0.9'),
              Divider(height: 18),
              _AuditInfoRow(label: 'Data freshness', value: '18 sec'),
            ],
          ),
        ),
      ],
    );
  }

  Widget _decisionThresholds() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const _AuditSection('Execution gates'),
        const SizedBox(height: 8),
        const _AuditCard(
          child: Column(
            children: [
              _ThresholdRow(label: 'Fusion score', actual: '82', rule: '≥ 75', status: 'PASS'),
              Divider(height: 18),
              _ThresholdRow(label: 'Model confidence', actual: '81%', rule: '≥ 70%', status: 'PASS'),
              Divider(height: 18),
              _ThresholdRow(label: 'Relative volume', actual: '1.6×', rule: '≥ 1.3×', status: 'PASS'),
              Divider(height: 18),
              _ThresholdRow(label: 'Market regime', actual: 'Bullish', rule: 'Not bearish', status: 'PASS'),
              Divider(height: 18),
              _ThresholdRow(label: 'Risk budget', actual: '₹720', rule: '≤ ₹1,500', status: 'PASS'),
            ],
          ),
        ),
        const SizedBox(height: 10),
        const _AuditGlow(
          child: Row(
            children: [
              Icon(Icons.verified_rounded, color: _AuditC.green),
              SizedBox(width: 10),
              Expanded(child: Text('5 / 5 mandatory decision gates passed', style: TextStyle(fontWeight: FontWeight.w900))),
            ],
          ),
        ),
        const SizedBox(height: 10),
        const _AuditNotice('Passing the signal gates does not itself place an order. Risk approval remains a separate mandatory boundary.'),
      ],
    );
  }

  Widget _modelAgreement() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const _AuditSection('Independent opinions'),
        const SizedBox(height: 8),
        const _AuditCard(
          child: Column(
            children: [
              _AgreementRow(name: 'Strategy engine', decision: 'BUY', score: '76 / 100', tone: _AuditC.green),
              Divider(height: 18),
              _AgreementRow(name: 'Quant model', decision: 'BUY', score: '78 / 100', tone: _AuditC.green),
              Divider(height: 18),
              _AgreementRow(name: 'PyTorch model', decision: 'BUY', score: '81%', tone: _AuditC.green),
              Divider(height: 18),
              _AgreementRow(name: 'Gemini evidence', decision: 'POSITIVE', score: '+0.72', tone: _AuditC.cyan),
              Divider(height: 18),
              _AgreementRow(name: 'Market regime', decision: 'SUPPORT', score: '76 / 100', tone: _AuditC.green),
              Divider(height: 18),
              _AgreementRow(name: 'RSI quality', decision: 'NEUTRAL', score: '62.4', tone: _AuditC.amber),
            ],
          ),
        ),
        const SizedBox(height: 10),
        const _AuditGlow(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('Agreement summary', style: TextStyle(fontWeight: FontWeight.w900)),
              SizedBox(height: 8),
              Text('5 supporting · 1 neutral · 0 opposing', style: TextStyle(color: _AuditC.green, fontSize: 18, fontWeight: FontWeight.w900)),
              SizedBox(height: 5),
              Text('Neutral evidence stays visible instead of being hidden by the combined score.', style: TextStyle(color: _AuditC.muted, height: 1.4)),
            ],
          ),
        ),
      ],
    );
  }

  Widget _counterfactual() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const _AuditGlow(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(children: [Icon(Icons.alt_route_rounded, color: _AuditC.cyan), SizedBox(width: 9), Expanded(child: Text('Why trade instead of WAIT?', style: TextStyle(fontSize: 18, fontWeight: FontWeight.w900)))]),
              SizedBox(height: 8),
              Text('The order became eligible only because every mandatory threshold remained on the permitted side of its boundary.', style: TextStyle(color: _AuditC.muted, height: 1.45)),
            ],
          ),
        ),
        const SizedBox(height: 10),
        const _AuditCard(
          child: Column(
            children: [
              _CounterfactualRow(ifText: 'Volume < 1.3× average', thenText: 'WAIT / no breakout confirmation'),
              Divider(height: 18),
              _CounterfactualRow(ifText: 'Confidence < 70%', thenText: 'NO AUTO EXECUTION'),
              Divider(height: 18),
              _CounterfactualRow(ifText: 'Market regime turns bearish', thenText: 'BLOCK LONG setup'),
              Divider(height: 18),
              _CounterfactualRow(ifText: 'Calculated downside > ₹1,500', thenText: 'RISK ENGINE REJECTS'),
              Divider(height: 18),
              _CounterfactualRow(ifText: 'Emergency Stop = ON', thenText: 'ALL NEW ORDERS BLOCKED'),
            ],
          ),
        ),
        const SizedBox(height: 10),
        const _AuditNotice('Counterfactuals explain decision boundaries. They are not claims that changing one market variable would guarantee a different future outcome.'),
      ],
    );
  }

  Widget _tradeReplay() {
    final replay = <(String, String, Color)>[
      ('10:29:40', 'Price approaches breakout zone', _AuditC.muted),
      ('10:31:02', 'Market scan detects setup', _AuditC.cyan),
      ('10:31:04', 'Model ensemble reaches 82 / 100', _AuditC.cyan),
      ('10:31:05', 'Gemini evidence snapshot +0.72', _AuditC.violet),
      ('10:31:06', 'Risk Engine approves ₹${_maxRisk.toStringAsFixed(0)} downside', _AuditC.green),
      ('10:31:07', 'Order instruction created', _AuditC.green),
      ('10:31:09', 'PAPER fill @ ₹${widget.entryPrice.toStringAsFixed(2)}', _AuditC.green),
      ('10:34:12', 'Momentum remains consistent with thesis', _AuditC.cyan),
      ('11:05:17', widget.closed ? 'Exit recorded @ ₹${widget.exitPrice.toStringAsFixed(2)}' : 'Position still open in review state', _AuditC.amber),
    ];
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const _AuditSection('Replay timeline'),
        const SizedBox(height: 8),
        _AuditCard(
          child: Column(
            children: List.generate(replay.length, (index) {
              final item = replay[index];
              return Padding(
                padding: EdgeInsets.only(bottom: index == replay.length - 1 ? 0 : 14),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Container(width: 10, height: 10, margin: const EdgeInsets.only(top: 5), decoration: BoxDecoration(color: item.$3, shape: BoxShape.circle)),
                    const SizedBox(width: 10),
                    SizedBox(width: 64, child: Text(item.$1, style: const TextStyle(color: _AuditC.muted, fontSize: 11))),
                    const SizedBox(width: 8),
                    Expanded(child: Text(item.$2, style: const TextStyle(fontWeight: FontWeight.w700, height: 1.35))),
                  ],
                ),
              );
            }),
          ),
        ),
        const SizedBox(height: 10),
        const _AuditNotice('Production replay should be reconstructed from immutable event timestamps and captured evidence, not regenerated from a later explanation.'),
      ],
    );
  }

  Widget _liveThesis() {
    final health = widget.monitorStage >= 4 ? 72 : 78;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _AuditGlow(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  const Icon(Icons.monitor_heart_rounded, color: _AuditC.cyan),
                  const SizedBox(width: 9),
                  const Expanded(child: Text('Thesis Health', style: TextStyle(fontSize: 18, fontWeight: FontWeight.w900))),
                  Text('$health / 100', style: const TextStyle(color: _AuditC.green, fontSize: 22, fontWeight: FontWeight.w900)),
                ],
              ),
              const SizedBox(height: 10),
              LinearProgressIndicator(value: health / 100, minHeight: 8, borderRadius: BorderRadius.circular(99), backgroundColor: const Color(0xFF17243A), color: _AuditC.green),
              const SizedBox(height: 8),
              const Text('The position remains valid while the original thesis and risk conditions stay above their hold thresholds.', style: TextStyle(color: _AuditC.muted, height: 1.4)),
            ],
          ),
        ),
        const SizedBox(height: 10),
        const _AuditCard(
          child: Column(
            children: [
              _ThesisRow(label: 'Trend structure', state: 'INTACT', good: true),
              Divider(height: 18),
              _ThesisRow(label: 'Volume support', state: 'VALID', good: true),
              Divider(height: 18),
              _ThesisRow(label: 'Market regime', state: 'SUPPORTIVE', good: true),
              Divider(height: 18),
              _ThesisRow(label: 'Stop protection', state: 'ACTIVE', good: true),
              Divider(height: 18),
              _ThesisRow(label: 'Momentum', state: 'WEAKENING', good: false),
            ],
          ),
        ),
        const SizedBox(height: 10),
        const _AuditCard(
          child: Column(
            children: [
              _AuditInfoRow(label: 'Original confidence', value: '82%'),
              Divider(height: 18),
              _AuditInfoRow(label: 'Current thesis confidence', value: '78%'),
              Divider(height: 18),
              _AuditInfoRow(label: 'Exit threshold', value: 'Health < 55 or stop hit'),
            ],
          ),
        ),
        const SizedBox(height: 10),
        const _AuditNotice('A lower thesis score is not automatically a sell signal unless the configured exit/risk rule is crossed.'),
      ],
    );
  }

  Widget _expectedVsActual() {
    final actualMove = widget.closed ? _actualMovePct : 1.18;
    final profitable = widget.closed ? widget.realizedPnl >= 0 : true;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _AuditGlow(
          child: Row(
            children: [
              const Icon(Icons.compare_arrows_rounded, color: _AuditC.cyan),
              const SizedBox(width: 9),
              const Expanded(child: Text('Forecast vs realized path', style: TextStyle(fontSize: 18, fontWeight: FontWeight.w900))),
              _AuditPill(profitable ? 'THESIS HELD' : 'THESIS FAILED', profitable ? _AuditC.green : _AuditC.red),
            ],
          ),
        ),
        const SizedBox(height: 10),
        _AuditCard(
          child: Column(
            children: [
              _ComparisonRow(label: 'Direction', expected: 'Bullish', actual: profitable ? 'Bullish' : 'Adverse reversal'),
              const Divider(height: 18),
              _ComparisonRow(label: 'Move', expected: '+2.4% target', actual: '${actualMove >= 0 ? '+' : ''}${actualMove.toStringAsFixed(2)}%'),
              const Divider(height: 18),
              const _ComparisonRow(label: 'Hold time', expected: '30–90 min', actual: '34 min'),
              const Divider(height: 18),
              _ComparisonRow(label: 'Entry', expected: '₹${widget.opportunity.entry.toStringAsFixed(2)}', actual: '₹${widget.entryPrice.toStringAsFixed(2)}'),
              const Divider(height: 18),
              _ComparisonRow(label: 'Exit', expected: 'Target ₹${widget.opportunity.target.toStringAsFixed(2)}', actual: widget.closed ? '₹${widget.exitPrice.toStringAsFixed(2)}' : 'Still open'),
            ],
          ),
        ),
        const SizedBox(height: 10),
        const _AuditNotice('A correct direction with poor execution can still lose money; a profitable trade can also contain incorrect model assumptions.'),
      ],
    );
  }

  Widget _outcomeAttribution() {
    final factors = _isProfit
        ? const <(String, int, String)>[
            ('Breakout continuation', 35, 'Observed after entry'),
            ('Volume confirmation', 25, 'Stayed above threshold'),
            ('Supportive market regime', 18, 'Broad market remained positive'),
            ('Entry timing', 12, 'Inside planned zone'),
            ('Execution quality', 10, 'Low mock slippage'),
          ]
        : const <(String, int, String)>[
            ('Regime reversal', 32, 'Broad market turned adverse'),
            ('Breakout failure', 28, 'Price returned below breakout'),
            ('Unexpected adverse catalyst', 18, 'New information after entry'),
            ('Entry timing', 12, 'Late relative to local impulse'),
            ('Execution/slippage', 10, 'Worsened realized loss'),
          ];
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Expanded(
              child: ChoiceChip(
                label: const SizedBox(width: double.infinity, child: Text('PROFIT EXAMPLE', textAlign: TextAlign.center)),
                selected: !_showLossScenario,
                onSelected: (_) => setState(() => _showLossScenario = false),
              ),
            ),
            const SizedBox(width: 8),
            Expanded(
              child: ChoiceChip(
                label: const SizedBox(width: double.infinity, child: Text('LOSS EXAMPLE', textAlign: TextAlign.center)),
                selected: _showLossScenario,
                onSelected: (_) => setState(() => _showLossScenario = true),
              ),
            ),
          ],
        ),
        const SizedBox(height: 10),
        _AuditGlow(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(_isProfit ? 'Why this trade likely worked' : 'Why this trade likely failed', style: TextStyle(color: _isProfit ? _AuditC.green : _AuditC.red, fontSize: 20, fontWeight: FontWeight.w900)),
              const SizedBox(height: 6),
              Text(_isProfit
                  ? 'The original setup remained valid after entry and price followed the expected breakout path.'
                  : 'The setup was valid at entry, but later market evidence moved against the original thesis.', style: const TextStyle(color: _AuditC.muted, height: 1.45)),
            ],
          ),
        ),
        const SizedBox(height: 10),
        _AuditCard(
          child: Column(
            children: factors.map((factor) => Padding(
              padding: const EdgeInsets.only(bottom: 12),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(children: [Expanded(child: Text(factor.$1, style: const TextStyle(fontWeight: FontWeight.w800))), Text('${factor.$2}%', style: TextStyle(color: _isProfit ? _AuditC.green : _AuditC.red, fontWeight: FontWeight.w900))]),
                  const SizedBox(height: 5),
                  LinearProgressIndicator(value: factor.$2 / 100, minHeight: 6, borderRadius: BorderRadius.circular(99), backgroundColor: const Color(0xFF17243A), color: _isProfit ? _AuditC.green : _AuditC.red),
                  const SizedBox(height: 4),
                  Text(factor.$3, style: const TextStyle(color: _AuditC.muted, fontSize: 11)),
                ],
              ),
            )).toList(),
          ),
        ),
        const SizedBox(height: 10),
        _AuditNotice(_isProfit
            ? 'Profit does not prove every signal was correct. These percentages are mock explanatory weights for UI review, not causal proof.'
            : 'A valid high-confidence setup can still lose. Confidence is a probability estimate, never a guarantee. Attribution must distinguish model error from new market information.'),
      ],
    );
  }

  Widget _executionQuality() {
    final signalPrice = widget.entryPrice - 2.15;
    final slippage = widget.entryPrice - signalPrice;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const _AuditSection('Execution audit'),
        const SizedBox(height: 8),
        _AuditCard(
          child: Column(
            children: [
              _AuditInfoRow(label: 'Signal reference price', value: '₹${signalPrice.toStringAsFixed(2)}'),
              const Divider(height: 18),
              const _AuditInfoRow(label: 'Instruction created', value: '10:31:07.118'),
              const Divider(height: 18),
              const _AuditInfoRow(label: 'Mock broker accepted', value: '10:31:08.041'),
              const Divider(height: 18),
              _AuditInfoRow(label: 'Fill price', value: '₹${widget.entryPrice.toStringAsFixed(2)}'),
              const Divider(height: 18),
              _AuditInfoRow(label: 'Price slippage', value: '₹${slippage.toStringAsFixed(2)} / share'),
              const Divider(height: 18),
              const _AuditInfoRow(label: 'Decision → fill latency', value: '1.9 sec'),
              const Divider(height: 18),
              const _AuditInfoRow(label: 'Mock fees + taxes', value: '₹18.40'),
            ],
          ),
        ),
        const SizedBox(height: 10),
        _AuditGlow(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text('Execution impact', style: TextStyle(fontWeight: FontWeight.w900)),
              const SizedBox(height: 6),
              Text('Execution reduced the theoretical P&L by approximately ₹${(slippage * widget.quantity + 18.4).toStringAsFixed(0)} in this mock example.', style: const TextStyle(color: _AuditC.muted, height: 1.45)),
            ],
          ),
        ),
        const SizedBox(height: 10),
        const _AuditNotice('This separation prevents a good model decision with poor execution from being incorrectly labeled as a model failure.'),
      ],
    );
  }

  Widget _learningRecord() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const _AuditGlow(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(children: [Icon(Icons.school_outlined, color: _AuditC.cyan), SizedBox(width: 9), Expanded(child: Text('Governed learning record', style: TextStyle(fontSize: 18, fontWeight: FontWeight.w900)))]),
              SizedBox(height: 7),
              Text('The system preserves the decision and outcome as evidence. It does not silently rewrite the explanation after seeing the result.', style: TextStyle(color: _AuditC.muted, height: 1.45)),
            ],
          ),
        ),
        const SizedBox(height: 10),
        const _AuditCard(
          child: Column(
            children: [
              _LearningRow(label: 'Pre-trade feature snapshot', state: 'CAPTURED'),
              Divider(height: 18),
              _LearningRow(label: 'Model + strategy versions', state: 'CAPTURED'),
              Divider(height: 18),
              _LearningRow(label: 'Gemini evidence snapshot', state: 'CAPTURED'),
              Divider(height: 18),
              _LearningRow(label: 'Risk decision + sizing', state: 'CAPTURED'),
              Divider(height: 18),
              _LearningRow(label: 'Execution events', state: 'CAPTURED'),
              Divider(height: 18),
              _LearningRow(label: 'Outcome metrics', state: 'EVALUATION CANDIDATE'),
              Divider(height: 18),
              _LearningRow(label: 'Automatic retraining', state: 'NO'),
              Divider(height: 18),
              _LearningRow(label: 'Automatic model promotion', state: 'NO'),
            ],
          ),
        ),
        const SizedBox(height: 10),
        const _AuditCard(
          child: Column(
            children: [
              _AuditInfoRow(label: 'Model bundle', value: 'fusion-review-v0.9'),
              Divider(height: 18),
              _AuditInfoRow(label: 'Evaluation dataset', value: 'review-eval-2026-11'),
              Divider(height: 18),
              _AuditInfoRow(label: 'Promotion state', value: 'REQUIRES OFFLINE GATES'),
            ],
          ),
        ),
        const SizedBox(height: 10),
        const _AuditNotice('Production model changes should require batch evaluation, calibration, walk-forward evidence, risk review and controlled promotion before affecting LIVE decisions.'),
      ],
    );
  }

  Widget _footerNavigation() {
    return Column(
      children: [
        _AuditPrimaryButton(
          label: _index == _steps.length - 1 ? 'Close Decision Audit' : 'Next: ${_stepTitle(_steps[_index + 1])}',
          icon: _index == _steps.length - 1 ? Icons.check_rounded : Icons.arrow_forward_rounded,
          onTap: _next,
        ),
        if (_index > 0) ...[
          const SizedBox(height: 9),
          OutlinedButton.icon(
            onPressed: _back,
            icon: const Icon(Icons.arrow_back_rounded),
            label: Text('Previous: ${_stepTitle(_steps[_index - 1])}'),
            style: OutlinedButton.styleFrom(minimumSize: const Size.fromHeight(46)),
          ),
        ],
      ],
    );
  }

  String _stepTitle(_AuditStep step) => switch (step) {
        _AuditStep.preTradeSnapshot => 'Snapshot',
        _AuditStep.decisionThresholds => 'Thresholds',
        _AuditStep.modelAgreement => 'Model Agreement',
        _AuditStep.counterfactual => 'Trade vs Wait',
        _AuditStep.tradeReplay => 'Replay',
        _AuditStep.liveThesis => 'Live Thesis',
        _AuditStep.expectedVsActual => 'Expected vs Actual',
        _AuditStep.outcomeAttribution => 'Outcome Attribution',
        _AuditStep.executionQuality => 'Execution Quality',
        _AuditStep.learningRecord => 'Learning Record',
      };
}

class _AuditC {
  static const cyan = Color(0xFF1DE7FF);
  static const violet = Color(0xFF8B5CFF);
  static const green = Color(0xFF39F59A);
  static const red = Color(0xFFFF6B7D);
  static const amber = Color(0xFFFFC857);
  static const muted = Color(0xFF8FA4C2);
}

class _AuditCard extends StatelessWidget {
  const _AuditCard({required this.child});
  final Widget child;

  @override
  Widget build(BuildContext context) => Material(
        color: const Color(0xFF08162A),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16), side: const BorderSide(color: Color(0xFF183050))),
        clipBehavior: Clip.antiAlias,
        child: Padding(padding: const EdgeInsets.all(15), child: child),
      );
}

class _AuditGlow extends StatelessWidget {
  const _AuditGlow({required this.child});
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

class _AuditPrimaryButton extends StatelessWidget {
  const _AuditPrimaryButton({required this.label, required this.icon, required this.onTap});
  final String label;
  final IconData icon;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) => DecoratedBox(
        decoration: BoxDecoration(borderRadius: BorderRadius.circular(13), gradient: const LinearGradient(colors: [_AuditC.cyan, _AuditC.violet])),
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
                  Icon(icon, color: const Color(0xFF03111F), size: 20),
                  const SizedBox(width: 8),
                  Flexible(child: Text(label, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(color: Color(0xFF03111F), fontWeight: FontWeight.w900))),
                ],
              ),
            ),
          ),
        ),
      );
}

class _AuditInfoRow extends StatelessWidget {
  const _AuditInfoRow({required this.label, required this.value});
  final String label;
  final String value;

  @override
  Widget build(BuildContext context) => Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Expanded(child: Text(label, style: const TextStyle(color: _AuditC.muted, fontSize: 12))),
          const SizedBox(width: 10),
          Expanded(child: Text(value, textAlign: TextAlign.right, style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 12))),
        ],
      );
}

class _ThresholdRow extends StatelessWidget {
  const _ThresholdRow({required this.label, required this.actual, required this.rule, required this.status});
  final String label;
  final String actual;
  final String rule;
  final String status;

  @override
  Widget build(BuildContext context) => Row(
        children: [
          Expanded(flex: 4, child: Text(label, style: const TextStyle(fontWeight: FontWeight.w700))),
          Expanded(flex: 2, child: Text(actual, textAlign: TextAlign.right, style: const TextStyle(fontWeight: FontWeight.w900))),
          const SizedBox(width: 7),
          Expanded(flex: 2, child: Text(rule, textAlign: TextAlign.right, style: const TextStyle(color: _AuditC.muted, fontSize: 11))),
          const SizedBox(width: 7),
          Text(status, style: const TextStyle(color: _AuditC.green, fontSize: 10, fontWeight: FontWeight.w900)),
        ],
      );
}

class _AgreementRow extends StatelessWidget {
  const _AgreementRow({required this.name, required this.decision, required this.score, required this.tone});
  final String name;
  final String decision;
  final String score;
  final Color tone;

  @override
  Widget build(BuildContext context) => Row(
        children: [
          Expanded(child: Text(name, style: const TextStyle(fontWeight: FontWeight.w700))),
          const SizedBox(width: 7),
          Text(decision, style: TextStyle(color: tone, fontSize: 11, fontWeight: FontWeight.w900)),
          const SizedBox(width: 9),
          SizedBox(width: 62, child: Text(score, textAlign: TextAlign.right, style: const TextStyle(color: _AuditC.muted, fontSize: 11))),
        ],
      );
}

class _CounterfactualRow extends StatelessWidget {
  const _CounterfactualRow({required this.ifText, required this.thenText});
  final String ifText;
  final String thenText;

  @override
  Widget build(BuildContext context) => Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Icon(Icons.call_split_rounded, color: _AuditC.amber, size: 17),
          const SizedBox(width: 9),
          Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [Text(ifText, style: const TextStyle(fontWeight: FontWeight.w800)), const SizedBox(height: 3), Text(thenText, style: const TextStyle(color: _AuditC.muted, fontSize: 11, height: 1.3))])),
        ],
      );
}

class _ThesisRow extends StatelessWidget {
  const _ThesisRow({required this.label, required this.state, required this.good});
  final String label;
  final String state;
  final bool good;

  @override
  Widget build(BuildContext context) => Row(
        children: [
          Icon(good ? Icons.check_circle_rounded : Icons.warning_amber_rounded, color: good ? _AuditC.green : _AuditC.amber, size: 18),
          const SizedBox(width: 9),
          Expanded(child: Text(label, style: const TextStyle(fontWeight: FontWeight.w700))),
          Text(state, style: TextStyle(color: good ? _AuditC.green : _AuditC.amber, fontSize: 10, fontWeight: FontWeight.w900)),
        ],
      );
}

class _ComparisonRow extends StatelessWidget {
  const _ComparisonRow({required this.label, required this.expected, required this.actual});
  final String label;
  final String expected;
  final String actual;

  @override
  Widget build(BuildContext context) => Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(label, style: const TextStyle(color: _AuditC.muted, fontSize: 11)),
          const SizedBox(height: 5),
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Expanded(child: Text('Expected\n$expected', style: const TextStyle(fontWeight: FontWeight.w800, height: 1.35))),
              const SizedBox(width: 10),
              Expanded(child: Text('Actual\n$actual', textAlign: TextAlign.right, style: const TextStyle(fontWeight: FontWeight.w800, height: 1.35))),
            ],
          ),
        ],
      );
}

class _LearningRow extends StatelessWidget {
  const _LearningRow({required this.label, required this.state});
  final String label;
  final String state;

  @override
  Widget build(BuildContext context) {
    final negative = state == 'NO';
    return Row(
      children: [
        Expanded(child: Text(label, style: const TextStyle(fontWeight: FontWeight.w700))),
        const SizedBox(width: 9),
        Flexible(child: Text(state, textAlign: TextAlign.right, style: TextStyle(color: negative ? _AuditC.amber : _AuditC.green, fontSize: 10, fontWeight: FontWeight.w900))),
      ],
    );
  }
}

class _AuditSection extends StatelessWidget {
  const _AuditSection(this.text);
  final String text;

  @override
  Widget build(BuildContext context) => Text(text, style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w900));
}

class _AuditNotice extends StatelessWidget {
  const _AuditNotice(this.text);
  final String text;

  @override
  Widget build(BuildContext context) => Container(
        width: double.infinity,
        padding: const EdgeInsets.all(13),
        decoration: BoxDecoration(color: const Color(0xFF0A1930), borderRadius: BorderRadius.circular(13), border: Border.all(color: const Color(0xFF1E3D62))),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Icon(Icons.info_outline_rounded, color: _AuditC.cyan, size: 18),
            const SizedBox(width: 9),
            Expanded(child: Text(text, style: const TextStyle(color: _AuditC.muted, fontSize: 11, height: 1.45))),
          ],
        ),
      );
}

class _AuditPill extends StatelessWidget {
  const _AuditPill(this.text, this.color);
  final String text;
  final Color color;

  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 5),
        decoration: BoxDecoration(color: color.withValues(alpha: .12), borderRadius: BorderRadius.circular(99), border: Border.all(color: color.withValues(alpha: .42))),
        child: Text(text, maxLines: 1, overflow: TextOverflow.ellipsis, style: TextStyle(color: color, fontSize: 9, fontWeight: FontWeight.w900)),
      );
}

class _AuditPhaseChip extends StatelessWidget {
  const _AuditPhaseChip({required this.label, required this.selected, required this.onTap});
  final String label;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) => InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(99),
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 11, vertical: 7),
          decoration: BoxDecoration(
            color: selected ? const Color(0x221DE7FF) : const Color(0xFF071326),
            borderRadius: BorderRadius.circular(99),
            border: Border.all(color: selected ? _AuditC.cyan : const Color(0xFF1A3150)),
          ),
          child: Text(label, style: TextStyle(color: selected ? _AuditC.cyan : _AuditC.muted, fontSize: 10, fontWeight: FontWeight.w900)),
        ),
      );
}

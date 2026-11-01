import 'dart:math' as math;

import 'package:flutter/material.dart';

import 'mock_review_data.dart';

enum MockTradeJourneyStart { stock, analysis }

enum _TradeStep {
  stockDetail,
  aiAnalysis,
  opportunityDetail,
  decisionSummary,
  signalBreakdown,
  modelEvidence,
  tradeTicket,
  orderReview,
  riskResult,
  riskDecision,
  executionSuccess,
  decisionTimeline,
  positionDetail,
  tradeMonitor,
  exitConfirm,
  tradeResult,
  postTradeLearning,
}

class MockTradeJourney extends StatefulWidget {
  const MockTradeJourney({
    super.key,
    required this.store,
    required this.onExit,
    this.quote,
    this.opportunity,
    this.start = MockTradeJourneyStart.stock,
  });

  final MockReviewStore store;
  final VoidCallback onExit;
  final MockQuote? quote;
  final MockOpportunity? opportunity;
  final MockTradeJourneyStart start;

  @override
  State<MockTradeJourney> createState() => _MockTradeJourneyState();
}

class _MockTradeJourneyState extends State<MockTradeJourney> {
  late MockQuote _quote;
  late MockOpportunity _opportunity;
  late _TradeStep _step;

  String _side = 'BUY';
  String _orderType = 'Market';
  int _quantity = 10;
  bool _useStop = true;
  bool _useTarget = true;
  bool _executed = false;
  bool _closed = false;
  int _monitorStage = 2;
  String _exitReason = 'AI target reached';
  late double _entryPrice;
  late double _exitPrice;

  @override
  void initState() {
    super.initState();
    _quote = widget.quote ?? _quoteForOpportunity(widget.opportunity) ?? widget.store.quotes.firstWhere((item) => item.symbol == 'RELIANCE');
    _opportunity = widget.opportunity ?? _opportunityForQuote(_quote);
    _entryPrice = _opportunity.entry;
    _exitPrice = _opportunity.target - 8;
    _step = widget.start == MockTradeJourneyStart.analysis ? _TradeStep.aiAnalysis : _TradeStep.stockDetail;
  }

  MockQuote? _quoteForOpportunity(MockOpportunity? opportunity) {
    if (opportunity == null) return null;
    for (final quote in widget.store.quotes) {
      if (quote.symbol == opportunity.symbol) return quote;
    }
    return MockQuote(
      symbol: opportunity.symbol,
      name: opportunity.company,
      price: opportunity.price,
      changePercent: opportunity.changePercent,
      sparkline: const [22, 25, 27, 26, 31, 34, 38, 42, 47],
    );
  }

  MockOpportunity _opportunityForQuote(MockQuote quote) {
    for (final item in widget.store.opportunities) {
      if (item.symbol == quote.symbol) return item;
    }
    return MockOpportunity(
      symbol: quote.symbol,
      company: quote.name,
      price: quote.price,
      changePercent: quote.changePercent,
      rating: quote.changePercent >= 0 ? 'Buy' : 'Watch',
      confidence: quote.changePercent >= 0 ? 78 : 69,
      setup: quote.changePercent >= 0 ? 'Trend continuation' : 'Mean reversion watch',
      riskReward: '1 : 2.2',
      timeframe: '15 min',
      thesis: 'Mock AI combines price structure, momentum, volume and market context to produce this review-mode setup.',
      entry: quote.price,
      stop: quote.price * .989,
      target: quote.price * 1.024,
    );
  }

  void _go(_TradeStep step) => setState(() => _step = step);

  void _back() {
    switch (_step) {
      case _TradeStep.stockDetail:
        widget.onExit();
        return;
      case _TradeStep.aiAnalysis:
        _go(_TradeStep.stockDetail);
        return;
      case _TradeStep.opportunityDetail:
        _go(_TradeStep.aiAnalysis);
        return;
      case _TradeStep.decisionSummary:
        _go(_TradeStep.opportunityDetail);
        return;
      case _TradeStep.signalBreakdown:
        _go(_TradeStep.decisionSummary);
        return;
      case _TradeStep.modelEvidence:
        _go(_TradeStep.signalBreakdown);
        return;
      case _TradeStep.tradeTicket:
        _go(_TradeStep.opportunityDetail);
        return;
      case _TradeStep.orderReview:
        _go(_TradeStep.tradeTicket);
        return;
      case _TradeStep.riskResult:
        _go(_TradeStep.orderReview);
        return;
      case _TradeStep.riskDecision:
        _go(_TradeStep.riskResult);
        return;
      case _TradeStep.executionSuccess:
        _go(_TradeStep.riskDecision);
        return;
      case _TradeStep.decisionTimeline:
        _go(_TradeStep.executionSuccess);
        return;
      case _TradeStep.positionDetail:
        _go(_TradeStep.decisionTimeline);
        return;
      case _TradeStep.tradeMonitor:
        _go(_TradeStep.positionDetail);
        return;
      case _TradeStep.exitConfirm:
        _go(_TradeStep.tradeMonitor);
        return;
      case _TradeStep.tradeResult:
        _go(_TradeStep.positionDetail);
        return;
      case _TradeStep.postTradeLearning:
        _go(_TradeStep.tradeResult);
        return;
    }
  }

  double get _estimatedValue => _quantity * _entryPrice;
  double get _maxLoss => _quantity * (_entryPrice - _opportunity.stop).abs();
  double get _targetProfit => _quantity * (_opportunity.target - _entryPrice).abs();
  double get _unrealizedPnl => _quantity * (_quote.price - _entryPrice);
  double get _realizedPnl => _quantity * (_exitPrice - _entryPrice) * (_side == 'BUY' ? 1 : -1);

  void _executeMockTrade() {
    if (_executed) return;
    final signedLast = _side == 'BUY' ? _quote.price : _entryPrice - 10;
    widget.store.orders.insert(
      0,
      MockOrder(
        symbol: _quote.symbol,
        side: _side,
        quantity: _quantity,
        averagePrice: _entryPrice,
        status: 'Completed',
        time: 'now',
      ),
    );
    widget.store.positions.add(
      MockPosition(
        symbol: _quote.symbol,
        quantity: _quantity,
        averagePrice: _entryPrice,
        lastPrice: signedLast,
      ),
    );
    widget.store.notifications = [
      MockNotificationItem(
        title: 'PAPER order executed',
        message: '$_side $_quantity ${_quote.symbol} @ ₹${_entryPrice.toStringAsFixed(2)}',
        category: 'Trades',
        time: 'now',
      ),
      ...widget.store.notifications,
    ];
    widget.store.refresh();
    _executed = true;
  }

  void _closeMockTrade() {
    if (_closed) return;
    final index = widget.store.positions.lastIndexWhere((item) => item.symbol == _quote.symbol && item.quantity == _quantity);
    if (index >= 0) widget.store.positions.removeAt(index);
    widget.store.orders.insert(
      0,
      MockOrder(
        symbol: _quote.symbol,
        side: _side == 'BUY' ? 'SELL' : 'BUY',
        quantity: _quantity,
        averagePrice: _exitPrice,
        status: 'Completed',
        time: 'now',
      ),
    );
    widget.store.todayPnl += _realizedPnl;
    widget.store.totalPnl += _realizedPnl;
    widget.store.currentValue += _realizedPnl;
    widget.store.notifications = [
      MockNotificationItem(
        title: 'PAPER position closed',
        message: '${_quote.symbol} realized P&L ${_realizedPnl >= 0 ? '+' : '-'}₹${_realizedPnl.abs().toStringAsFixed(0)}',
        category: 'Trades',
        time: 'now',
      ),
      ...widget.store.notifications,
    ];
    widget.store.refresh();
    _closed = true;
  }

  @override
  Widget build(BuildContext context) {
    return ColoredBox(
      color: const Color(0xFF020817),
      child: _TradePage(
        title: _title,
        subtitle: _subtitle,
        onBack: _back,
        child: _screen(),
      ),
    );
  }

  String get _title => switch (_step) {
        _TradeStep.stockDetail => 'Stock Detail',
        _TradeStep.aiAnalysis => 'AI Analysis',
        _TradeStep.opportunityDetail => 'AI Opportunity',
        _TradeStep.decisionSummary => 'Trade Decision',
        _TradeStep.signalBreakdown => 'Signal Breakdown',
        _TradeStep.modelEvidence => 'AI / Model Evidence',
        _TradeStep.tradeTicket => 'PAPER Trade',
        _TradeStep.orderReview => 'Review Order',
        _TradeStep.riskResult => 'Risk Check',
        _TradeStep.riskDecision => 'Risk Engine Decision',
        _TradeStep.executionSuccess => 'Order Executed',
        _TradeStep.decisionTimeline => 'Decision Timeline',
        _TradeStep.positionDetail => 'Position Detail',
        _TradeStep.tradeMonitor => 'AI Trade Monitor',
        _TradeStep.exitConfirm => 'Exit Position',
        _TradeStep.tradeResult => 'Trade Result',
        _TradeStep.postTradeLearning => 'Post-Trade Review',
      };

  String get _subtitle => switch (_step) {
        _TradeStep.stockDetail => '${_quote.symbol} · ${_quote.name}',
        _TradeStep.aiAnalysis => 'Multi-signal review-mode analysis',
        _TradeStep.opportunityDetail => '${_opportunity.rating} · ${_opportunity.confidence}% confidence',
        _TradeStep.decisionSummary => 'Why the system selected this trade',
        _TradeStep.signalBreakdown => 'Weighted evidence behind the fused score',
        _TradeStep.modelEvidence => 'Strategy + quantitative + PyTorch + Gemini evidence',
        _TradeStep.tradeTicket => 'Configure a mock PAPER order',
        _TradeStep.orderReview => 'Confirm exposure before risk validation',
        _TradeStep.riskResult => 'Mandatory risk engine preview',
        _TradeStep.riskDecision => 'Detailed approval and position-sizing justification',
        _TradeStep.executionSuccess => 'PAPER only · no broker call',
        _TradeStep.decisionTimeline => 'Auditable provenance from scan to fill',
        _TradeStep.positionDetail => 'Live-looking mock position state',
        _TradeStep.tradeMonitor => 'AI lifecycle monitoring',
        _TradeStep.exitConfirm => 'Close the mock PAPER position',
        _TradeStep.tradeResult => 'Outcome and AI learning summary',
        _TradeStep.postTradeLearning => 'Prediction quality, execution quality and governed learning data',
      };

  Widget _screen() => switch (_step) {
        _TradeStep.stockDetail => _stockDetail(),
        _TradeStep.aiAnalysis => _aiAnalysis(),
        _TradeStep.opportunityDetail => _opportunityDetail(),
        _TradeStep.decisionSummary => _decisionSummary(),
        _TradeStep.signalBreakdown => _signalBreakdown(),
        _TradeStep.modelEvidence => _modelEvidence(),
        _TradeStep.tradeTicket => _tradeTicket(),
        _TradeStep.orderReview => _orderReview(),
        _TradeStep.riskResult => _riskResult(),
        _TradeStep.riskDecision => _riskDecision(),
        _TradeStep.executionSuccess => _executionSuccess(),
        _TradeStep.decisionTimeline => _decisionTimeline(),
        _TradeStep.positionDetail => _positionDetail(),
        _TradeStep.tradeMonitor => _tradeMonitor(),
        _TradeStep.exitConfirm => _exitConfirm(),
        _TradeStep.tradeResult => _tradeResult(),
        _TradeStep.postTradeLearning => _postTradeLearning(),
      };

  Widget _stockDetail() {
    final watching = widget.store.watchlist.contains(_quote.symbol);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            _SymbolBadge(_quote.symbol),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(_quote.symbol, style: const TextStyle(fontSize: 22, fontWeight: FontWeight.w900)),
                  Text(_quote.name, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(color: _C.muted, fontSize: 12)),
                ],
              ),
            ),
            IconButton(
              onPressed: () => widget.store.toggleWatchlist(_quote.symbol),
              icon: Icon(watching ? Icons.star_rounded : Icons.star_border_rounded, color: watching ? const Color(0xFFFFD85C) : _C.muted),
            ),
          ],
        ),
        const SizedBox(height: 16),
        Row(
          crossAxisAlignment: CrossAxisAlignment.end,
          children: [
            Text('₹${_quote.price.toStringAsFixed(2)}', style: const TextStyle(fontSize: 30, fontWeight: FontWeight.w900)),
            const SizedBox(width: 10),
            Padding(
              padding: const EdgeInsets.only(bottom: 4),
              child: Text(_percent(_quote.changePercent), style: TextStyle(color: _quote.changePercent >= 0 ? _C.green : _C.red, fontWeight: FontWeight.w900)),
            ),
          ],
        ),
        const SizedBox(height: 12),
        _Card(
          child: SizedBox(
            height: 150,
            width: double.infinity,
            child: _MiniChart(values: _quote.sparkline, positive: _quote.changePercent >= 0),
          ),
        ),
        const SizedBox(height: 10),
        Row(
          children: [
            Expanded(child: _Metric(label: 'Open', value: '₹${(_quote.price * .992).toStringAsFixed(0)}')),
            const SizedBox(width: 8),
            Expanded(child: _Metric(label: 'Day High', value: '₹${(_quote.price * 1.009).toStringAsFixed(0)}')),
            const SizedBox(width: 8),
            Expanded(child: _Metric(label: 'Day Low', value: '₹${(_quote.price * .985).toStringAsFixed(0)}')),
          ],
        ),
        const SizedBox(height: 10),
        _Card(
          child: Column(
            children: const [
              _InfoRow(label: 'Volume', value: '8.42M'),
              Divider(height: 18),
              _InfoRow(label: 'VWAP', value: '₹2,931.80'),
              Divider(height: 18),
              _InfoRow(label: '52W range', value: '₹2,220 – ₹3,217'),
            ],
          ),
        ),
        const SizedBox(height: 10),
        _Glow(
          child: Row(
            children: [
              const Icon(Icons.auto_awesome_rounded, color: _C.cyan),
              const SizedBox(width: 10),
              Expanded(child: Text('AI setup score ${_opportunity.confidence}/100', style: const TextStyle(fontWeight: FontWeight.w900))),
              const _Pill('READY', _C.green),
            ],
          ),
        ),
        const SizedBox(height: 16),
        _PrimaryButton(label: 'Analyze with AI', icon: Icons.auto_awesome_rounded, onTap: () => _go(_TradeStep.aiAnalysis)),
      ],
    );
  }

  Widget _aiAnalysis() {
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
                  const Expanded(child: Text('AI Confidence', style: TextStyle(fontSize: 18, fontWeight: FontWeight.w900))),
                  Text('${_opportunity.confidence}%', style: const TextStyle(color: _C.green, fontSize: 22, fontWeight: FontWeight.w900)),
                ],
              ),
              const SizedBox(height: 12),
              ClipRRect(
                borderRadius: BorderRadius.circular(99),
                child: LinearProgressIndicator(value: _opportunity.confidence / 100, minHeight: 8, backgroundColor: const Color(0xFF17243A), color: _C.cyan),
              ),
              const SizedBox(height: 10),
              const Text('Signals agree on a bullish intraday setup. This is synthetic review data and not investment advice.', style: TextStyle(color: _C.muted, height: 1.4)),
            ],
          ),
        ),
        const SizedBox(height: 12),
        const _Section('Model signals'),
        const SizedBox(height: 8),
        const _SignalRow(icon: Icons.show_chart_rounded, title: 'Trend model', value: 'Bullish', score: '88%'),
        const _SignalRow(icon: Icons.speed_rounded, title: 'Momentum model', value: 'Strong', score: '84%'),
        const _SignalRow(icon: Icons.waterfall_chart_rounded, title: 'Volume confirmation', value: 'Confirmed', score: '79%'),
        const _SignalRow(icon: Icons.public_rounded, title: 'Market regime', value: 'Supportive', score: '76%'),
        const SizedBox(height: 14),
        const _Section('Technical context'),
        const SizedBox(height: 8),
        _Card(
          child: const Column(
            children: [
              _InfoRow(label: 'RSI (14)', value: '62.4 · healthy'),
              Divider(height: 18),
              _InfoRow(label: 'MACD', value: 'Positive crossover'),
              Divider(height: 18),
              _InfoRow(label: 'VWAP', value: 'Price above VWAP'),
              Divider(height: 18),
              _InfoRow(label: 'Volume', value: '1.6× average'),
            ],
          ),
        ),
        const SizedBox(height: 12),
        const _Section('Catalysts'),
        const SizedBox(height: 8),
        const _Card(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('• Sector breadth is positive across large-cap peers.', style: TextStyle(color: _C.muted, height: 1.5)),
              Text('• No adverse mock news flags in the last 60 minutes.', style: TextStyle(color: _C.muted, height: 1.5)),
              Text('• Breakout volume is above the review threshold.', style: TextStyle(color: _C.muted, height: 1.5)),
            ],
          ),
        ),
        const SizedBox(height: 16),
        _PrimaryButton(label: 'View AI Opportunity', icon: Icons.arrow_forward_rounded, onTap: () => _go(_TradeStep.opportunityDetail)),
      ],
    );
  }

  Widget _opportunityDetail() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Expanded(child: Text(_opportunity.rating, style: const TextStyle(color: _C.green, fontSize: 27, fontWeight: FontWeight.w900))),
            _Pill('${_opportunity.confidence}% AI', _C.cyan),
          ],
        ),
        const SizedBox(height: 6),
        Text(_opportunity.thesis, style: const TextStyle(color: _C.muted, height: 1.45)),
        const SizedBox(height: 14),
        Row(
          children: [
            Expanded(child: _Metric(label: 'Setup', value: _opportunity.setup)),
            const SizedBox(width: 8),
            Expanded(child: _Metric(label: 'R / R', value: _opportunity.riskReward)),
            const SizedBox(width: 8),
            Expanded(child: _Metric(label: 'Timeframe', value: _opportunity.timeframe)),
          ],
        ),
        const SizedBox(height: 10),
        _Glow(
          child: Column(
            children: [
              _PriceRow(label: 'Entry zone', value: _opportunity.entry, color: _C.cyan),
              const Divider(height: 18),
              _PriceRow(label: 'Stop loss', value: _opportunity.stop, color: _C.red),
              const Divider(height: 18),
              _PriceRow(label: 'Target 1', value: _opportunity.target, color: _C.green),
              const Divider(height: 18),
              _PriceRow(label: 'Target 2', value: _opportunity.target * 1.008, color: _C.green),
            ],
          ),
        ),
        const SizedBox(height: 10),
        const _Card(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('Why AI picked this', style: TextStyle(fontWeight: FontWeight.w900)),
              SizedBox(height: 8),
              Text('Breakout + momentum + volume alignment is strong while estimated downside remains inside the configured PAPER risk envelope.', style: TextStyle(color: _C.muted, height: 1.45)),
            ],
          ),
        ),
        const SizedBox(height: 16),
        _PrimaryButton(label: 'Why this trade?', icon: Icons.help_outline_rounded, onTap: () => _go(_TradeStep.decisionSummary)),
        const SizedBox(height: 10),
        OutlinedButton.icon(
          onPressed: () => _go(_TradeStep.tradeTicket),
          icon: const Icon(Icons.tune_rounded),
          label: const Text('Build PAPER Trade'),
          style: OutlinedButton.styleFrom(minimumSize: const Size.fromHeight(48)),
        ),
      ],
    );
  }

  Widget _decisionSummary() {
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
                  const Expanded(child: Text('System Decision', style: TextStyle(fontSize: 18, fontWeight: FontWeight.w900))),
                  _Pill('${_opportunity.confidence}% CONFIDENCE', _C.green),
                ],
              ),
              const SizedBox(height: 12),
              Text('${_opportunity.rating.toUpperCase()} ${_quote.symbol}', style: const TextStyle(fontSize: 27, fontWeight: FontWeight.w900)),
              const SizedBox(height: 6),
              const Text('Primary reason: momentum breakout + volume confirmation + supportive market regime.', style: TextStyle(color: _C.muted, height: 1.45)),
              const SizedBox(height: 10),
              const _Pill('AUTO EXECUTION ELIGIBLE', _C.cyan),
            ],
          ),
        ),
        const SizedBox(height: 10),
        Row(
          children: [
            Expanded(child: _Metric(label: 'Entry', value: '₹${_opportunity.entry.toStringAsFixed(0)}', valueColor: _C.cyan)),
            const SizedBox(width: 8),
            Expanded(child: _Metric(label: 'Stop', value: '₹${_opportunity.stop.toStringAsFixed(0)}', valueColor: _C.red)),
            const SizedBox(width: 8),
            Expanded(child: _Metric(label: 'Target', value: '₹${_opportunity.target.toStringAsFixed(0)}', valueColor: _C.green)),
          ],
        ),
        const SizedBox(height: 10),
        _Card(
          child: Column(
            children: [
              _InfoRow(label: 'Expected holding period', value: _opportunity.timeframe),
              const Divider(height: 18),
              _InfoRow(label: 'Risk / reward', value: _opportunity.riskReward),
              const Divider(height: 18),
              const _InfoRow(label: 'Decision source', value: 'Fused AI + strategy'),
            ],
          ),
        ),
        const SizedBox(height: 10),
        const _Notice('The AI proposes and explains the trade. The Risk Engine still decides whether capital exposure is allowed.'),
        const SizedBox(height: 16),
        _PrimaryButton(label: 'View Signal Breakdown', icon: Icons.account_tree_outlined, onTap: () => _go(_TradeStep.signalBreakdown)),
      ],
    );
  }

  Widget _signalBreakdown() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const _Section('Weighted decision evidence'),
        const SizedBox(height: 8),
        const _Card(
          child: Column(
            children: [
              _InfoRow(label: 'Trend structure', value: '+18'),
              Divider(height: 18),
              _InfoRow(label: 'Momentum strength', value: '+15'),
              Divider(height: 18),
              _InfoRow(label: 'Volume breakout', value: '+13'),
              Divider(height: 18),
              _InfoRow(label: 'RSI quality', value: '+7'),
              Divider(height: 18),
              _InfoRow(label: 'Market regime', value: '+9'),
              Divider(height: 18),
              _InfoRow(label: 'Fundamental context', value: '+6'),
              Divider(height: 18),
              _InfoRow(label: 'Gemini event evidence', value: '+10'),
              Divider(height: 18),
              _InfoRow(label: 'Volatility penalty', value: '-6'),
              Divider(height: 18),
              _InfoRow(label: 'Model prior', value: '+10'),
            ],
          ),
        ),
        const SizedBox(height: 10),
        _Glow(
          child: Row(
            children: [
              const Expanded(child: Text('Final fused score', style: TextStyle(fontWeight: FontWeight.w900))),
              Text('${_opportunity.confidence} / 100', style: const TextStyle(color: _C.green, fontSize: 22, fontWeight: FontWeight.w900)),
            ],
          ),
        ),
        const SizedBox(height: 10),
        const _Notice('Mock contributions are normalized for UI review. Production weights must come from versioned models and strategy configuration.'),
        const SizedBox(height: 16),
        _PrimaryButton(label: 'View AI / Model Evidence', icon: Icons.memory_rounded, onTap: () => _go(_TradeStep.modelEvidence)),
      ],
    );
  }

  Widget _modelEvidence() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const _Section('Model ensemble'),
        const SizedBox(height: 8),
        const _Card(
          child: Column(
            children: [
              _InfoRow(label: 'Deterministic strategy', value: '76 / 100'),
              Divider(height: 18),
              _InfoRow(label: 'Quantitative model', value: '78 / 100'),
              Divider(height: 18),
              _InfoRow(label: 'PyTorch probability', value: '81% bullish'),
              Divider(height: 18),
              _InfoRow(label: 'Gemini sentiment', value: '+0.72'),
              Divider(height: 18),
              _InfoRow(label: 'Gemini materiality', value: 'High'),
            ],
          ),
        ),
        const SizedBox(height: 10),
        const _Card(
          child: Column(
            children: [
              _InfoRow(label: 'Catalyst', value: 'Sector breadth + breakout volume'),
              Divider(height: 18),
              _InfoRow(label: 'Prediction horizon', value: '30–90 min'),
              Divider(height: 18),
              _InfoRow(label: 'Model version', value: 'fusion-review-v0.9'),
              Divider(height: 18),
              _InfoRow(label: 'Feature snapshot', value: 'review-fixture-1042'),
              Divider(height: 18),
              _InfoRow(label: 'Data freshness', value: '18 sec'),
            ],
          ),
        ),
        const SizedBox(height: 10),
        const _Notice('Technical IDs are shown only as review evidence. Raw prompts, tensors and internal JSON stay out of the customer-facing experience.'),
        const SizedBox(height: 16),
        _PrimaryButton(label: 'Build PAPER Trade', icon: Icons.tune_rounded, onTap: () => _go(_TradeStep.tradeTicket)),
      ],
    );
  }

  Widget _tradeTicket() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _Card(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text('Side', style: TextStyle(color: _C.muted, fontSize: 12)),
              const SizedBox(height: 8),
              Row(
                children: ['BUY', 'SELL'].map((value) {
                  final selected = _side == value;
                  return Expanded(
                    child: Padding(
                      padding: EdgeInsets.only(right: value == 'BUY' ? 6 : 0, left: value == 'SELL' ? 6 : 0),
                      child: ChoiceChip(
                        label: SizedBox(width: double.infinity, child: Text(value, textAlign: TextAlign.center)),
                        selected: selected,
                        onSelected: (_) => setState(() => _side = value),
                      ),
                    ),
                  );
                }).toList(),
              ),
              const SizedBox(height: 14),
              const Text('Quantity', style: TextStyle(color: _C.muted, fontSize: 12)),
              const SizedBox(height: 8),
              Row(
                children: [
                  IconButton.filledTonal(onPressed: _quantity > 1 ? () => setState(() => _quantity--) : null, icon: const Icon(Icons.remove_rounded)),
                  Expanded(child: Text('$_quantity shares', textAlign: TextAlign.center, style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w900))),
                  IconButton.filledTonal(onPressed: () => setState(() => _quantity++), icon: const Icon(Icons.add_rounded)),
                ],
              ),
              const SizedBox(height: 14),
              const Text('Order type', style: TextStyle(color: _C.muted, fontSize: 12)),
              const SizedBox(height: 8),
              Wrap(
                spacing: 8,
                children: ['Market', 'Limit'].map((value) => ChoiceChip(label: Text(value), selected: _orderType == value, onSelected: (_) => setState(() => _orderType = value))).toList(),
              ),
              const SizedBox(height: 14),
              _InputLike(label: _orderType == 'Market' ? 'Reference price' : 'Limit price', value: '₹${_entryPrice.toStringAsFixed(2)}'),
            ],
          ),
        ),
        const SizedBox(height: 10),
        _Card(
          child: Column(
            children: [
              SwitchListTile.adaptive(contentPadding: EdgeInsets.zero, title: const Text('Stop loss'), subtitle: Text('₹${_opportunity.stop.toStringAsFixed(2)}', style: const TextStyle(color: _C.muted)), value: _useStop, onChanged: (value) => setState(() => _useStop = value)),
              SwitchListTile.adaptive(contentPadding: EdgeInsets.zero, title: const Text('Take profit'), subtitle: Text('₹${_opportunity.target.toStringAsFixed(2)}', style: const TextStyle(color: _C.muted)), value: _useTarget, onChanged: (value) => setState(() => _useTarget = value)),
            ],
          ),
        ),
        const SizedBox(height: 10),
        _Glow(child: _InfoRow(label: 'Estimated capital', value: '₹${_estimatedValue.toStringAsFixed(0)}')),
        const SizedBox(height: 10),
        const _Notice('PAPER review mode only. A mandatory risk check runs before execution. No live broker order is sent.'),
        const SizedBox(height: 16),
        _PrimaryButton(label: 'Review Order', icon: Icons.receipt_long_rounded, onTap: () => _go(_TradeStep.orderReview)),
      ],
    );
  }

  Widget _orderReview() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _Glow(
          child: Column(
            children: [
              _InfoRow(label: 'Instrument', value: _quote.symbol),
              const Divider(height: 18),
              _InfoRow(label: 'Side / Type', value: '$_side · $_orderType'),
              const Divider(height: 18),
              _InfoRow(label: 'Quantity', value: '$_quantity'),
              const Divider(height: 18),
              _InfoRow(label: 'Entry', value: '₹${_entryPrice.toStringAsFixed(2)}'),
              const Divider(height: 18),
              _InfoRow(label: 'Capital', value: '₹${_estimatedValue.toStringAsFixed(0)}'),
            ],
          ),
        ),
        const SizedBox(height: 10),
        Row(
          children: [
            Expanded(child: _Metric(label: 'Max loss', value: '₹${_maxLoss.toStringAsFixed(0)}', valueColor: _C.red)),
            const SizedBox(width: 8),
            Expanded(child: _Metric(label: 'Target profit', value: '₹${_targetProfit.toStringAsFixed(0)}', valueColor: _C.green)),
          ],
        ),
        const SizedBox(height: 10),
        _Card(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text('Protection', style: TextStyle(fontWeight: FontWeight.w900)),
              const SizedBox(height: 8),
              _CheckLine(text: _useStop ? 'Stop loss attached' : 'Stop loss disabled', good: _useStop),
              _CheckLine(text: _useTarget ? 'Take profit attached' : 'Take profit disabled', good: _useTarget),
              const _CheckLine(text: 'PAPER execution mode confirmed', good: true),
            ],
          ),
        ),
        const SizedBox(height: 16),
        _PrimaryButton(label: 'Run Risk Check', icon: Icons.shield_outlined, onTap: () => _go(_TradeStep.riskResult)),
      ],
    );
  }

  Widget _riskResult() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _Glow(
          child: Column(
            children: [
              Container(width: 62, height: 62, decoration: const BoxDecoration(color: Color(0x2239F59A), shape: BoxShape.circle), child: const Icon(Icons.shield_rounded, color: _C.green, size: 34)),
              const SizedBox(height: 10),
              const Text('Risk Approved', style: TextStyle(color: _C.green, fontSize: 24, fontWeight: FontWeight.w900)),
              const SizedBox(height: 5),
              const Text('This mock order is inside all configured PAPER limits.', textAlign: TextAlign.center, style: TextStyle(color: _C.muted)),
            ],
          ),
        ),
        const SizedBox(height: 12),
        const _Card(
          child: Column(
            children: [
              _RiskCheck('Per-trade risk ≤ 1.0%', 'PASS'),
              Divider(height: 18),
              _RiskCheck('Daily loss limit available', 'PASS'),
              Divider(height: 18),
              _RiskCheck('Max open positions', 'PASS'),
              Divider(height: 18),
              _RiskCheck('PAPER mode enforced', 'PASS'),
            ],
          ),
        ),
        const SizedBox(height: 10),
        _Notice('Risk Engine remains mandatory in the real product. This review screen never bypasses it or enables LIVE execution.'),
        const SizedBox(height: 16),
        _PrimaryButton(label: 'View Detailed Risk Decision', icon: Icons.fact_check_outlined, onTap: () => _go(_TradeStep.riskDecision)),
      ],
    );
  }

  Widget _riskDecision() {
    final stopDistance = (_entryPrice - _opportunity.stop).abs();
    final availableAfter = (widget.store.virtualCapital - _estimatedValue).clamp(0, double.infinity);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _Glow(
          child: Column(
            children: [
              const Icon(Icons.verified_user_rounded, color: _C.green, size: 42),
              const SizedBox(height: 8),
              const Text('Risk Engine — APPROVED', style: TextStyle(color: _C.green, fontSize: 21, fontWeight: FontWeight.w900)),
              const SizedBox(height: 5),
              Text('Calculated downside ₹${_maxLoss.toStringAsFixed(0)} is inside the mock ₹1,500 per-trade budget.', textAlign: TextAlign.center, style: const TextStyle(color: _C.muted, height: 1.4)),
            ],
          ),
        ),
        const SizedBox(height: 10),
        _Card(
          child: Column(
            children: [
              const _InfoRow(label: 'Max risk budget', value: '₹1,500'),
              const Divider(height: 18),
              _InfoRow(label: 'Calculated risk', value: '₹${_maxLoss.toStringAsFixed(0)}'),
              const Divider(height: 18),
              _InfoRow(label: 'Stop distance', value: '₹${stopDistance.toStringAsFixed(2)}'),
              const Divider(height: 18),
              _InfoRow(label: 'Approved quantity', value: '$_quantity shares'),
              const Divider(height: 18),
              _InfoRow(label: 'Funds after trade', value: '₹${availableAfter.toStringAsFixed(0)}'),
            ],
          ),
        ),
        const SizedBox(height: 10),
        const _Card(
          child: Column(
            children: [
              _RiskCheck('Daily loss limit', 'SAFE'),
              Divider(height: 18),
              _RiskCheck('Portfolio exposure', 'SAFE'),
              Divider(height: 18),
              _RiskCheck('Sector concentration', 'SAFE'),
              Divider(height: 18),
              _RiskCheck('Duplicate-order guard', 'PASS'),
              Divider(height: 18),
              _RiskCheck('Emergency Stop', 'OFF'),
            ],
          ),
        ),
        const SizedBox(height: 10),
        _Notice('Why $_quantity shares? Mock sizing keeps the stop-defined loss below the configured risk budget. Production sizing remains server-authoritative.'),
        const SizedBox(height: 16),
        _PrimaryButton(
          label: 'Place PAPER Order',
          icon: Icons.play_arrow_rounded,
          onTap: () {
            _executeMockTrade();
            _go(_TradeStep.executionSuccess);
          },
        ),
      ],
    );
  }

  Widget _executionSuccess() {
    final orderId = 'PAPER-${_quote.symbol}-1042';
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Center(
          child: Column(
            children: [
              Container(width: 76, height: 76, decoration: const BoxDecoration(gradient: LinearGradient(colors: [_C.cyan, _C.violet]), shape: BoxShape.circle), child: const Icon(Icons.check_rounded, color: Color(0xFF02101A), size: 42)),
              const SizedBox(height: 12),
              const Text('PAPER Order Executed', style: TextStyle(fontSize: 24, fontWeight: FontWeight.w900)),
              const SizedBox(height: 5),
              const Text('Mock state updated across Orders, Portfolio and Notifications.', textAlign: TextAlign.center, style: TextStyle(color: _C.muted)),
            ],
          ),
        ),
        const SizedBox(height: 18),
        _Card(
          child: Column(
            children: [
              _InfoRow(label: 'Order ID', value: orderId),
              const Divider(height: 18),
              _InfoRow(label: 'Order', value: '$_side $_quantity ${_quote.symbol}'),
              const Divider(height: 18),
              _InfoRow(label: 'Executed at', value: '₹${_entryPrice.toStringAsFixed(2)}'),
              const Divider(height: 18),
              const _InfoRow(label: 'Mode', value: 'PAPER'),
            ],
          ),
        ),
        const SizedBox(height: 16),
        _PrimaryButton(label: 'View Decision Timeline', icon: Icons.timeline_rounded, onTap: () => _go(_TradeStep.decisionTimeline)),
        const SizedBox(height: 10),
        OutlinedButton.icon(onPressed: () => _go(_TradeStep.positionDetail), icon: const Icon(Icons.account_balance_wallet_rounded), label: const Text('View Position'), style: OutlinedButton.styleFrom(minimumSize: const Size.fromHeight(48))),
      ],
    );
  }

  Widget _decisionTimeline() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const _Section('Order provenance'),
        const SizedBox(height: 8),
        const _Card(
          child: Column(
            children: [
              _InfoRow(label: '10:31:02', value: 'Market scan detected setup'),
              Divider(height: 18),
              _InfoRow(label: '10:31:03', value: 'Strategy score 76'),
              Divider(height: 18),
              _InfoRow(label: '10:31:04', value: 'PyTorch probability 81%'),
              Divider(height: 18),
              _InfoRow(label: '10:31:05', value: 'Gemini evidence +0.72'),
              Divider(height: 18),
              _InfoRow(label: '10:31:05', value: 'Fusion score 82'),
              Divider(height: 18),
              _InfoRow(label: '10:31:06', value: 'Risk Engine APPROVED'),
              Divider(height: 18),
              _InfoRow(label: '10:31:07', value: 'Auto execution initiated'),
              Divider(height: 18),
              _InfoRow(label: '10:31:09', value: 'PAPER fill confirmed'),
            ],
          ),
        ),
        const SizedBox(height: 10),
        const _Notice('Every production automated order should retain equivalent provenance so the user can reconstruct what the system knew and why it acted.'),
        const SizedBox(height: 16),
        _PrimaryButton(label: 'View Position', icon: Icons.account_balance_wallet_rounded, onTap: () => _go(_TradeStep.positionDetail)),
      ],
    );
  }

  Widget _positionDetail() {
    final pnl = _unrealizedPnl;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            _SymbolBadge(_quote.symbol),
            const SizedBox(width: 10),
            Expanded(child: Text('${_quote.symbol} · $_side', style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w900))),
            const _Pill('PAPER', _C.magenta),
          ],
        ),
        const SizedBox(height: 14),
        _Glow(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text('Unrealized P&L', style: TextStyle(color: _C.muted)),
              const SizedBox(height: 5),
              Text('${pnl >= 0 ? '+' : '-'}₹${pnl.abs().toStringAsFixed(0)}', style: TextStyle(color: pnl >= 0 ? _C.green : _C.red, fontSize: 28, fontWeight: FontWeight.w900)),
              const SizedBox(height: 12),
              _InfoRow(label: 'Entry', value: '₹${_entryPrice.toStringAsFixed(2)}'),
              const Divider(height: 18),
              _InfoRow(label: 'Current', value: '₹${_quote.price.toStringAsFixed(2)}'),
              const Divider(height: 18),
              _InfoRow(label: 'Quantity', value: '$_quantity'),
            ],
          ),
        ),
        const SizedBox(height: 10),
        Row(
          children: [
            Expanded(child: _Metric(label: 'Stop', value: '₹${_opportunity.stop.toStringAsFixed(0)}', valueColor: _C.red)),
            const SizedBox(width: 8),
            Expanded(child: _Metric(label: 'Target', value: '₹${_opportunity.target.toStringAsFixed(0)}', valueColor: _C.green)),
          ],
        ),
        const SizedBox(height: 10),
        const _Notice('Modify-stop and target controls will bind to the execution backend later. This APK keeps the values locally for visual review.'),
        const SizedBox(height: 16),
        _PrimaryButton(label: 'Monitor with AI', icon: Icons.monitor_heart_outlined, onTap: () => _go(_TradeStep.tradeMonitor)),
        const SizedBox(height: 10),
        OutlinedButton.icon(onPressed: () => _go(_TradeStep.exitConfirm), icon: const Icon(Icons.exit_to_app_rounded), label: const Text('Exit Position'), style: OutlinedButton.styleFrom(minimumSize: const Size.fromHeight(48))),
      ],
    );
  }

  Widget _tradeMonitor() {
    final items = <(String, String)>[
      ('AI opportunity found', '09:58'),
      ('Risk approved', '10:01'),
      ('PAPER entry executed', '10:02'),
      ('Momentum remains positive', '10:18'),
      ('Target zone approaching', '10:34'),
    ];
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _Glow(
          child: Row(
            children: [
              const Icon(Icons.auto_awesome_rounded, color: _C.cyan),
              const SizedBox(width: 10),
              const Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [Text('AI Monitoring Active', style: TextStyle(fontWeight: FontWeight.w900)), SizedBox(height: 3), Text('Price action is still consistent with the setup.', style: TextStyle(color: _C.muted, fontSize: 12))])),
              _Pill(_monitorStage >= 4 ? 'TARGET NEAR' : 'ON TRACK', _monitorStage >= 4 ? _C.green : _C.cyan),
            ],
          ),
        ),
        const SizedBox(height: 12),
        _Card(
          child: Column(
            children: List.generate(items.length, (index) {
              final active = index <= _monitorStage;
              final item = items[index];
              return Padding(
                padding: const EdgeInsets.symmetric(vertical: 8),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Container(width: 12, height: 12, margin: const EdgeInsets.only(top: 3), decoration: BoxDecoration(color: active ? _C.green : const Color(0xFF24334B), shape: BoxShape.circle)),
                    const SizedBox(width: 10),
                    Expanded(child: Text(item.$1, style: TextStyle(color: active ? Colors.white : _C.muted, fontWeight: active ? FontWeight.w800 : FontWeight.w500))),
                    Text(item.$2, style: const TextStyle(color: _C.muted, fontSize: 11)),
                  ],
                ),
              );
            }),
          ),
        ),
        const SizedBox(height: 10),
        _Card(
          child: Column(
            children: [
              const _InfoRow(label: 'AI confidence', value: '84%'),
              const Divider(height: 18),
              _InfoRow(label: 'Distance to target', value: '${((_opportunity.target - _quote.price) / _quote.price * 100).abs().toStringAsFixed(2)}%'),
              const Divider(height: 18),
              const _InfoRow(label: 'Risk status', value: 'Protected'),
            ],
          ),
        ),
        const SizedBox(height: 16),
        if (_monitorStage < 4)
          _PrimaryButton(label: 'Simulate Target Approaching', icon: Icons.fast_forward_rounded, onTap: () => setState(() => _monitorStage = 4))
        else
          _PrimaryButton(label: 'Exit Position', icon: Icons.exit_to_app_rounded, onTap: () => _go(_TradeStep.exitConfirm)),
      ],
    );
  }

  Widget _exitConfirm() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _Glow(
          child: Column(
            children: [
              _InfoRow(label: 'Position', value: '${_quote.symbol} · $_quantity shares'),
              const Divider(height: 18),
              _InfoRow(label: 'Entry', value: '₹${_entryPrice.toStringAsFixed(2)}'),
              const Divider(height: 18),
              _InfoRow(label: 'Mock exit price', value: '₹${_exitPrice.toStringAsFixed(2)}'),
              const Divider(height: 18),
              _InfoRow(label: 'Estimated P&L', value: '${_realizedPnl >= 0 ? '+' : '-'}₹${_realizedPnl.abs().toStringAsFixed(0)}'),
            ],
          ),
        ),
        const SizedBox(height: 12),
        DropdownButtonFormField<String>(
          initialValue: _exitReason,
          decoration: _inputDecoration('Exit reason'),
          dropdownColor: const Color(0xFF0B1930),
          items: const ['AI target reached', 'Manual exit', 'Protect profit', 'Setup invalidated'].map((value) => DropdownMenuItem(value: value, child: Text(value))).toList(),
          onChanged: (value) {
            if (value != null) setState(() => _exitReason = value);
          },
        ),
        const SizedBox(height: 10),
        const _Notice('This closes only the local PAPER review position. No broker API or LIVE order is invoked.'),
        const SizedBox(height: 16),
        _PrimaryButton(
          label: 'Confirm PAPER Exit',
          icon: Icons.check_circle_outline_rounded,
          onTap: () {
            _closeMockTrade();
            _go(_TradeStep.tradeResult);
          },
        ),
      ],
    );
  }

  Widget _tradeResult() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Center(
          child: Column(
            children: [
              const Icon(Icons.emoji_events_outlined, size: 58, color: _C.green),
              const SizedBox(height: 8),
              Text('${_realizedPnl >= 0 ? '+' : '-'}₹${_realizedPnl.abs().toStringAsFixed(0)}', style: TextStyle(fontSize: 34, fontWeight: FontWeight.w900, color: _realizedPnl >= 0 ? _C.green : _C.red)),
              const Text('Realized PAPER P&L', style: TextStyle(color: _C.muted)),
            ],
          ),
        ),
        const SizedBox(height: 18),
        _Card(
          child: Column(
            children: [
              _InfoRow(label: 'Instrument', value: _quote.symbol),
              const Divider(height: 18),
              _InfoRow(label: 'Entry → Exit', value: '₹${_entryPrice.toStringAsFixed(0)} → ₹${_exitPrice.toStringAsFixed(0)}'),
              const Divider(height: 18),
              _InfoRow(label: 'Exit reason', value: _exitReason),
              const Divider(height: 18),
              const _InfoRow(label: 'Mock duration', value: '34 minutes'),
            ],
          ),
        ),
        const SizedBox(height: 10),
        const _Glow(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(children: [Icon(Icons.psychology_alt_rounded, color: _C.cyan), SizedBox(width: 8), Text('AI learning summary', style: TextStyle(fontWeight: FontWeight.w900))]),
              SizedBox(height: 8),
              Text('The breakout signal, volume confirmation and controlled stop distance aligned with the profitable outcome. In production this becomes governed outcome-learning data, not uncontrolled self-training.', style: TextStyle(color: _C.muted, height: 1.45)),
            ],
          ),
        ),
        const SizedBox(height: 16),
        _PrimaryButton(label: 'What happened?', icon: Icons.manage_search_rounded, onTap: () => _go(_TradeStep.postTradeLearning)),
        const SizedBox(height: 10),
        OutlinedButton(onPressed: widget.onExit, style: OutlinedButton.styleFrom(minimumSize: const Size.fromHeight(48)), child: const Text('Done')),
        const SizedBox(height: 10),
        OutlinedButton(
          onPressed: () {
            setState(() {
              _step = _TradeStep.stockDetail;
              _executed = false;
              _closed = false;
              _monitorStage = 2;
            });
          },
          style: OutlinedButton.styleFrom(minimumSize: const Size.fromHeight(48)),
          child: const Text('Review Journey Again'),
        ),
      ],
    );
  }

  Widget _postTradeLearning() {
    final favorableMove = (_opportunity.target - _entryPrice).abs() * _quantity;
    final adverseMove = (_entryPrice - _opportunity.stop).abs() * _quantity * .34;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _Glow(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Row(children: [Icon(Icons.analytics_outlined, color: _C.cyan), SizedBox(width: 8), Expanded(child: Text('Prediction vs outcome', maxLines: 1, overflow: TextOverflow.ellipsis, style: TextStyle(fontWeight: FontWeight.w900)))]),
              const SizedBox(height: 10),
              const _CheckLine(text: 'Trend prediction was correct', good: true),
              const _CheckLine(text: 'Volume confirmation remained valid', good: true),
              const _CheckLine(text: 'Entry timing was inside the planned zone', good: true),
              _CheckLine(text: _realizedPnl >= 0 ? 'Outcome supported the bullish thesis' : 'Outcome contradicted the bullish thesis', good: _realizedPnl >= 0),
            ],
          ),
        ),
        const SizedBox(height: 10),
        _Card(
          child: Column(
            children: [
              _InfoRow(label: 'Original confidence', value: '${_opportunity.confidence}%'),
              const Divider(height: 18),
              _InfoRow(label: 'Realized P&L', value: '${_realizedPnl >= 0 ? '+' : '-'}₹${_realizedPnl.abs().toStringAsFixed(0)}'),
              const Divider(height: 18),
              _InfoRow(label: 'Max favorable excursion', value: '₹${favorableMove.toStringAsFixed(0)}'),
              const Divider(height: 18),
              _InfoRow(label: 'Max adverse excursion', value: '₹${adverseMove.toStringAsFixed(0)}'),
              const Divider(height: 18),
              const _InfoRow(label: 'Mock slippage', value: '₹3.40'),
              const Divider(height: 18),
              _InfoRow(label: 'Exit reason', value: _exitReason),
            ],
          ),
        ),
        const SizedBox(height: 10),
        const _Card(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('Governed learning', style: TextStyle(fontWeight: FontWeight.w900)),
              SizedBox(height: 8),
              Text('Prediction snapshot, model versions, Gemini evidence, risk decision, execution details and outcome metrics are added to the mock evaluation dataset.', style: TextStyle(color: _C.muted, height: 1.45)),
              SizedBox(height: 8),
              Text('This does not trigger automatic retraining or model promotion from one trade.', style: TextStyle(color: _C.green, fontWeight: FontWeight.w800, height: 1.4)),
            ],
          ),
        ),
        const SizedBox(height: 10),
        const _Notice('Production model changes require batch evaluation, walk-forward evidence, calibration/risk gates and controlled promotion.'),
        const SizedBox(height: 16),
        _PrimaryButton(label: 'Done', icon: Icons.home_outlined, onTap: widget.onExit),
      ],
    );
  }
}

class _TradePage extends StatelessWidget {
  const _TradePage({required this.title, required this.subtitle, required this.onBack, required this.child});

  final String title;
  final String subtitle;
  final VoidCallback onBack;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    return SingleChildScrollView(
      padding: const EdgeInsets.fromLTRB(16, 12, 16, 28),
      child: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 620),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  IconButton(onPressed: onBack, padding: EdgeInsets.zero, alignment: Alignment.centerLeft, icon: const Icon(Icons.arrow_back_rounded)),
                  const SizedBox(width: 4),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(title, style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w900)),
                        Text(subtitle, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(color: _C.muted, fontSize: 11)),
                      ],
                    ),
                  ),
                  const _Pill('MOCK DATA', _C.violet),
                ],
              ),
              const SizedBox(height: 18),
              child,
            ],
          ),
        ),
      ),
    );
  }
}

class _C {
  static const cyan = Color(0xFF1DE7FF);
  static const violet = Color(0xFF8B5CFF);
  static const magenta = Color(0xFFFF4FD8);
  static const green = Color(0xFF39F59A);
  static const red = Color(0xFFFF6B7D);
  static const muted = Color(0xFF8FA4C2);
}

class _Card extends StatelessWidget {
  const _Card({required this.child});
  final Widget child;
  @override
  Widget build(BuildContext context) => Material(
        color: const Color(0xFF08162A),
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(16),
          side: const BorderSide(color: Color(0xFF183050)),
        ),
        clipBehavior: Clip.antiAlias,
        child: Padding(
          padding: const EdgeInsets.all(15),
          child: child,
        ),
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
          boxShadow: const [BoxShadow(color: Color(0x331DE7FF), blurRadius: 22, offset: Offset(0, 8))],
        ),
        child: child,
      );
}

class _PrimaryButton extends StatelessWidget {
  const _PrimaryButton({required this.label, required this.onTap, required this.icon});
  final String label;
  final VoidCallback onTap;
  final IconData icon;
  @override
  Widget build(BuildContext context) => DecoratedBox(
        decoration: BoxDecoration(borderRadius: BorderRadius.circular(13), gradient: const LinearGradient(colors: [_C.cyan, _C.violet, _C.magenta])),
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

class _Metric extends StatelessWidget {
  const _Metric({required this.label, required this.value, this.valueColor});
  final String label;
  final String value;
  final Color? valueColor;
  @override
  Widget build(BuildContext context) => _Card(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(label, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(color: _C.muted, fontSize: 10)),
            const SizedBox(height: 5),
            Text(value, maxLines: 1, overflow: TextOverflow.ellipsis, style: TextStyle(color: valueColor, fontSize: 13, fontWeight: FontWeight.w900)),
          ],
        ),
      );
}

class _Pill extends StatelessWidget {
  const _Pill(this.text, this.color);
  final String text;
  final Color color;
  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 5),
        decoration: BoxDecoration(color: color.withValues(alpha: .13), borderRadius: BorderRadius.circular(99), border: Border.all(color: color.withValues(alpha: .45))),
        child: Text(text, style: TextStyle(color: color, fontSize: 9, fontWeight: FontWeight.w900, letterSpacing: .2)),
      );
}

class _Section extends StatelessWidget {
  const _Section(this.text);
  final String text;
  @override
  Widget build(BuildContext context) => Text(text, style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w900));
}

class _InfoRow extends StatelessWidget {
  const _InfoRow({required this.label, required this.value});
  final String label;
  final String value;
  @override
  Widget build(BuildContext context) => Row(
        children: [
          Expanded(child: Text(label, style: const TextStyle(color: _C.muted, fontSize: 12))),
          const SizedBox(width: 10),
          Flexible(child: Text(value, textAlign: TextAlign.right, style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 12))),
        ],
      );
}

class _PriceRow extends StatelessWidget {
  const _PriceRow({required this.label, required this.value, required this.color});
  final String label;
  final double value;
  final Color color;
  @override
  Widget build(BuildContext context) => Row(children: [Expanded(child: Text(label, style: const TextStyle(color: _C.muted))), Text('₹${value.toStringAsFixed(2)}', style: TextStyle(color: color, fontWeight: FontWeight.w900))]);
}

class _SignalRow extends StatelessWidget {
  const _SignalRow({required this.icon, required this.title, required this.value, required this.score});
  final IconData icon;
  final String title;
  final String value;
  final String score;
  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.only(bottom: 8),
        child: _Card(
          child: Row(
            children: [
              Icon(icon, color: _C.cyan, size: 20),
              const SizedBox(width: 10),
              Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [Text(title, style: const TextStyle(fontWeight: FontWeight.w800)), const SizedBox(height: 2), Text(value, style: const TextStyle(color: _C.muted, fontSize: 11))])),
              Text(score, style: const TextStyle(color: _C.green, fontWeight: FontWeight.w900)),
            ],
          ),
        ),
      );
}

class _CheckLine extends StatelessWidget {
  const _CheckLine({required this.text, required this.good});
  final String text;
  final bool good;
  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 4),
        child: Row(children: [Icon(good ? Icons.check_circle_rounded : Icons.warning_amber_rounded, color: good ? _C.green : _C.red, size: 18), const SizedBox(width: 8), Expanded(child: Text(text, style: const TextStyle(fontSize: 12)))]),
      );
}

class _RiskCheck extends StatelessWidget {
  const _RiskCheck(this.label, this.value);
  final String label;
  final String value;
  @override
  Widget build(BuildContext context) => Row(children: [const Icon(Icons.check_circle_rounded, color: _C.green, size: 18), const SizedBox(width: 8), Expanded(child: Text(label, style: const TextStyle(fontSize: 12))), Text(value, style: const TextStyle(color: _C.green, fontWeight: FontWeight.w900, fontSize: 11))]);
}

class _Notice extends StatelessWidget {
  const _Notice(this.text);
  final String text;
  @override
  Widget build(BuildContext context) => Container(
        width: double.infinity,
        padding: const EdgeInsets.all(13),
        decoration: BoxDecoration(color: const Color(0xFF0B1830), borderRadius: BorderRadius.circular(13), border: Border.all(color: const Color(0xFF233653))),
        child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [const Icon(Icons.info_outline_rounded, color: _C.cyan, size: 18), const SizedBox(width: 8), Expanded(child: Text(text, style: const TextStyle(color: _C.muted, fontSize: 11, height: 1.4)))]),
      );
}

class _InputLike extends StatelessWidget {
  const _InputLike({required this.label, required this.value});
  final String label;
  final String value;
  @override
  Widget build(BuildContext context) => InputDecorator(decoration: _inputDecoration(label), child: Text(value, style: const TextStyle(fontWeight: FontWeight.w900)));
}

class _SymbolBadge extends StatelessWidget {
  const _SymbolBadge(this.symbol);
  final String symbol;
  @override
  Widget build(BuildContext context) => CircleAvatar(radius: 22, backgroundColor: const Color(0xFF18294A), child: Text(symbol.isEmpty ? '?' : symbol[0], style: const TextStyle(color: _C.cyan, fontWeight: FontWeight.w900, fontSize: 18)));
}

class _MiniChart extends StatelessWidget {
  const _MiniChart({required this.values, required this.positive});
  final List<double> values;
  final bool positive;
  @override
  Widget build(BuildContext context) => CustomPaint(painter: _ChartPainter(values, positive ? _C.green : _C.red));
}

class _ChartPainter extends CustomPainter {
  _ChartPainter(this.values, this.color);
  final List<double> values;
  final Color color;
  @override
  void paint(Canvas canvas, Size size) {
    if (values.length < 2) return;
    final grid = Paint()..color = const Color(0x181DE7FF)..strokeWidth = 1;
    for (var i = 1; i < 4; i++) {
      final y = size.height * i / 4;
      canvas.drawLine(Offset(0, y), Offset(size.width, y), grid);
    }
    final minValue = values.reduce(math.min);
    final maxValue = values.reduce(math.max);
    final span = math.max(1.0, maxValue - minValue);
    final path = Path();
    for (var i = 0; i < values.length; i++) {
      final x = size.width * i / (values.length - 1);
      final y = size.height - ((values[i] - minValue) / span * (size.height * .74)) - size.height * .13;
      if (i == 0) {
        path.moveTo(x, y);
      } else {
        path.lineTo(x, y);
      }
    }
    canvas.drawPath(path, Paint()..style = PaintingStyle.stroke..strokeWidth = 7..color = color.withValues(alpha: .14)..maskFilter = const MaskFilter.blur(BlurStyle.normal, 7));
    canvas.drawPath(path, Paint()..style = PaintingStyle.stroke..strokeWidth = 2.2..strokeCap = StrokeCap.round..strokeJoin = StrokeJoin.round..color = color);
  }

  @override
  bool shouldRepaint(covariant _ChartPainter oldDelegate) => oldDelegate.values != values || oldDelegate.color != color;
}

InputDecoration _inputDecoration(String label) => InputDecoration(
      labelText: label,
      labelStyle: const TextStyle(color: _C.muted),
      filled: true,
      fillColor: const Color(0xFF08162A),
      enabledBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(13), borderSide: const BorderSide(color: Color(0xFF20344F))),
      focusedBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(13), borderSide: const BorderSide(color: _C.cyan)),
    );

String _percent(double value) => '${value >= 0 ? '+' : ''}${value.toStringAsFixed(2)}%';

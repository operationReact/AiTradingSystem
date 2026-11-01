from pathlib import Path

path = Path('lib/src/presentation/review/mock_trade_journey.dart')
text = path.read_text()

old_back = '''  void _back() {
    switch (_step) {
      case _TradeStep.stockDetail:
        widget.onExit();
      case _TradeStep.aiAnalysis:
        _go(_TradeStep.stockDetail);
      case _TradeStep.opportunityDetail:
        _go(_TradeStep.aiAnalysis);
      case _TradeStep.tradeTicket:
        _go(_TradeStep.opportunityDetail);
      case _TradeStep.orderReview:
        _go(_TradeStep.tradeTicket);
      case _TradeStep.riskResult:
        _go(_TradeStep.orderReview);
      case _TradeStep.executionSuccess:
        _go(_TradeStep.riskResult);
      case _TradeStep.positionDetail:
        _go(_TradeStep.executionSuccess);
      case _TradeStep.tradeMonitor:
        _go(_TradeStep.positionDetail);
      case _TradeStep.exitConfirm:
        _go(_TradeStep.tradeMonitor);
      case _TradeStep.tradeResult:
        _go(_TradeStep.positionDetail);
    }
  }
'''

new_back = '''  void _back() {
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
      case _TradeStep.tradeTicket:
        _go(_TradeStep.opportunityDetail);
        return;
      case _TradeStep.orderReview:
        _go(_TradeStep.tradeTicket);
        return;
      case _TradeStep.riskResult:
        _go(_TradeStep.orderReview);
        return;
      case _TradeStep.executionSuccess:
        _go(_TradeStep.riskResult);
        return;
      case _TradeStep.positionDetail:
        _go(_TradeStep.executionSuccess);
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
    }
  }
'''
text = text.replace(old_back, new_back)

old_card = '''class _Card extends StatelessWidget {
  const _Card({required this.child});
  final Widget child;
  @override
  Widget build(BuildContext context) => Container(
        width: double.infinity,
        padding: const EdgeInsets.all(15),
        decoration: BoxDecoration(
          color: const Color(0xFF08162A),
          borderRadius: BorderRadius.circular(16),
          border: Border.all(color: const Color(0xFF183050)),
        ),
        child: child,
      );
}
'''

new_card = '''class _Card extends StatelessWidget {
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
'''
text = text.replace(old_card, new_card)

path.write_text(text)

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/data/paper_trading_repository.dart';
import 'package:trading_client/src/presentation/paper_orders_page.dart';
import 'package:trading_client/src/presentation/paper_portfolio_page.dart';

class _FakePaperRepository implements PaperTradingRepository {
  _FakePaperRepository({required this.portfolio, required this.orders, this.orderResult});

  final PaperPortfolio portfolio;
  PaperOrderPage orders;
  final PaperOrderResult? orderResult;
  CreatePaperOrderCommand? submitted;
  String? submittedIdempotencyKey;

  @override
  Future<PaperPortfolio> fetchPortfolio() async => portfolio;

  @override
  Future<PaperOrderPage> fetchOrders({int page = 0, int size = 20, String? status, String? symbol}) async => orders;

  @override
  Future<PaperOrderResult> createOrder(CreatePaperOrderCommand command, {required String idempotencyKey}) async {
    submitted = command;
    submittedIdempotencyKey = idempotencyKey;
    return orderResult!;
  }

  @override
  Future<PaperOrderResult> cancelOrder(String orderId) async => orderResult!;
}

PaperPortfolio _portfolio() => PaperPortfolio(
      mode: 'PAPER', liveTradingEnabled: false, newOrdersHalted: false, startingCapital: 100000, cash: 75000, invested: 25000,
      marketValue: 26000, equity: 101000, realizedPnl: 500, unrealizedPnl: 1000, realizedPnlToday: 100, exposurePercent: 25.7,
      openPositions: 1, cashReconciled: true, markPriceSource: 'PERSISTED_MARK',
      positions: [PaperPosition(instrumentId: 42, exchange: 'NSE', symbol: 'INFY-EQ', productType: 'DELIVERY', quantity: 10, averageCost: 2500, costBasis: 25000, markPrice: 2600, marketValue: 26000, unrealizedPnl: 1000, stopLoss: 2400, target: 2800, openedAt: DateTime.parse('2026-10-30T10:00:00Z'))],
      asOf: DateTime.parse('2026-10-31T00:00:00Z'),
    );

PaperOrder _order({String status = 'RISK_APPROVED'}) => PaperOrder(
      id: '00000000-0000-0000-0000-000000000002', symbol: 'INFY-EQ', side: 'BUY', quantity: 2, filledQuantity: 0, orderType: 'MARKET',
      productType: 'DELIVERY', averageFillPrice: null, entryPrice: 2500, stopLoss: 2400, target: 2800, status: status, rejectionReason: null,
      createdAt: DateTime.parse('2026-10-31T00:00:00Z'), updatedAt: DateTime.parse('2026-10-31T00:00:00Z'), correlationId: 'corr-2',
    );

Future<void> _scrollPageTo(WidgetTester tester, String listKey, Finder target, {double delta = 200}) => tester.scrollUntilVisible(
      target,
      delta,
      scrollable: find.descendant(of: find.byKey(ValueKey(listKey)), matching: find.byType(Scrollable)).first,
    );

Future<void> _bringIntoView(WidgetTester tester, Finder target) async {
  await tester.ensureVisible(target);
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('PAPER portfolio presents customer account metrics and positions', (tester) async {
    final repository = _FakePaperRepository(portfolio: _portfolio(), orders: const PaperOrderPage(items: [], page: 0, totalPages: 0, totalItems: 0));

    await tester.pumpWidget(MaterialApp(home: PaperPortfolioPage(repository: repository)));
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('paper-portfolio-summary')), findsOneWidget);
    expect(find.text('Account overview'), findsOneWidget);
    expect(find.text('PAPER'), findsWidgets);
    expect(find.text('Available cash'), findsOneWidget);
    expect(find.text('Today P&L'), findsOneWidget);
    await _scrollPageTo(tester, 'paper-portfolio-list', find.byKey(const ValueKey('paper-position-42')));
    expect(find.byKey(const ValueKey('paper-position-42')), findsOneWidget);
    expect(find.textContaining('INFY-EQ'), findsOneWidget);
  });

  testWidgets('PAPER order form refuses incomplete BUY before repository submission', (tester) async {
    final repository = _FakePaperRepository(portfolio: _portfolio(), orders: const PaperOrderPage(items: [], page: 0, totalPages: 0, totalItems: 0));

    await tester.pumpWidget(MaterialApp(home: PaperOrdersPage(repository: repository)));
    await tester.pumpAndSettle();

    await tester.enterText(find.byKey(const ValueKey('paper-order-symbol')), 'INFY-EQ');
    await tester.enterText(find.byKey(const ValueKey('paper-order-reference-price')), '2500');
    await tester.enterText(find.byKey(const ValueKey('paper-order-signal-time')), '2026-10-31T00:00:00Z');
    final submit = find.byKey(const ValueKey('paper-order-submit'));
    await _bringIntoView(tester, submit);
    await tester.tap(submit);
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('paper-order-validation-error')), findsOneWidget);
    expect(find.text('A PAPER buy needs both a stop loss and target.'), findsOneWidget);
    expect(repository.submitted, isNull);
  });

  testWidgets('PAPER order submits simulated intent and never exposes a LIVE action', (tester) async {
    final result = PaperOrderResult(order: _order(), replayed: false, reasonCodes: const ['WITHIN_POLICY']);
    final repository = _FakePaperRepository(
      portfolio: _portfolio(),
      orders: PaperOrderPage(items: [_order()], page: 0, totalPages: 1, totalItems: 1),
      orderResult: result,
    );

    await tester.pumpWidget(MaterialApp(home: PaperOrdersPage(repository: repository)));
    await tester.pumpAndSettle();

    await tester.enterText(find.byKey(const ValueKey('paper-order-symbol')), 'INFY-EQ');
    await tester.enterText(find.byKey(const ValueKey('paper-order-quantity')), '2');
    await tester.enterText(find.byKey(const ValueKey('paper-order-reference-price')), '2500');
    await tester.enterText(find.byKey(const ValueKey('paper-order-stop-loss')), '2400');
    await tester.enterText(find.byKey(const ValueKey('paper-order-target')), '2800');
    await tester.enterText(find.byKey(const ValueKey('paper-order-signal-time')), '2026-10-31T00:00:00Z');
    final submit = find.byKey(const ValueKey('paper-order-submit'));
    await _bringIntoView(tester, submit);
    await tester.tap(submit);
    await tester.pumpAndSettle();

    expect(repository.submitted?.symbol, 'INFY-EQ');
    expect(repository.submitted?.side, 'BUY');
    expect(repository.submittedIdempotencyKey, startsWith('flutter-paper-'));
    expect(find.byKey(const ValueKey('paper-order-result')), findsOneWidget);
    expect(find.text('PAPER order RISK_APPROVED'), findsOneWidget);
    // Guard against an actual LIVE execution control. Do not substring-match
    // "LIVE" because legitimate words such as DELIVERY contain those letters.
    expect(find.widgetWithText(FilledButton, 'Place LIVE order'), findsNothing);
    expect(find.widgetWithText(ElevatedButton, 'Place LIVE order'), findsNothing);
    expect(find.widgetWithText(TextButton, 'Place LIVE order'), findsNothing);
    expect(find.widgetWithText(OutlinedButton, 'Place LIVE order'), findsNothing);
  });
}

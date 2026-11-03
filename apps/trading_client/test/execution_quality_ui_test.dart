import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/data/paper_trading_repository.dart';
import 'package:trading_client/src/presentation/execution_quality_panel.dart';
import 'package:trading_client/src/presentation/paper_orders_page.dart';

PaperOrder _filledOrder() => PaperOrder(
      id: '00000000-0000-0000-0000-000000000099',
      symbol: 'RELIANCE',
      side: 'BUY',
      quantity: 10,
      filledQuantity: 10,
      orderType: 'MARKET',
      productType: 'INTRADAY',
      averageFillPrice: 2501.25,
      entryPrice: 2500,
      stopLoss: 2475,
      target: 2550,
      status: 'FILLED',
      rejectionReason: null,
      createdAt: DateTime.parse('2026-11-03T04:30:00Z'),
      updatedAt: DateTime.parse('2026-11-03T04:31:00Z'),
      correlationId: 'corr-quality-99',
    );

PaperFill _fill() => PaperFill(
      id: '00000000-0000-0000-0000-000000000199',
      fillPrice: 2501.25,
      grossAmount: 25012.50,
      feeAmount: 18.75,
      slippageAmount: 12.50,
      netAmount: 25043.75,
      costBasis: 25043.75,
      realizedPnl: 0,
      filledAt: DateTime.parse('2026-11-03T04:31:00Z'),
    );

PaperOrderResult _detail() => PaperOrderResult(
      order: _filledOrder(),
      replayed: false,
      reasonCodes: const [],
      fill: _fill(),
    );

class _QualityRepository implements PaperTradingRepository, PaperOrderDetailRepository {
  _QualityRepository({required this.detail});

  final PaperOrderResult detail;
  int detailRequests = 0;

  @override
  Future<PaperPortfolio> fetchPortfolio() async => PaperPortfolio(
        mode: 'PAPER',
        liveTradingEnabled: false,
        newOrdersHalted: false,
        startingCapital: 100000,
        cash: 100000,
        invested: 0,
        marketValue: 0,
        equity: 100000,
        realizedPnl: 0,
        unrealizedPnl: 0,
        realizedPnlToday: 0,
        exposurePercent: 0,
        openPositions: 0,
        cashReconciled: true,
        markPriceSource: 'LAST_PAPER_FILL',
        positions: const [],
        asOf: DateTime.parse('2026-11-03T04:31:00Z'),
      );

  @override
  Future<PaperOrderPage> fetchOrders({
    int page = 0,
    int size = 20,
    String? status,
    String? symbol,
  }) async => PaperOrderPage(
        items: [_filledOrder()],
        page: 0,
        totalPages: 1,
        totalItems: 1,
      );

  @override
  Future<PaperOrderResult> fetchOrderDetail(String orderId) async {
    detailRequests += 1;
    return detail;
  }

  @override
  Future<PaperOrderResult> createOrder(
    CreatePaperOrderCommand command, {
    required String idempotencyKey,
  }) async => detail;

  @override
  Future<PaperOrderResult> cancelOrder(String orderId) async => detail;
}

void main() {
  test('PAPER order detail parser preserves persisted fill amounts', () {
    final parsed = PaperOrderResult.fromJson({
      'order': {
        'id': 'order-1',
        'symbol': 'RELIANCE',
        'side': 'BUY',
        'quantity': 10,
        'filledQuantity': 10,
        'orderType': 'MARKET',
        'productType': 'INTRADAY',
        'averageFillPrice': 2501.25,
        'entryPrice': 2500,
        'status': 'FILLED',
      },
      'fill': {
        'id': 'fill-1',
        'fillPrice': 2501.25,
        'grossAmount': 25012.50,
        'feeAmount': 18.75,
        'slippageAmount': 12.50,
        'netAmount': 25043.75,
        'costBasis': 25043.75,
        'realizedPnl': 0,
        'filledAt': '2026-11-03T04:31:00Z',
      },
      'replayed': false,
    });

    expect(parsed.fill, isNotNull);
    expect(parsed.fill!.feeAmount, 18.75);
    expect(parsed.fill!.slippageAmount, 12.50);
    expect(parsed.fill!.fillPrice, 2501.25);
  });

  testWidgets('execution-quality panel shows realized ledger evidence without inventing expected TCA', (tester) async {
    await tester.pumpWidget(MaterialApp(home: Scaffold(body: ExecutionQualityPanel(detail: _detail()))));
    await tester.pumpAndSettle();

    expect(find.text('Execution quality'), findsOneWidget);
    expect(find.text('FILLED'), findsOneWidget);
    expect(find.text('₹18.75'), findsOneWidget);
    expect(find.text('₹12.50'), findsOneWidget);
    expect(find.textContaining('Expected-vs-realized TCA is not persisted'), findsOneWidget);
    expect(find.textContaining('no expected cost or cost-model version is inferred'), findsOneWidget);
  });

  testWidgets('PAPER orders lazily load execution-quality detail from the read-only capability', (tester) async {
    final repository = _QualityRepository(detail: _detail());
    await tester.pumpWidget(MaterialApp(home: PaperOrdersPage(repository: repository)));
    await tester.pumpAndSettle();

    final toggle = find.byKey(const ValueKey('paper-order-quality-toggle-00000000-0000-0000-0000-000000000099'));
    await tester.scrollUntilVisible(
      toggle,
      250,
      scrollable: find.descendant(
        of: find.byKey(const ValueKey('paper-orders-list')),
        matching: find.byType(Scrollable),
      ).first,
    );
    await tester.tap(toggle);
    await tester.pumpAndSettle();

    expect(repository.detailRequests, 1);
    expect(find.byKey(const ValueKey('execution-quality-00000000-0000-0000-0000-000000000099')), findsOneWidget);
    expect(find.text('₹18.75'), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('paper-order-quality-toggle-00000000-0000-0000-0000-000000000099')));
    await tester.pumpAndSettle();
    expect(repository.detailRequests, 1, reason: 'cached detail should not be fetched again');
  });
}

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/data/paper_trading_repository.dart';
import 'package:trading_client/src/presentation/paper_orders_page.dart';
import 'package:trading_client/src/theme/app_theme.dart';

class _FakePaperRepository implements PaperTradingRepository {
  CreatePaperOrderCommand? submittedCommand;

  @override
  Future<PaperPortfolio> fetchPortfolio() => throw UnimplementedError();

  @override
  Future<PaperOrderPage> fetchOrders({int page = 0, int size = 20, String? status, String? symbol}) async =>
      const PaperOrderPage(items: [], page: 0, totalPages: 0, totalItems: 0);

  @override
  Future<PaperOrderResult> createOrder(CreatePaperOrderCommand command, {required String idempotencyKey}) async {
    submittedCommand = command;
    return PaperOrderResult(
      order: PaperOrder(
        id: '00000000-0000-0000-0000-000000000005',
        symbol: command.symbol,
        side: command.side,
        quantity: command.quantity,
        filledQuantity: command.quantity,
        orderType: 'MARKET',
        productType: command.productType,
        averageFillPrice: command.referencePrice,
        entryPrice: command.referencePrice,
        stopLoss: command.stopLoss,
        target: command.target,
        trailingStopPercent: command.trailingStopPercent,
        status: 'FILLED',
        rejectionReason: null,
        createdAt: DateTime.parse('2026-11-01T04:00:00Z'),
        updatedAt: DateTime.parse('2026-11-01T04:00:00Z'),
        correlationId: 'paper-05-test',
      ),
      replayed: false,
      reasonCodes: const [],
    );
  }

  @override
  Future<PaperOrderResult> cancelOrder(String orderId) => throw UnimplementedError();
}

Future<void> _scrollTo(WidgetTester tester, Finder target) async {
  await tester.scrollUntilVisible(
    target,
    220,
    scrollable: find.descendant(
      of: find.byKey(const ValueKey('paper-orders-list')),
      matching: find.byType(Scrollable),
    ).first,
  );
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('trailing protection requires explicit opt-in and has no preset at phone width', (tester) async {
    tester.view.devicePixelRatio = 1;
    tester.view.physicalSize = const Size(390, 844);
    addTearDown(() {
      tester.view.resetPhysicalSize();
      tester.view.resetDevicePixelRatio();
    });

    final repository = _FakePaperRepository();
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(DesignStyle.neonFuturisticAi, Brightness.dark),
      home: PaperOrdersPage(
        repository: repository,
        trailingProtectionPreviewEnabled: true,
      ),
    ));
    await tester.pumpAndSettle();

    final editor = find.byKey(const ValueKey('paper-trailing-protection-editor'));
    await _scrollTo(tester, editor);
    expect(editor, findsOneWidget);
    expect(find.text('Optional. You choose the trail distance — there is no preset.'), findsOneWidget);
    expect(find.byKey(const ValueKey('paper-trailing-distance-percent')), findsNothing);

    await tester.tap(find.byKey(const ValueKey('paper-trailing-protection-toggle')));
    await tester.pumpAndSettle();

    final distance = find.byKey(const ValueKey('paper-trailing-distance-percent'));
    expect(distance, findsOneWidget);
    final field = tester.widget<TextField>(distance);
    expect(field.controller?.text, isEmpty);
    expect(find.textContaining('No value is selected for you.'), findsOneWidget);
    expect(
      find.textContaining('can move up but never down'),
      findsOneWidget,
    );
    expect(tester.takeException(), isNull);
  });

  testWidgets('enabled trailing protection submits the explicit PAPER percentage', (tester) async {
    final repository = _FakePaperRepository();
    await tester.pumpWidget(MaterialApp(
      home: PaperOrdersPage(
        repository: repository,
        trailingProtectionPreviewEnabled: true,
      ),
    ));
    await tester.pumpAndSettle();

    await tester.enterText(find.byKey(const ValueKey('paper-order-symbol')), 'INFY-EQ');
    await tester.enterText(find.byKey(const ValueKey('paper-order-quantity')), '2');
    await tester.enterText(find.byKey(const ValueKey('paper-order-reference-price')), '2500');
    await tester.enterText(find.byKey(const ValueKey('paper-order-stop-loss')), '2400');
    await tester.enterText(find.byKey(const ValueKey('paper-order-target')), '2800');

    final toggle = find.byKey(const ValueKey('paper-trailing-protection-toggle'));
    await _scrollTo(tester, toggle);
    await tester.tap(toggle);
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const ValueKey('paper-trailing-distance-percent')), '2.5');

    final signalTime = find.byKey(const ValueKey('paper-order-signal-time'));
    await _scrollTo(tester, signalTime);
    await tester.enterText(signalTime, '2026-11-01T04:00:00Z');

    final submit = find.byKey(const ValueKey('paper-order-submit'));
    await _scrollTo(tester, submit);
    await tester.tap(submit);
    await tester.pumpAndSettle();

    expect(repository.submittedCommand, isNotNull);
    expect(repository.submittedCommand!.trailingStopPercent, 2.5);
    expect(find.byKey(const ValueKey('paper-order-result')), findsOneWidget);
    expect(find.text('Trailing protection 2.5%'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('trailing protection is scoped to BUY and clears when side changes', (tester) async {
    final repository = _FakePaperRepository();
    await tester.pumpWidget(MaterialApp(
      home: PaperOrdersPage(
        repository: repository,
        trailingProtectionPreviewEnabled: true,
      ),
    ));
    await tester.pumpAndSettle();

    final editor = find.byKey(const ValueKey('paper-trailing-protection-editor'));
    await _scrollTo(tester, editor);
    await tester.tap(find.byKey(const ValueKey('paper-trailing-protection-toggle')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('paper-trailing-distance-percent')), findsOneWidget);

    final side = find.byKey(const ValueKey('paper-order-side'));
    await _scrollTo(tester, side);
    await tester.tap(side);
    await tester.pumpAndSettle();
    await tester.tap(find.text('SELL').last);
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('paper-trailing-protection-editor')), findsNothing);
    expect(find.byKey(const ValueKey('paper-trailing-distance-percent')), findsNothing);
    expect(tester.takeException(), isNull);
  });
}

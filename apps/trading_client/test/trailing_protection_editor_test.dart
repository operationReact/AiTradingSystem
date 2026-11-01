import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/data/paper_trading_repository.dart';
import 'package:trading_client/src/presentation/paper_orders_page.dart';
import 'package:trading_client/src/theme/app_theme.dart';

class _FakePaperRepository implements PaperTradingRepository {
  bool submitted = false;

  @override
  Future<PaperPortfolio> fetchPortfolio() => throw UnimplementedError();

  @override
  Future<PaperOrderPage> fetchOrders({int page = 0, int size = 20, String? status, String? symbol}) async =>
      const PaperOrderPage(items: [], page: 0, totalPages: 0, totalItems: 0);

  @override
  Future<PaperOrderResult> createOrder(CreatePaperOrderCommand command, {required String idempotencyKey}) {
    submitted = true;
    throw StateError('Trailing preview must never submit an unsupported PAPER command.');
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
    expect(find.text('No value is selected for you.'), findsOneWidget);
    expect(
      find.textContaining('It can move up, never down, and it never loosens your original stop loss.'),
      findsOneWidget,
    );
    expect(tester.takeException(), isNull);
  });

  testWidgets('trailing preview cannot silently submit before backend support exists', (tester) async {
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

    expect(repository.submitted, isFalse);
    expect(find.byKey(const ValueKey('paper-order-validation-error')), findsOneWidget);
    expect(find.text('Trailing protection is not available for order placement yet.'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
}

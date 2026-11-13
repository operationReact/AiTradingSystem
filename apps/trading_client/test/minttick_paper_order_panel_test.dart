import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/trading_rooms/data/customer_market_repository.dart';
import 'package:trading_client/src/features/trading_rooms/data/customer_paper_trading_repository.dart';
import 'package:trading_client/src/features/trading_rooms/data/customer_room_repository.dart';
import 'package:trading_client/src/features/trading_rooms/presentation/minttick_paper_order_panel.dart';
import 'package:trading_client/src/theme/app_theme.dart';

class _FakePaperOrders extends Fake implements HttpCustomerPaperTradingRepository {
  int resumed = 0;
  int paused = 0;
  int submits = 0;

  @override
  Future<CustomerPaperWallet> resume(String roomId) async {
    resumed++;
    return _wallet(halted: false);
  }

  @override
  Future<CustomerPaperWallet> pause(String roomId) async {
    paused++;
    return _wallet(halted: true);
  }

  @override
  Future<List<CustomerPaperOrder>> orders(String roomId) async => const [];

  @override
  Future<List<CustomerPaperPosition>> positions(String roomId) async => const [];

  @override
  Future<CustomerPaperOrder> submit({
    required String roomId,
    required String idempotencyKey,
    required int instrumentId,
    required String side,
    required int quantity,
    required String limitPrice,
  }) async {
    submits++;
    throw StateError('No paper order was authorized');
  }
}

class _FakeMarket extends Fake implements HttpCustomerMarketRepository {}

CustomerPaperWallet _wallet({required bool halted}) => CustomerPaperWallet(
  roomId: '12345678-1234-1234-1234-123456789012',
  roomStatus: 'PAPER_FUNDED',
  initialCashPaise: 2_000_000,
  availableCashPaise: 2_000_000,
  reservedCashPaise: 0,
  realizedPnlPaise: 0,
  halted: halted,
  executionEnabled: !halted,
  updatedAt: DateTime.utc(2026, 11, 12),
);

void main() {
  testWidgets('Paper Room starts paused and cannot place orders accidentally', (tester) async {
    final orders = _FakePaperOrders();
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: Scaffold(body: SingleChildScrollView(child: MintTickManualPaperTradingPanel(
        roomId: _wallet(halted: true).roomId,
        wallet: _wallet(halted: true),
        orders: orders,
        market: _FakeMarket(),
        onWalletChanged: () async {},
      ))),
    ));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('paper-resume-manual')), findsOneWidget);
    expect(find.byKey(const ValueKey('paper-open-confirmation')), findsNothing);
    expect(orders.submits, 0);

    await tester.tap(find.byKey(const ValueKey('paper-resume-manual')));
    // An indeterminate progress bar remains behind this confirmation modal:
    // pumpAndSettle cannot finish until the user closes the dialog.
    await tester.pump(const Duration(milliseconds: 250));
    expect(find.textContaining('virtual funds'), findsWidgets);
    await tester.tap(find.byKey(const ValueKey('paper-resume-confirm')));
    await tester.pumpAndSettle();
    expect(orders.resumed, 1);
    expect(orders.submits, 0);
  });
}

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/trading_rooms/data/customer_paper_trading_repository.dart';
import 'package:trading_client/src/features/trading_rooms/data/customer_room_repository.dart';
import 'package:trading_client/src/features/trading_rooms/presentation/minttick_paper_performance_panel.dart';
import 'package:trading_client/src/theme/app_theme.dart';

const _room = '11111111-1111-1111-1111-111111111111';
final _now = DateTime.utc(2026, 11, 12, 7, 0);

CustomerPaperWallet _wallet() => CustomerPaperWallet(
  roomId: _room, roomStatus: 'PAPER_FUNDED',
  initialCashPaise: 2000000, availableCashPaise: 1700000,
  reservedCashPaise: 0, realizedPnlPaise: -1245,
  halted: true, executionEnabled: false, updatedAt: _now,
);

CustomerPaperOrder _fill(String id, int fees, int notional) =>
  CustomerPaperOrder(
    id: id, roomId: _room, instrumentId: 42,
    tradingSymbol: 'RELIANCE', exchange: 'NSE', side: 'BUY',
    quantity: 1, fillPrice: 100, limitPrice: 100,
    notionalPaise: notional, feesPaise: fees, cashAfterPaise: 1700000,
    exchangeAt: _now,
  );

class _FakePaper extends Fake implements HttpCustomerPaperTradingRepository {
  _FakePaper({this.fail = false});
  final bool fail;

  @override
  Future<List<CustomerPaperOrder>> orders(String roomId) async {
    expect(roomId, _room);
    if (fail) throw StateError('unreachable backend');
    return [_fill('1', 100, 10000), _fill('2', 110, 12000)];
  }

  @override
  Future<List<CustomerPaperPosition>> positions(String roomId) async {
    expect(roomId, _room);
    if (fail) throw StateError('unreachable backend');
    return [
      const CustomerPaperPosition(
        instrumentId: 42, quantity: 2, avgCostPaise: 11000),
      const CustomerPaperPosition(
        instrumentId: 43, quantity: 0, avgCostPaise: 14000),
    ];
  }
}

void main() {
  test('metrics use integer paise and label the 100-fill window', () {
    final snapshot = PaperPerformanceSnapshot.from(
      fills: [_fill('1', 100, 10000), _fill('2', 110, 12000)],
      positions: [
        const CustomerPaperPosition(
          instrumentId: 42, quantity: 2, avgCostPaise: 10000),
        const CustomerPaperPosition(
          instrumentId: 43, quantity: 0, avgCostPaise: 10000),
      ],
    );
    expect(snapshot.recentFills, 2);
    expect(snapshot.openPositions, 1);
    expect(snapshot.recentFeesPaise, 210);
    expect(snapshot.recentTurnoverPaise, 22000);
    expect(formatVirtualPaise(-1245), '−₹12.45');
    expect(formatVirtualPaise(210), '₹2.10');
  });

  testWidgets('read-only Paper performance shows verified metrics',
      (tester) async {
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: Scaffold(body: SingleChildScrollView(
        child: MintTickPaperPerformancePanel(
          roomId: _room, wallet: _wallet(), repository: _FakePaper()),
      )),
    ));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('paper-performance-realized')),
      findsOneWidget);
    expect(find.text('−₹12.45'), findsOneWidget);
    expect(find.text('₹2.10'), findsOneWidget);
    expect(find.text('₹220.00'), findsOneWidget);
    expect(find.text('Recent confirmed fills (max 100)'), findsOneWidget);
    expect(find.textContaining('Unrealized P&L'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('unavailable read-only API does not show invented totals',
      (tester) async {
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: Scaffold(body: SingleChildScrollView(
        child: MintTickPaperPerformancePanel(
          roomId: _room, wallet: _wallet(),
          repository: _FakePaper(fail: true)),
      )),
    ));
    await tester.pumpAndSettle();
    expect(find.textContaining('could not be verified'), findsOneWidget);
    expect(find.text('Recent confirmed fills (max 100)'), findsNothing);
    expect(tester.takeException(), isNull);
  });
}

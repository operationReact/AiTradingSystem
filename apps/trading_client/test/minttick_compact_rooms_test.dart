
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/trading_rooms/data/customer_room_repository.dart';
import 'package:trading_client/src/features/trading_rooms/data/customer_trade_intent_repository.dart';
import 'package:trading_client/src/features/trading_rooms/domain/trading_room_draft.dart';
import 'package:trading_client/src/features/trading_rooms/domain/trading_workspace_mode.dart';
import 'package:trading_client/src/features/trading_rooms/presentation/minttick_rooms_pages.dart';
import 'package:trading_client/src/theme/app_theme.dart';

void main() {
  test('dashboard rejects LIVE data masquerading as virtual PAPER cash', () {
    final raw = {
      'roomId': 'live-1', 'mode': 'LIVE', 'status': 'DRAFT',
      'plannedBudgetInr': 50000, 'availableCashPaise': 24000,
      'paperHalted': true, 'positionCount': 1,
      'paperFillCount': 2, 'blockedIntentCount': 0,
    };
    expect(() => CustomerRoomMetric.decode(raw), throwsFormatException);
    raw['availableCashPaise'] = null;
    raw['paperHalted'] = null;
    raw['positionCount'] = 0;
    raw['paperFillCount'] = 0;
    final row = CustomerRoomMetric.decode(raw);
    expect(row.mode, 'LIVE');
    expect(row.availableCashPaise, isNull);
  });

  test('LIVE ticket receipt refuses an executable or filled trade', () {
    final intent = {
      'id': 'intent-1', 'roomId': 'live-1', 'mode': 'LIVE',
      'instrumentId': 12, 'tradingSymbol': 'RELIANCE-EQ',
      'exchange': 'NSE', 'side': 'BUY', 'quantity': 2,
      'productType': 'DELIVERY', 'limitPrice': 2400.50,
      'state': 'EXECUTED', 'blockers': <String>[],
    };
    expect(() => CustomerBlockedOrderIntent.decode(intent), throwsFormatException);
    intent['state'] = 'PRECHECK_BLOCKED';
    expect(CustomerBlockedOrderIntent.decode(intent).state, 'PRECHECK_BLOCKED');
  });

  testWidgets('dense dashboard switches PAPER and LIVE without mixing room rows',
      (tester) async {
    tester.view.devicePixelRatio = 1;
    tester.view.physicalSize = const Size(390, 844);
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final store = TradingRoomDraftStore();
    final mode = TradingWorkspaceMode();
    addTearDown(store.dispose);
    addTearDown(mode.dispose);
    store.add(const TradingRoomDraft(
      id: 'p1', name: 'Paper swing', focus: 'Swing',
      paperBudget: 30000, riskPreference: 'Balanced', mode: 'PAPER'));
    store.add(const TradingRoomDraft(
      id: 'l1', name: 'Live morning', focus: 'Intraday',
      paperBudget: 50000, riskPreference: 'Cautious', mode: 'LIVE'));
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: MintTickTradingRoomsPage(
        store: store, mode: mode, onCreate: () {}, onOpen: (_) {}),
    ));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('draft-room-p1')), findsOneWidget);
    expect(find.byKey(const ValueKey('draft-room-l1')), findsNothing);
    mode.select('LIVE');
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('draft-room-l1')), findsOneWidget);
    expect(find.byKey(const ValueKey('draft-room-p1')), findsNothing);
    expect(tester.takeException(), isNull);
  });

  testWidgets('compact room details navigate chart, order, positions and risk',
      (tester) async {
    tester.view.devicePixelRatio = 1;
    tester.view.physicalSize = const Size(390, 844);
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: const MintTickRoomDetailPage(
        room: TradingRoomDraft(
          id: 'p1', name: 'Paper swing', focus: 'Swing',
          paperBudget: 30000, riskPreference: 'Balanced', mode: 'PAPER'),
      ),
    ));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('room-tab-overview')), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('room-tab-chart')));
    await tester.pumpAndSettle();
    expect(find.text('Market data is unavailable.'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('room-tab-order')));
    await tester.pumpAndSettle();
    expect(find.text('Fund and verify the virtual Paper wallet first.'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('room-tab-risk')));
    await tester.pumpAndSettle();
    expect(find.text('Illustrative daily loss'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
}

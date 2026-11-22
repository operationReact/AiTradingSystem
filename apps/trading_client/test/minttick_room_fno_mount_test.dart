import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/data/authenticated_api_client.dart';
import 'package:trading_client/src/features/trading_rooms/data/customer_paper_fno_repository.dart';
import 'package:trading_client/src/features/trading_rooms/data/customer_execution_repository.dart';
import 'package:trading_client/src/features/trading_rooms/domain/trading_room_draft.dart';
import 'package:trading_client/src/features/trading_rooms/presentation/minttick_paper_fno_panel.dart';
import 'package:trading_client/src/features/trading_rooms/presentation/minttick_room_production_workspace.dart';

/// Returns a 409 for market (rights not verified) and empty positions, so the
/// mounted section renders its blocker without any network or quote data.
class _BlockedRepo implements CustomerPaperFnoRepository {
  @override
  Future<CustomerFnoCapability> capability(String roomId) async =>
      CustomerFnoCapability.decode({
        'status': 'SOURCE_CHECK_REQUIRED',
        'readOnlyConfigured': true,
        'paperConfigured': true,
        'liveExecutionAuthorized': false,
        'blockerCodes': <String>[],
      });

  @override
  Future<CustomerFnoExpiries> expiries(String roomId) async => CustomerFnoExpiries.decode({
        'underlyingKey': 'NSE_INDEX|Nifty 50',
        'status': 'VERIFIED',
        'observedAt': '2026-11-16T04:00:00Z',
        'expiries': ['2026-11-20'],
      });
  @override
  Future<CustomerFnoMarket> market(String roomId, DateTime expiry) async =>
      throw const ApiHttpFailure('CONFLICT', null, 409);
  @override
  Future<List<CustomerFnoPosition>> positions(String roomId) async => const [];
  @override
  Future<List<CustomerFnoFill>> orders(String roomId) async => const [];
  @override
  dynamic noSuchMethod(Invocation invocation) => throw UnimplementedError('${invocation.memberName}');
}

Future<void> pumpRoom(WidgetTester tester, TradingRoomDraft room, {CustomerPaperFnoRepository? fno}) async {
  tester.view.devicePixelRatio = 1;
  tester.view.physicalSize = const Size(900, 2600);
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  await tester.pumpWidget(MaterialApp(home: MintTickRoomProductionWorkspace(
    room: room,
    onBack: () {},
    onRefresh: () async {},
    onFundPaper: () async {},
    onSelectBroker: (_) async {},
    fno: fno,
  )));
  await tester.pumpAndSettle();
  await tester.tap(find.text('Positions').first);
  await tester.pumpAndSettle();
}

const _paperRoom = TradingRoomDraft(id: 'room-1', name: 'Paper room', focus: 'Swing',
    paperBudget: 100000, riskPreference: 'MODERATE', mode: 'PAPER', marketScope: 'FNO');
const _liveRoom = TradingRoomDraft(id: 'room-2', name: 'Live room', focus: 'Swing',
    paperBudget: 100000, riskPreference: 'MODERATE', mode: 'LIVE', marketScope: 'FNO');

void main() {
  testWidgets('F&O KPIs remain visible without sideways scrolling at 320dp',
      (tester) async {
    tester.view.devicePixelRatio = 1;
    tester.view.physicalSize = const Size(320, 720);
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(MaterialApp(home: MintTickRoomProductionWorkspace(
      room: _paperRoom,
      onBack: () {},
      onRefresh: () async {},
      onFundPaper: () async {},
      onSelectBroker: (_) async {},
    )));
    await tester.pumpAndSettle();
    for (final label in ['VIRTUAL CASH', 'OPTIONS P&L', 'POSITIONS']) {
      final finder = find.text(label);
      expect(finder, findsOneWidget);
      final rect = tester.getRect(finder);
      expect(rect.left, greaterThanOrEqualTo(0));
      expect(rect.right, lessThanOrEqualTo(320));
    }
    expect(tester.takeException(), isNull);
  });

  testWidgets('a PAPER F&O room mounts its own verified inventory without loading quotes', (tester) async {
    await pumpRoom(tester, _paperRoom, fno: _BlockedRepo());
    expect(find.byKey(const ValueKey('paper-fno-room-1')), findsOneWidget);
    expect(find.byType(MintTickPaperFnoPanel), findsOneWidget);
    expect(find.text('F&O Positions'), findsOneWidget);
    expect(find.text('No virtual F&O positions.'), findsOneWidget);
    expect(find.byKey(const ValueKey('paper-fno-expiry-dropdown')), findsNothing);
    expect(find.byKey(const ValueKey('paper-fno-submit')), findsNothing);
  });

  testWidgets('F&O-only room cannot expose equity order shortcuts in Positions or Orders',
      (tester) async {
    await pumpRoom(tester, _paperRoom, fno: _BlockedRepo());
    expect(find.byKey(const ValueKey('paper-fno-room-1')), findsOneWidget);
    expect(find.text('New Buy'), findsNothing);
    expect(find.text('New Sell'), findsNothing);
    await tester.tap(find.byKey(const ValueKey('room-tab-orders')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('fno-order-history-room-1')), findsOneWidget);
    expect(find.text('New Buy'), findsNothing);
    expect(find.text('New Sell'), findsNothing);
    expect(tester.takeException(), isNull);
  });

  testWidgets('a LIVE room never shows the PAPER F&O section', (tester) async {
    await pumpRoom(tester, _liveRoom, fno: _BlockedRepo());
    expect(find.byType(MintTickPaperFnoPanel), findsNothing);
  });

  testWidgets('without a repository the PAPER room shows no F&O section', (tester) async {
    await pumpRoom(tester, _paperRoom);
    expect(find.byType(MintTickPaperFnoPanel), findsNothing);
  });

  testWidgets('Mind lists actual backend preflight reasons rather than only a count',
      (tester) async {
    tester.view.devicePixelRatio = 1;
    tester.view.physicalSize = const Size(900, 2600);
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    const readiness = CustomerExecutionReadiness(
      roomId: 'room-1',
      mode: 'PAPER',
      roomStatus: 'PAPER_FUNDED',
      brokerConnected: false,
      virtualCashReady: true,
      executionEnabled: false,
      blockers: ['BROKER_AUTH_REQUIRED', 'QUOTE_NOT_FRESH'],
    );
    await tester.pumpWidget(MaterialApp(home: MintTickRoomProductionWorkspace(
      room: _paperRoom,
      readiness: readiness,
      onBack: () {},
      onRefresh: () async {},
      onFundPaper: () async {},
      onSelectBroker: (_) async {},
    )));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Mind').first);
    await tester.pumpAndSettle();
    expect(find.text('Preflight blockers: 2'), findsOneWidget);
    expect(find.text('BROKER AUTH REQUIRED'), findsOneWidget);
    expect(find.text('QUOTE NOT FRESH'), findsOneWidget);
    expect(find.text('READY'), findsNothing);
    expect(tester.takeException(), isNull);
  });
}

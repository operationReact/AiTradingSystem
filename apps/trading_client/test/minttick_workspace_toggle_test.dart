import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:trading_client/src/features/trading_rooms/domain/trading_room_draft.dart';
import 'package:trading_client/src/features/trading_rooms/domain/trading_workspace_mode.dart';
import 'package:trading_client/src/features/trading_rooms/presentation/minttick_customer_home.dart';
import 'package:trading_client/src/features/trading_rooms/presentation/minttick_rooms_pages.dart';
import 'package:trading_client/src/theme/app_theme.dart';

const _profile = <String, dynamic>{
  'role': 'CUSTOMER',
  'userId': 'customer-mode-test',
  'email': 'mode@example.test',
};

TradingRoomDraftStore _rooms() => TradingRoomDraftStore()
  ..add(const TradingRoomDraft(
      id: 'paper-1', name: 'Paper research',
      focus: 'Intraday', paperBudget: 20000,
      riskPreference: 'Cautious', mode: 'PAPER'))
  ..add(const TradingRoomDraft(
      id: 'live-1', name: 'Live investing',
      focus: 'Long-term', paperBudget: 80000,
      riskPreference: 'Balanced', mode: 'LIVE'));

void main() {
  setUp(() => SharedPreferences.setMockInitialValues({}));

  testWidgets('switching workspace filters both modes without mixing cards',
      (tester) async {
    tester.view.devicePixelRatio = 1;
    tester.view.physicalSize = const Size(390, 844);
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final rooms = _rooms();
    final mode = TradingWorkspaceMode();
    addTearDown(rooms.dispose);
    addTearDown(mode.dispose);

    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: MintTickCustomerHomePage(
        loadProfile: () async => _profile,
        drafts: rooms,
        mode: mode,
        onCreateRoom: () {},
        onRooms: () {},
        onMarkets: () {},
        onPortfolio: () {},
        onAccount: () {},
        onNotifications: () {},
      ),
    ));
    await tester.pumpAndSettle();

    expect(mode.selected, 'PAPER');
    expect(find.text('Paper research'), findsOneWidget);
    expect(find.text('Live investing'), findsNothing);
    expect(find.byKey(const ValueKey('paper-live-toggle')), findsOneWidget);

    await tester.tap(find.text('Live').first);
    await tester.pumpAndSettle();
    expect(mode.selected, 'LIVE');
    expect(find.text('Live investing'), findsOneWidget);
    expect(find.text('Paper research'), findsNothing);
    expect(find.text('₹80,000'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('Rooms page uses same shared toggle and filters customer rooms',
      (tester) async {
    final rooms = _rooms();
    final mode = TradingWorkspaceMode();
    addTearDown(rooms.dispose);
    addTearDown(mode.dispose);
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: MintTickTradingRoomsPage(
        store: rooms,
        mode: mode,
        onCreate: () {},
        onOpen: (_) {},
      ),
    ));
    await tester.pumpAndSettle();
    expect(find.text('Paper research'), findsOneWidget);
    expect(find.text('Live investing'), findsNothing);
    await tester.tap(find.text('Live').first);
    await tester.pumpAndSettle();
    expect(find.text('Live investing'), findsOneWidget);
    expect(find.text('Paper research'), findsNothing);
    expect(tester.takeException(), isNull);
  });

  test('mode view cannot be silently promoted into execution authorization', () {
    final mode = TradingWorkspaceMode();
    addTearDown(mode.dispose);
    mode.select('LIVE');
    expect(mode.selected, 'LIVE');
    expect(() => mode.select('BROKER_AUTHORIZED'), throwsArgumentError);
    mode.reset();
    expect(mode.selected, 'PAPER');
  });
}

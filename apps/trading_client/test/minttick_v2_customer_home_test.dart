import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/trading_rooms/domain/trading_room_draft.dart';
import 'package:trading_client/src/features/trading_rooms/domain/trading_workspace_mode.dart';
import 'package:trading_client/src/features/trading_rooms/presentation/minttick_customer_home.dart';
import 'package:trading_client/src/features/trading_rooms/presentation/minttick_customer_states.dart';
import 'package:trading_client/src/features/trading_rooms/presentation/minttick_rooms_pages.dart';
import 'package:trading_client/src/theme/app_theme.dart';

const _customer = <String, dynamic>{
  'role': 'CUSTOMER',
  'userId': 'sample-customer-id',
  'email': 'example@example.com',
};

void main() {
  for (final width in [320.0, 390.0, 430.0]) {
    testWidgets('MintTick V2 first-use Home at ${width.toInt()}dp', (tester) async {
      tester.view.devicePixelRatio = 1;
      tester.view.physicalSize = Size(width, 844);
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final store = TradingRoomDraftStore();
      addTearDown(store.dispose);
      final mode = TradingWorkspaceMode();
      addTearDown(mode.dispose);
      await tester.pumpWidget(MaterialApp(
        theme: buildTheme(Brightness.dark),
        home: MintTickCustomerHomePage(
          loadProfile: () async => _customer,
          drafts: store,
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
      expect(find.byKey(const ValueKey('customer-home-first-title')), findsOneWidget);
      expect(find.byKey(const ValueKey('customer-home-create-first')), findsOneWidget);
      expect(find.textContaining('P&L'), findsNothing);
      expect(find.text('PAPER + LIVE'), findsOneWidget);
      expect(tester.takeException(), isNull);
    });
  }

  testWidgets('verified returning customer sees only local draft totals', (tester) async {
    final store = TradingRoomDraftStore()
      ..add(const TradingRoomDraft(
        id: 'local-1', name: 'Morning focus',
        focus: 'Intraday', paperBudget: 20000,
        riskPreference: 'Balanced',
      ));
    addTearDown(store.dispose);
    final mode = TradingWorkspaceMode();
    addTearDown(mode.dispose);
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: MintTickCustomerHomePage(
        loadProfile: () async => _customer,
        drafts: store,
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
    expect(find.byKey(const ValueKey('customer-home-returning-title')), findsOneWidget);
    expect(find.text('Morning focus'), findsOneWidget);
    expect(find.text('₹20,000'), findsOneWidget);
    expect(find.textContaining('Mode selection does not enable execution'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('backend verification failure hides customer room content', (tester) async {
    final store = TradingRoomDraftStore();
    addTearDown(store.dispose);
    final mode = TradingWorkspaceMode();
    addTearDown(mode.dispose);
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: MintTickCustomerHomePage(
        loadProfile: () async => throw StateError('offline'),
        drafts: store,
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
    expect(find.byKey(const ValueKey('customer-home-retry')), findsOneWidget);
    expect(find.byKey(const ValueKey('customer-home-create-first')), findsNothing);
    expect(tester.takeException(), isNull);
  });

  testWidgets('room wizard reviews quantified examples before local draft', (tester) async {
    TradingRoomDraft? saved;
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: Builder(builder: (context) => Scaffold(
        body: Center(child: FilledButton(
          onPressed: () async {
            saved = await Navigator.of(context).push<TradingRoomDraft>(
              MaterialPageRoute(builder: (_) => const MintTickRoomCreationPage()),
            );
          },
          child: const Text('Open room builder'),
        )),
      )),
    ));
    await tester.tap(find.text('Open room builder'));
    await tester.pumpAndSettle();
    await tester.enterText(
      find.byKey(const ValueKey('room-name-input')), 'Morning focus');
    await tester.tap(find.byKey(const ValueKey('room-creation-next')));
    await tester.pumpAndSettle();
    expect(find.text('Your proposed plan.'), findsOneWidget);
    expect(find.text('Morning focus'), findsOneWidget);
    expect(find.text('₹200'), findsOneWidget);
    final acknowledgment = find.byKey(const ValueKey('room-draft-acknowledge'));
    await tester.ensureVisible(acknowledgment);
    await tester.pumpAndSettle();
    await tester.tap(acknowledgment);
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('room-creation-next')));
    await tester.pumpAndSettle();
    expect(saved?.name, 'Morning focus');
    expect(saved?.paperBudget, 20000);
    expect(saved?.riskPreference, 'Balanced');
    expect(tester.takeException(), isNull);
  });

  testWidgets('customer portfolio explicitly contains no fabricated holdings', (tester) async {
    final store = TradingRoomDraftStore();
    addTearDown(store.dispose);
    final mode = TradingWorkspaceMode();
    addTearDown(mode.dispose);
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: MintTickCustomerUnavailablePage(
        section: 'Portfolio',
        drafts: store,
        onRooms: () {},
      ),
    ));
    await tester.pumpAndSettle();
    expect(find.text('No broker portfolio connected'), findsOneWidget);
    expect(find.textContaining('Total P&L'), findsNothing);
    expect(tester.takeException(), isNull);
  });
}

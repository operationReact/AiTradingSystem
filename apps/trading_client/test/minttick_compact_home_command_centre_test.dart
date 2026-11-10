import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/trading_rooms/data/customer_home_summary_repository.dart';
import 'package:trading_client/src/features/trading_rooms/domain/trading_room_draft.dart';
import 'package:trading_client/src/features/trading_rooms/domain/trading_workspace_mode.dart';
import 'package:trading_client/src/features/trading_rooms/presentation/minttick_home_command_centre.dart';
import 'package:trading_client/src/theme/app_theme.dart';

class _SummaryRepo implements CustomerHomeSummaryRepository {
  bool fail = false;
  final List<String> requested = [];
  @override
  Future<CustomerHomeSummary> load(String mode) async {
    requested.add(mode);
    if (fail) throw StateError('private authentication failure');
    return CustomerHomeSummary.decode(_summary(mode));
  }
}

Map<String, dynamic> _summary(String mode) => {
  'contractVersion': 'customer.home.v1',
  'mode': mode,
  'generatedAt': '2026-11-11T00:00:00Z',
  'liveExecutionEnabled': false,
  'rooms': {
    'count': mode == 'PAPER' ? 1 : 0,
    'drafts': mode == 'PAPER' ? 1 : 0,
    'funded': 0,
    'plannedBudgetInr': mode == 'PAPER' ? 20000 : 0,
    'availablePaperCashPaise': mode == 'PAPER' ? 0 : null,
    'featured': mode == 'PAPER' ? [
      {'id': 'local-1', 'name': 'Long-term Compounding',
       'focus': 'LONG_TERM', 'status': 'DRAFT', 'mode': 'PAPER',
       'plannedBudgetInr': 20000, 'createdAt': '2026-11-11T00:00:00Z'}
    ] : [],
  },
  'brokers': {
    'angelOne': 'NOT_CONNECTED', 'upstox': 'NOT_CONNECTED',
    'connected': 0, 'complete': true,
  },
  'mind': {
    'market': {'status': 'UNAVAILABLE', 'source': null, 'asOf': null},
    'research': {'status': 'UNAVAILABLE', 'source': null, 'asOf': null},
    'executionEnabled': false,
  },
  'activity': null,
  'nextAction': {
    'code': mode == 'PAPER' ? 'COMPLETE_PAPER_ROOM' : 'CONNECT_BROKER',
    'destination': mode == 'PAPER' ? 'ROOM' : 'BROKERS',
    'roomId': mode == 'PAPER' ? 'local-1' : null,
  }
};

final _profile = {
  'role': 'CUSTOMER', 'userId': 'customer-test-1',
  'email': 'home@example.test',
};

void main() {
  test('read model rejects unknown balances or order permission', () {
    final raw = _summary('PAPER');
    raw['liveExecutionEnabled'] = true;
    expect(() => CustomerHomeSummary.decode(raw), throwsFormatException);
    final broken = _summary('PAPER');
    (broken['rooms'] as Map<String, dynamic>)['availablePaperCashPaise'] = -1;
    expect(() => CustomerHomeSummary.decode(broken), throwsFormatException);
    final invalidSource = _summary('LIVE');
    ((invalidSource['mind'] as Map<String, dynamic>)['market']
        as Map<String, dynamic>)['source'] = 'UNKNOWN';
    expect(() => CustomerHomeSummary.decode(invalidSource),
        throwsFormatException);
  });

  for (final width in [320.0, 390.0, 412.0]) {
    testWidgets('compact Home fits ${width.toInt()}dp and preserves Paper/Live scope',
        (tester) async {
      tester.view.physicalSize = Size(width, 844);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final mode = TradingWorkspaceMode();
      final drafts = TradingRoomDraftStore()..add(const TradingRoomDraft(
        id: 'local-1', name: 'Long-term Compounding',
        focus: 'Long-term', paperBudget: 20000,
        riskPreference: 'Balanced'));
      addTearDown(mode.dispose);
      addTearDown(drafts.dispose);
      final repo = _SummaryRepo();
      var openedRoom = 0;
      var openedMind = 0;
      var openedBroker = 0;
      await tester.pumpWidget(MaterialApp(
        theme: buildTheme(Brightness.dark),
        home: MintTickHomeCommandCentre(
          loadProfile: () async => _profile,
          summaryRepository: repo, drafts: drafts, mode: mode,
          onOpenRoom: (_) => openedRoom++,
          onCreateRoom: () {}, onRooms: () {},
          onMarkets: () {}, onMind: () => openedMind++,
          onPortfolio: () {}, onActivity: () {},
          onBroker: () => openedBroker++,
          onAccount: () {}, onNotifications: () {},
        ),
      ));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('home-next-action')), findsOneWidget);
      expect(find.byKey(const ValueKey('home-market-pulse')), findsOneWidget);
      expect(find.byKey(const ValueKey('home-mind-status')), findsOneWidget);
      expect(find.text('Long-term Compounding'), findsOneWidget);
      expect(find.textContaining('20,000 planned'), findsWidgets);
      expect(find.textContaining('Mode selection does not enable execution'),
          findsWidgets);
      expect(find.textContaining('P&L'), findsNothing);
      expect(tester.takeException(), isNull);

      await tester.tap(find.byKey(const ValueKey('home-next-action')));
      await tester.pumpAndSettle();
      expect(openedRoom, 1);

      await tester.ensureVisible(find.byKey(const ValueKey('home-mind-status')));
      await tester.tap(find.byKey(const ValueKey('home-mind-status')));
      await tester.pumpAndSettle();
      expect(openedMind, 1);

      mode.select('LIVE');
      await tester.pumpAndSettle();
      expect(repo.requested, containsAllInOrder(['PAPER', 'LIVE']));
      expect(find.text('Connect your brokerage account'), findsOneWidget);
      expect(find.textContaining('20,000 planned'), findsNothing);
      await tester.tap(find.byKey(const ValueKey('home-next-action')));
      expect(openedBroker, 1);
      expect(tester.takeException(), isNull);
    });
  }

  testWidgets('failed secure read never invents a Home balance', (tester) async {
    final mode = TradingWorkspaceMode();
    final drafts = TradingRoomDraftStore();
    final repo = _SummaryRepo()..fail = true;
    addTearDown(mode.dispose);
    addTearDown(drafts.dispose);
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: MintTickHomeCommandCentre(
        loadProfile: () async => _profile,
        summaryRepository: repo, drafts: drafts, mode: mode,
        onCreateRoom: () {}, onOpenRoom: (_) {}, onRooms: () {},
        onMarkets: () {}, onMind: () {}, onPortfolio: () {},
        onActivity: () {}, onBroker: () {}, onAccount: () {},
        onNotifications: () {},
      ),
    ));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('home-summary-retry')), findsOneWidget);
    expect(find.textContaining('private authentication failure'), findsNothing);
    expect(find.byKey(const ValueKey('home-next-action')), findsNothing);
    expect(tester.takeException(), isNull);
  });
}

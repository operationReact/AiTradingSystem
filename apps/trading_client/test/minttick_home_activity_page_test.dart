import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/trading_rooms/data/customer_home_activity_repository.dart';
import 'package:trading_client/src/features/trading_rooms/data/customer_home_summary_repository.dart';
import 'package:trading_client/src/features/trading_rooms/domain/trading_workspace_mode.dart';
import 'package:trading_client/src/features/trading_rooms/presentation/minttick_home_activity_page.dart';
import 'package:trading_client/src/theme/app_theme.dart';

class _History implements CustomerHomeActivityRepository {
  final List<String> modes = [];
  bool fail = false;

  @override
  Future<List<HomeActivity>> load(String mode) async {
    modes.add(mode);
    if (fail) throw StateError('secret provider error');
    if (mode == 'LIVE') return const [];
    return [
      HomeActivity(
        kind: 'ROOM_PLAN_SAVED',
        description: 'Trading Room plan saved',
        occurredAt: DateTime.utc(2026, 11, 10, 12, 00),
        roomId: 'local-room-id',
      ),
      HomeActivity(
        kind: 'PAPER_CASH_EVENT',
        description: 'INITIAL_PAPER_ALLOCATION',
        occurredAt: DateTime.utc(2026, 11, 10, 12, 5),
        roomId: 'local-room-id',
      ),
    ];
  }
}

void main() {
  for (final width in [320.0, 390.0]) {
    testWidgets('activity history is persisted-source only on ${width.toInt()}dp',
        (tester) async {
      tester.view.devicePixelRatio = 1;
      tester.view.physicalSize = Size(width, 844);
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final mode = TradingWorkspaceMode();
      addTearDown(mode.dispose);
      final repository = _History();
      var selectedRoom = '';
      await tester.pumpWidget(MaterialApp(
        theme: buildTheme(Brightness.dark),
        home: MintTickHomeActivityPage(
          repository: repository,
          mode: mode,
          onRoom: (id) => selectedRoom = id,
          onRooms: () {},
        ),
      ));
      await tester.pumpAndSettle();
      expect(find.text('Trading Room plan saved'), findsOneWidget);
      expect(find.text('Virtual Paper wallet funded'), findsOneWidget);
      expect(find.textContaining('P&L'), findsNothing);
      await tester.tap(find.text('Virtual Paper wallet funded'));
      expect(selectedRoom, 'local-room-id');
      mode.select('LIVE');
      await tester.pumpAndSettle();
      expect(repository.modes, containsAllInOrder(['PAPER', 'LIVE']));
      expect(find.textContaining('No persisted Trading Room'), findsOneWidget);
      expect(tester.takeException(), isNull);
    });
  }

  testWidgets('activity failures do not expose broker exception details',
      (tester) async {
    final mode = TradingWorkspaceMode();
    addTearDown(mode.dispose);
    final repository = _History()..fail = true;
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: MintTickHomeActivityPage(
        repository: repository, mode: mode,
        onRoom: (_) {}, onRooms: () {},
      ),
    ));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('home-activity-retry')), findsOneWidget);
    expect(find.textContaining('secret provider error'), findsNothing);
    expect(tester.takeException(), isNull);
  });
}

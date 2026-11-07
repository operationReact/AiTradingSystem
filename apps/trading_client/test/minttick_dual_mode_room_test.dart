import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/trading_rooms/domain/trading_room_draft.dart';
import 'package:trading_client/src/features/trading_rooms/presentation/minttick_rooms_pages.dart';
import 'package:trading_client/src/theme/app_theme.dart';

class _SavedRoomRepository implements RoomDraftPersistence {
  final rooms = <TradingRoomDraft>[];
  bool failLoad = false;

  @override
  Future<List<TradingRoomDraft>> list() async {
    if (failLoad) throw StateError('Backend unavailable');
    return List.unmodifiable(rooms);
  }

  @override
  Future<TradingRoomDraft> create(TradingRoomDraft room) async {
    final saved = TradingRoomDraft(
      id: 'db-${rooms.length + 1}',
      name: room.name, focus: room.focus,
      paperBudget: room.paperBudget,
      riskPreference: room.riskPreference,
      mode: room.mode,
    );
    rooms.add(saved);
    return saved;
  }
}

void main() {
  test('PAPER and LIVE room intents remain independent in persistence store', () async {
    final repository = _SavedRoomRepository();
    final store = TradingRoomDraftStore(repository: repository);
    addTearDown(store.dispose);
    await store.create(const TradingRoomDraft(
      id: 'local', name: 'Intraday',
      focus: 'Intraday', paperBudget: 20000,
      riskPreference: 'Balanced', mode: 'LIVE'));
    await store.create(const TradingRoomDraft(
      id: 'local', name: 'Long-term',
      focus: 'Long-term', paperBudget: 30000,
      riskPreference: 'Cautious', mode: 'PAPER'));
    await store.load();
    expect(store.drafts.map((r) => r.mode), containsAll(['LIVE', 'PAPER']));
    expect(store.drafts.every((room) => room.id.startsWith('db-')), isTrue);
    expect(store.drafts.every((room) => room.paperBudget > 0), isTrue);
    store.clear();
    expect(store.drafts, isEmpty);
    await store.load();
    expect(store.drafts.length, 2);
  });

  test('Room reload does not fabricate an empty room list on API failure', () async {
    final repository = _SavedRoomRepository()..failLoad = true;
    final store = TradingRoomDraftStore(repository: repository);
    addTearDown(store.dispose);
    await store.load();
    expect(store.loaded, isFalse);
    expect(store.lastError, contains('Could not retrieve'));
    expect(store.drafts, isEmpty);
  });

  testWidgets('Create Room offers LIVE and PAPER before reviewing a plan',
      (tester) async {
    tester.view.devicePixelRatio = 1;
    tester.view.physicalSize = const Size(390, 844);
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    TradingRoomDraft? result;

    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: Builder(builder: (context) => Scaffold(
        body: FilledButton(
          onPressed: () async {
            result = await Navigator.of(context).push<TradingRoomDraft>(
              MaterialPageRoute(builder: (_) => const MintTickRoomCreationPage()),
            );
          },
          child: const Text('New Trading Room'),
        ),
      )),
    ));
    await tester.tap(find.text('New Trading Room'));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('room-mode-toggle')), findsOneWidget);
    await tester.tap(find.text('Live'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const ValueKey('room-name-input')), 'Live morning');
    await tester.tap(find.byKey(const ValueKey('room-creation-next')));
    await tester.pumpAndSettle();

    expect(find.text('Your proposed plan.'), findsOneWidget);
    expect(find.text('LIVE'), findsWidgets);
    final ack = find.byKey(const ValueKey('room-draft-acknowledge'));
    await tester.dragUntilVisible(
      ack, find.byType(ListView).last, const Offset(0, -240),
    );
    await tester.pumpAndSettle();
    await tester.tap(ack);
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('room-creation-next')));
    await tester.pumpAndSettle();
    expect(result?.mode, 'LIVE');
    expect(result?.name, 'Live morning');
    expect(tester.takeException(), isNull);
  });
}

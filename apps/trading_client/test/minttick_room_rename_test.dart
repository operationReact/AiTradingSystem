import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:trading_client/src/data/authenticated_api_client.dart';
import 'package:trading_client/src/data/operator_session.dart';
import 'package:trading_client/src/features/trading_rooms/data/customer_room_repository.dart';
import 'package:trading_client/src/features/trading_rooms/domain/trading_room_draft.dart';
import 'package:trading_client/src/features/trading_rooms/domain/trading_workspace_mode.dart';
import 'package:trading_client/src/features/trading_rooms/presentation/minttick_rooms_pages.dart';
import 'package:trading_client/src/theme/app_theme.dart';

class _RenamingPersistence implements RoomDraftPersistence, RoomDraftRenaming {
  TradingRoomDraft room = const TradingRoomDraft(
    id: 'p1', name: 'Old Swing', focus: 'Swing',
    paperBudget: 10000, riskPreference: 'Balanced',
    mode: 'PAPER', status: 'PAPER_FUNDED',
  );
  bool conflict = false;
  int writes = 0;

  @override
  Future<List<TradingRoomDraft>> list() async => [room];

  @override
  Future<TradingRoomDraft> create(TradingRoomDraft draft) =>
      throw UnimplementedError();

  @override
  Future<TradingRoomDraft> rename(String roomId, String name) async {
    if (roomId != room.id) throw StateError('Wrong room');
    writes++;
    if (conflict) throw const ApiHttpFailure('CONFLICT', null, 409);
    room = TradingRoomDraft(
      id: room.id, name: name, focus: room.focus,
      paperBudget: room.paperBudget, riskPreference: room.riskPreference,
      mode: room.mode, status: room.status,
    );
    return room;
  }
}

void main() {
  testWidgets('room list rename keeps a funded Paper room and stable ID',
      (tester) async {
    tester.view.physicalSize = const Size(390, 844);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final persistence = _RenamingPersistence();
    final store = TradingRoomDraftStore(repository: persistence);
    final mode = TradingWorkspaceMode();
    addTearDown(store.dispose);
    addTearDown(mode.dispose);
    await store.load();

    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: Scaffold(body: MintTickTradingRoomsPage(
        store: store, mode: mode, onCreate: () {}, onOpen: (_) {})),
    ));
    await tester.pumpAndSettle();
    expect(find.text('Old Swing'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('room-rename-p1')));
    await tester.pumpAndSettle();
    expect(find.text('Rename room'), findsOneWidget);
    expect(find.text('Old Swing'), findsWidgets);

    await tester.enterText(find.byKey(const ValueKey('room-rename-input')),
        '  Swing   Alpha   ');
    await tester.tap(find.byKey(const ValueKey('room-rename-confirm')));
    await tester.pumpAndSettle();

    expect(persistence.writes, 1);
    expect(find.text('Swing Alpha'), findsOneWidget);
    expect(store.drafts.single.id, 'p1');
    expect(store.drafts.single.status, 'PAPER_FUNDED');
    expect(store.drafts.single.paperBudget, 10000);
    expect(tester.takeException(), isNull);
  });

  testWidgets('rename conflict keeps existing title and allows retry',
      (tester) async {
    tester.view.physicalSize = const Size(390, 844);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final persistence = _RenamingPersistence()..conflict = true;
    final store = TradingRoomDraftStore(repository: persistence);
    final mode = TradingWorkspaceMode();
    addTearDown(store.dispose);
    addTearDown(mode.dispose);
    await store.load();
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: Scaffold(body: MintTickTradingRoomsPage(
        store: store, mode: mode, onCreate: () {}, onOpen: (_) {})),
    ));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('room-rename-p1')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const ValueKey('room-rename-input')),
      'Duplicate');
    await tester.tap(find.byKey(const ValueKey('room-rename-confirm')));
    await tester.pumpAndSettle();
    expect(find.textContaining('already uses this name'), findsOneWidget);
    expect(store.drafts.single.name, 'Old Swing');

    persistence.conflict = false;
    await tester.enterText(find.byKey(const ValueKey('room-rename-input')),
      'Unique Swing');
    await tester.tap(find.byKey(const ValueKey('room-rename-confirm')));
    await tester.pumpAndSettle();
    expect(store.drafts.single.name, 'Unique Swing');
    expect(persistence.writes, 2);
    expect(tester.takeException(), isNull);
  });

  testWidgets('LIVE room can be renamed from header without changing room ID',
      (tester) async {
    tester.view.physicalSize = const Size(390, 844);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    const id = 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee';
    var renameCount = 0;
    final api = AuthenticatedApiClient(
      session: OperatorSession()..setOperatorKey('test-operator'),
      baseUri: Uri.parse('http://mock.invalid'),
      client: MockClient((request) async {
        if (request.method == 'PUT' &&
            request.url.path == '/api/customer/trading-rooms/$id/name') {
          renameCount++;
          final body = jsonDecode(request.body) as Map<String, dynamic>;
          return http.Response(jsonEncode({
            'id': id,
            'name': body['name'],
            'focus': 'SWING',
            'plannedBudgetInr': 20000,
            'riskPreference': 'BALANCED',
            'mode': 'LIVE',
            'status': 'DRAFT',
          }), 200);
        }
        return http.Response('{"code":"UNEXPECTED"}', 404);
      }),
    );
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: MintTickRoomDetailPage(
        room: const TradingRoomDraft(
          id: id, name: 'Live Old', focus: 'Swing', paperBudget: 20000,
          riskPreference: 'Balanced', mode: 'LIVE'),
        repository: HttpCustomerRoomRepository(api),
        onRoomChanged: () async {},
      ),
    ));
    await tester.pumpAndSettle();
    expect(find.text('Live Old'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('room-action-menu')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Rename room'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const ValueKey('room-rename-input')),
      'Live New');
    await tester.tap(find.byKey(const ValueKey('room-rename-confirm')));
    await tester.pumpAndSettle();
    expect(renameCount, 1);
    expect(find.text('Live New'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
}

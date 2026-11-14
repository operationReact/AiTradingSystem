import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/data/authenticated_api_client.dart';
import 'package:trading_client/src/features/trading_rooms/data/customer_paper_fno_repository.dart';
import 'package:trading_client/src/features/trading_rooms/domain/trading_room_draft.dart';
import 'package:trading_client/src/features/trading_rooms/presentation/minttick_paper_fno_panel.dart';
import 'package:trading_client/src/features/trading_rooms/presentation/minttick_room_production_workspace.dart';

/// Returns a 409 for market (rights not verified) and empty positions, so the
/// mounted section renders its blocker without any network or quote data.
class _BlockedRepo implements CustomerPaperFnoRepository {
  @override
  Future<CustomerFnoMarket> market(String roomId, DateTime expiry) async =>
      throw const ApiHttpFailure('CONFLICT', null, 409);
  @override
  Future<List<CustomerFnoPosition>> positions(String roomId) async => const [];
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

const _paperRoom = TradingRoomDraft(id: 'room-1', name: 'Paper room', focus: 'F&O',
    paperBudget: 100000, riskPreference: 'MODERATE', mode: 'PAPER');
const _liveRoom = TradingRoomDraft(id: 'room-2', name: 'Live room', focus: 'F&O',
    paperBudget: 100000, riskPreference: 'MODERATE', mode: 'LIVE');

void main() {
  testWidgets('a PAPER room mounts the F&O section on Positions and shows the server blocker', (tester) async {
    await pumpRoom(tester, _paperRoom, fno: _BlockedRepo());
    expect(find.byKey(const ValueKey('paper-fno-room-1')), findsOneWidget);
    expect(find.byType(MintTickPaperFnoPanel), findsOneWidget);
    // Nothing is requested until the user enters a verified expiry.
    expect(find.textContaining('display rights are not verified'), findsNothing);
    await tester.enterText(find.byKey(const ValueKey('paper-fno-expiry')), '2026-11-20');
    await tester.tap(find.byKey(const ValueKey('paper-fno-expiry-load')));
    await tester.pumpAndSettle();
    expect(find.textContaining('display rights are not verified'), findsOneWidget);
  });

  testWidgets('a LIVE room never shows the PAPER F&O section', (tester) async {
    await pumpRoom(tester, _liveRoom, fno: _BlockedRepo());
    expect(find.byType(MintTickPaperFnoPanel), findsNothing);
  });

  testWidgets('without a repository the PAPER room shows no F&O section', (tester) async {
    await pumpRoom(tester, _paperRoom);
    expect(find.byType(MintTickPaperFnoPanel), findsNothing);
  });
}

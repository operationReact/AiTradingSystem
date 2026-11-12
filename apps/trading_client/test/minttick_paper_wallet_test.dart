import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/trading_rooms/data/customer_room_repository.dart';
import 'package:trading_client/src/features/trading_rooms/domain/trading_room_draft.dart';
import 'package:trading_client/src/features/trading_rooms/presentation/minttick_rooms_pages.dart';
import 'package:trading_client/src/theme/app_theme.dart';

const _roomId = '11111111-1111-1111-1111-111111111111';
const _room = TradingRoomDraft(
  id: _roomId, name: 'Paper focus', focus: 'Intraday',
  paperBudget: 20000, riskPreference: 'Balanced',
  mode: 'PAPER', status: 'DRAFT',
);

final _now = DateTime.utc(2026, 11, 7, 7);

CustomerPaperWallet wallet() => CustomerPaperWallet(
  roomId: _roomId, roomStatus: 'PAPER_FUNDED',
  initialCashPaise: 2000000, availableCashPaise: 2000000,
  reservedCashPaise: 0, realizedPnlPaise: 0,
  halted: true, executionEnabled: false, updatedAt: _now,
);

class _WalletApi extends Fake implements HttpCustomerRoomRepository {
  int activationCalls = 0;

  @override
  Future<CustomerPaperWallet> activatePaper(String roomId) async {
    expect(roomId, _roomId);
    activationCalls++;
    return wallet();
  }

  @override
  Future<CustomerPaperWallet> paperAccount(String roomId) async {
    expect(roomId, _roomId);
    return wallet();
  }

  @override
  Future<List<CustomerPaperCashEvent>> paperLedger(String roomId) async {
    expect(roomId, _roomId);
    return [CustomerPaperCashEvent(
      id: 'event', eventKind: 'INITIAL_PAPER_ALLOCATION',
      cashDeltaPaise: 2000000, cashAfterPaise: 2000000, createdAt: _now,
    )];
  }
}

void main() {
  test('PAPER wallet rejects forged LIVE response and invalid cash', () {
    final valid = <String, dynamic>{
      'roomId': _roomId, 'mode': 'PAPER', 'roomStatus': 'PAPER_FUNDED',
      'initialCashPaise': 2000000, 'availableCashPaise': 2000000,
      'reservedCashPaise': 0, 'realizedPnlPaise': 0,
      'halted': true, 'executionEnabled': false,
      'updatedAt': '2026-11-07T07:00:00Z',
    };
    expect(CustomerPaperWallet.decode(valid).availableCashPaise, 2000000);
    expect(() => CustomerPaperWallet.decode({...valid, 'mode': 'LIVE'}),
      throwsFormatException);
    expect(() => CustomerPaperWallet.decode({...valid, 'availableCashPaise': -1}),
      throwsFormatException);
    expect(() => CustomerPaperWallet.decode({...valid, 'executionEnabled': 'yes'}),
      throwsFormatException);
  });

  testWidgets('User must confirm virtual funding before any wallet appears',
      (tester) async {
    tester.view.devicePixelRatio = 1;
    tester.view.physicalSize = const Size(390, 844);
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final repo = _WalletApi();
    int reloads = 0;
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: MintTickRoomDetailPage(
        room: _room,
        repository: repo,
        onRoomChanged: () async {
          reloads++;
        },
      ),
    ));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('paper-wallet-cash')), findsNothing);
    expect(find.text('LIVE'), findsNothing);

    await tester.tap(find.byKey(const ValueKey('activate-paper-wallet')));
    await tester.pumpAndSettle();
    expect(find.text('Fund your Paper room?'), findsOneWidget);
    expect(repo.activationCalls, 0);
    await tester.tap(find.byKey(const ValueKey('confirm-paper-activation')));
    await tester.pumpAndSettle();
    expect(repo.activationCalls, 1);
    expect(reloads, 1);
    expect(find.byKey(const ValueKey('paper-wallet-cash')), findsOneWidget);
    expect(find.text('₹20,000.00'), findsAtLeastNWidgets(1));
    expect(find.text('Automated execution'), findsOneWidget);
    expect(find.text('Disabled'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('LIVE room has no virtual wallet funding action', (tester) async {
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: const MintTickRoomDetailPage(
        room: TradingRoomDraft(
          id: _roomId, name: 'Live plan', focus: 'Intraday',
          paperBudget: 20000, riskPreference: 'Balanced', mode: 'LIVE',
        ),
      ),
    ));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('activate-paper-wallet')), findsNothing);
    expect(find.byKey(const ValueKey('live-room-broker-upstox')), findsOneWidget);
    expect(find.text('Select Live broker account'), findsOneWidget);
  });
}

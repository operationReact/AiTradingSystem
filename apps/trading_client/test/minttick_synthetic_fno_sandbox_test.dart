import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/trading_rooms/data/customer_paper_fno_repository.dart';
import 'package:trading_client/src/features/trading_rooms/presentation/minttick_synthetic_fno_sandbox_panel.dart';

Map<String, dynamic> fixture({bool ledger = true, bool exchange = false,
    bool live = false, int held = 0, int fills = 0}) => {
  'mode': 'SYNTHETIC_PRACTICE', 'synthetic': true,
  'exchangeDataUsed': exchange, 'brokerOrdersEnabled': live,
  'initialCashPaise': 2000000,
  'availableCashPaise': held > 0 ? 1899900 : 2000000,
  'realizedPnlPaise': 0,
  'ledgerReconciled': ledger,
  'recordedFills': fills,
  'contracts': [
    {
      'code': 'SIM_FNO_CE', 'label': 'Synthetic CALL (practice)',
      'optionType': 'CE', 'lotSize': 10, 'buyPrice': 100.0,
      'sellPrice': 99.0,
    },
    {
      'code': 'SIM_FNO_PE', 'label': 'Synthetic PUT (practice)',
      'optionType': 'PE', 'lotSize': 10, 'buyPrice': 80.0,
      'sellPrice': 79.0,
    },
  ],
  'positions': held == 0 ? <Object>[] : [
    {'contractCode': 'SIM_FNO_CE', 'quantity': held, 'costBasisPaise': 100100},
  ],
  'orders': <Object>[],
};

Map<String, dynamic> fill(String side) => {
  'id': 'be47432c-7dfa-46bc-87ea-e026ae0342ed',
  'contractCode': 'SIM_FNO_CE', 'side': side,
  'lots': 1, 'quantity': 10,
  'fillPrice': side == 'BUY' ? 100.0 : 99.0,
  'cashAfterPaise': side == 'BUY' ? 1899900 : 1998800,
  'realizedPnlDeltaPaise': side == 'BUY' ? 0 : -1200,
};

class FakeSandboxRepository implements CustomerPaperFnoRepository {
  FakeSandboxRepository({this.ledger = true, this.fail = false});
  bool ledger;
  bool fail;
  int held = 0;
  final calls = <String>[];

  @override
  Future<CustomerFnoSandboxState> sandboxState(String roomId) async =>
      CustomerFnoSandboxState.decode(fixture(
        held: held, ledger: ledger, fills: calls.length));

  @override
  Future<CustomerFnoSandboxFill> sandboxSubmit({
    required String roomId, required String idempotencyKey,
    required String contractCode, required String side, required int lots,
  }) async {
    calls.add(side);
    if (fail) throw StateError('unknown transport failure');
    if (side == 'BUY') held += lots * 10;
    if (side == 'SELL') held -= lots * 10;
    return CustomerFnoSandboxFill.decode(fill(side));
  }

  @override
  dynamic noSuchMethod(Invocation invocation) =>
      throw UnimplementedError('${invocation.memberName}');
}

void main() {
  test('synthetic decoder never accepts exchange or real-order claims', () {
    final result = CustomerFnoSandboxState.decode(fixture());
    expect(result.availableCashPaise, 2000000);
    expect(result.ledgerReconciled, isTrue);
    expect(result.contracts.map((c) => c.code), contains('SIM_FNO_PE'));
    for (final bad in [
      fixture(exchange: true),
      fixture(live: true),
      {...fixture(), 'mode': 'NSE_FO_LIVE'},
    ]) {
      expect(() => CustomerFnoSandboxState.decode(bad), throwsFormatException);
    }
    final tampered = fixture();
    (tampered['contracts'] as List)[0] = {
      'code': 'NSE_FO|NIFTY', 'optionType': 'CE', 'lotSize': 10,
      'label': 'Real CE', 'buyPrice': 100, 'sellPrice': 99,
    };
    expect(() => CustomerFnoSandboxState.decode(tampered), throwsFormatException);
  });

  testWidgets('sandbox practices BUY then SELL independently', (tester) async {
    tester.view.devicePixelRatio = 1;
    tester.view.physicalSize = const Size(920, 2300);
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final repo = FakeSandboxRepository();
    await tester.pumpWidget(MaterialApp(home: Scaffold(body: SingleChildScrollView(
      child: MintTickSyntheticFnoSandboxPanel(roomId: 'room', repository: repo),
    ))));
    await tester.pumpAndSettle();
    expect(find.textContaining('Invented prices'), findsOneWidget);
    expect(find.textContaining('₹20000.00'), findsOneWidget);
    expect(find.byKey(const ValueKey('synthetic-fno-sandbox')), findsOneWidget);
    expect(find.byKey(const ValueKey('synthetic-sell-SIM_FNO_CE')), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('synthetic-buy-SIM_FNO_CE')));
    await tester.pumpAndSettle();
    expect(repo.calls, ['BUY']);
    expect(repo.held, 10);
    await tester.tap(find.byKey(const ValueKey('synthetic-sell-SIM_FNO_CE')));
    await tester.pumpAndSettle();
    expect(repo.calls, ['BUY', 'SELL']);
    expect(repo.held, 0);
  });

  testWidgets('unreconciled sandbox cannot submit practice orders', (tester) async {
    tester.view.devicePixelRatio = 1;
    tester.view.physicalSize = const Size(900, 1900);
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final repo = FakeSandboxRepository(ledger: false);
    await tester.pumpWidget(MaterialApp(home: Scaffold(body:
      MintTickSyntheticFnoSandboxPanel(roomId: 'room', repository: repo))));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('synthetic-fno-ledger-blocked')), findsOneWidget);
    expect(find.byKey(const ValueKey('synthetic-buy-SIM_FNO_CE')), findsNothing);
    expect(repo.calls, isEmpty);
  });
}

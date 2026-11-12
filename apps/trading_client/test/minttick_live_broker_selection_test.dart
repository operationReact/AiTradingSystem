import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/trading_rooms/data/customer_execution_repository.dart';
import 'package:trading_client/src/features/trading_rooms/domain/trading_room_draft.dart';
import 'package:trading_client/src/features/trading_rooms/presentation/minttick_rooms_pages.dart';
import 'package:trading_client/src/theme/app_theme.dart';

class _LiveExecution extends Fake implements HttpCustomerExecutionRepository {
  String? selected;
  int selectCalls = 0;

  CustomerExecutionReadiness get state => CustomerExecutionReadiness(
    roomId: 'live-room-1',
    mode: 'LIVE',
    roomStatus: 'DRAFT',
    selectedBroker: selected,
    brokerConnected: false,
    virtualCashReady: false,
    executionEnabled: false,
    blockers: [
      if (selected == null) 'ROOM_BROKER_ASSIGNMENT_NOT_CONFIGURED',
      'BROKER_NOT_CONNECTED',
      'BROKER_APPROVED_HOSTING_NOT_VERIFIED',
      'REGISTERED_STATIC_IP_NOT_VERIFIED',
      'ACCOUNT_WIDE_RISK_AND_RECONCILIATION_NOT_ENABLED',
      'EXPLICIT_LIVE_EXECUTION_CONSENT_NOT_CONFIGURED',
      'EXECUTION_DISABLED',
    ],
  );

  @override
  Future<CustomerExecutionReadiness> readiness(String roomId) async => state;

  @override
  Future<CustomerExecutionReadiness> selectLiveBroker(
      String roomId, String broker) async {
    selectCalls++;
    if (roomId != 'live-room-1') throw StateError('wrong owner room');
    selected = broker;
    return state;
  }
}

const _live = TradingRoomDraft(
  id: 'live-room-1', name: 'Live intraday', focus: 'Intraday',
  riskPreference: 'Balanced', paperBudget: 20000, mode: 'LIVE',
);
const _paper = TradingRoomDraft(
  id: 'paper-room-1', name: 'Paper intraday', focus: 'Intraday',
  riskPreference: 'Balanced', paperBudget: 20000, mode: 'PAPER',
);

void main() {
  test('readiness parses explicit owner broker without accepting live execution',
      () {
    final state = CustomerExecutionReadiness.decode({
      'roomId': 'live-1',
      'mode': 'LIVE',
      'roomStatus': 'DRAFT',
      'selectedBroker': 'UPSTOX',
      'brokerConnected': true,
      'virtualCashReady': false,
      'executionEnabled': false,
      'blockers': ['REGISTERED_STATIC_IP_NOT_VERIFIED', 'EXECUTION_DISABLED'],
    });
    expect(state.selectedBroker, 'UPSTOX');
    expect(state.brokerConnected, isTrue);
    expect(state.executionEnabled, isFalse);
    expect(CustomerExecutionReadiness.decode({
      'roomId': 'live-1',
      'mode': 'LIVE',
      'roomStatus': 'DRAFT',
      'selectedBroker': 'INVALID',
      'brokerConnected': false,
      'virtualCashReady': false,
      'executionEnabled': false,
      'blockers': ['EXECUTION_DISABLED'],
    }).selectedBroker, isNull);
  });

  testWidgets('Live room explicitly selects one broker but remains blocked',
      (tester) async {
    tester.view.devicePixelRatio = 1;
    tester.view.physicalSize = const Size(390, 844);
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final repo = _LiveExecution();
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: MintTickRoomDetailPage(room: _live, executionRepository: repo),
    ));
    await tester.pumpAndSettle();
    final choice = find.byKey(const ValueKey('live-room-broker-upstox'));
    expect(choice, findsOneWidget);
    await tester.ensureVisible(choice);
    await tester.pumpAndSettle();
    await tester.tap(choice);
    await tester.pumpAndSettle();
    expect(repo.selectCalls, 1);
    expect(repo.selected, 'UPSTOX');
    expect(tester.widget<ChoiceChip>(choice).selected, isTrue);
    expect(find.textContaining('Live order execution is still locked'),
      findsNothing); // no authenticated broker token
    expect(find.textContaining('Selected broker is not authenticated'),
      findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('PAPER room never renders Live broker assignment',
      (tester) async {
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: const MintTickRoomDetailPage(room: _paper),
    ));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('live-room-broker-upstox')),
      findsNothing);
    expect(find.byKey(const ValueKey('live-room-broker-angel_one')),
      findsNothing);
    expect(tester.takeException(), isNull);
  });
}

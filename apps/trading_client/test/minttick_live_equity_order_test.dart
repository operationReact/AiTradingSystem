import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/data/instrument_repository.dart';
import 'package:trading_client/src/features/trading_rooms/data/customer_execution_repository.dart';
import 'package:trading_client/src/features/trading_rooms/data/customer_market_repository.dart';
import 'package:trading_client/src/features/trading_rooms/presentation/minttick_room_order_sheet.dart';
import 'package:trading_client/src/theme/app_theme.dart';

// Mock-only. These tests never reach a broker: the execution repository is a
// fake, and no HTTP request is made.

const _roomId = '11111111-1111-1111-1111-111111111111';

const _sbin = InstrumentRecord(
  id: 202, exchange: 'NSE', exchangeSegment: 'NSE_CM',
  symbolToken: '3045', tradingSymbol: 'SBIN-EQ',
  name: 'State Bank of India', instrumentType: 'EQ',
  expiry: null, strike: null, lotSize: 1, tickSize: .05,
);

class _Market extends Fake implements HttpCustomerMarketRepository {
  @override
  Future<CustomerLiveQuote> liveQuote(int instrumentId) async =>
      CustomerLiveQuote(
        instrumentId: instrumentId, exchange: 'NSE', tradingSymbol: 'SBIN-EQ',
        status: 'LIVE', ltp: 250.0, exchangeAt: DateTime.now(),
        source: 'ANGEL_ONE_SMARTAPI_WS_V2');
}

class _Execution extends Fake implements HttpCustomerExecutionRepository {
  _Execution({required this.executable, this.submitError});

  final bool executable;
  final Object? submitError;
  int preflights = 0;
  int submits = 0;
  String? lastKey;

  @override
  Future<Object?> preflight({
    required String roomId, required int instrumentId, required String side,
    required int quantity, required num limitPrice,
  }) async {
    preflights++;
    return {
      'roomId': roomId, 'mode': 'LIVE', 'instrumentId': instrumentId,
      'executable': executable,
      'blockers': executable ? <String>[] : <String>['EXECUTION_DISABLED'],
    };
  }

  @override
  Future<LiveEquityOrder> submitLiveEquityOrder({
    required String roomId, required String idempotencyKey,
    required int instrumentId, required String side, required int quantity,
    required num limitPrice,
  }) async {
    submits++;
    lastKey = idempotencyKey;
    if (submitError != null) throw submitError!;
    return LiveEquityOrder(
      id: 'order-1', roomId: roomId, instrumentId: instrumentId,
      tradingSymbol: 'SBIN-EQ', side: side, quantity: quantity,
      limitPrice: limitPrice, status: LiveEquityOrderStatus.acknowledged,
      filledQuantity: 0, brokerOrderId: '2602000001');
  }
}

LiveEquityCapability _capability({required bool executable}) =>
    LiveEquityCapability(
      roomId: _roomId, selectedBroker: 'UPSTOX', brokerConnected: true,
      orderRouteImplemented: true, executionEnabled: executable,
      blockers: executable ? const [] : const ['EXECUTION_DISABLED'],
    );

Future<void> _openLiveTicket(WidgetTester tester,
    {required _Execution execution, required bool executable}) async {
  tester.view.physicalSize = const Size(390, 844);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  await tester.pumpWidget(MaterialApp(
    theme: buildTheme(Brightness.dark),
    home: Scaffold(body: Builder(builder: (context) => FilledButton(
      onPressed: () => showModalBottomSheet<void>(
        context: context, isScrollControlled: true,
        builder: (_) => MintTickRoomOrderSheet(
          roomId: _roomId, mode: 'LIVE', side: 'BUY',
          initialInstrument: _sbin, market: _Market(),
          execution: execution,
          liveCapability: _capability(executable: executable),
          onChanged: () async {},
        )),
      child: const Text('Open ticket')))),
  ));
  await tester.tap(find.text('Open ticket'));
  await tester.pumpAndSettle();
}

void main() {
  group('LiveEquityOrder decode (server contract)', () {
    test('accepts an acknowledged order with its broker ID', () {
      final order = LiveEquityOrder.decode({
        'id': 'o1', 'roomId': _roomId, 'broker': 'UPSTOX', 'instrumentId': 202,
        'tradingSymbol': 'SBIN-EQ', 'exchange': 'NSE', 'side': 'BUY',
        'quantity': 2, 'limitPrice': 250, 'state': 'ACKNOWLEDGED',
        'brokerOrderId': '2602000001', 'filledQuantity': 0,
      });
      expect(order.status, LiveEquityOrderStatus.acknowledged);
      expect(order.brokerOrderId, '2602000001');
    });

    test('rejects an unknown server state instead of displaying it', () {
      expect(() => LiveEquityOrder.decode({
        'id': 'o1', 'roomId': _roomId, 'instrumentId': 202,
        'tradingSymbol': 'SBIN-EQ', 'side': 'BUY', 'quantity': 2,
        'limitPrice': 250, 'state': 'PENDING_BROKER', 'filledQuantity': 0,
      }), throwsFormatException);
    });

    test('rejects a filled quantity larger than the order quantity', () {
      expect(() => LiveEquityOrder.decode({
        'id': 'o1', 'roomId': _roomId, 'instrumentId': 202,
        'tradingSymbol': 'SBIN-EQ', 'side': 'BUY', 'quantity': 2,
        'limitPrice': 250, 'state': 'FILLED', 'filledQuantity': 5,
      }), throwsFormatException);
    });
  });

  testWidgets('gate off: the ticket never calls the submit route', (tester) async {
    final execution = _Execution(executable: false);
    await _openLiveTicket(tester, execution: execution, executable: false);
    await tester.tap(find.byKey(const ValueKey('room-ticket-review')));
    await tester.pumpAndSettle();
    expect(execution.preflights, 1);
    expect(execution.submits, 0);
    expect(find.textContaining('LIVE ORDER · REAL MONEY'), findsNothing);
    expect(find.text('Submit LIVE order'), findsNothing);
  });

  testWidgets('gate on: one order needs explicit confirmation, then one submit',
      (tester) async {
    final execution = _Execution(executable: true);
    await _openLiveTicket(tester, execution: execution, executable: true);
    await tester.tap(find.byKey(const ValueKey('room-ticket-review')));
    await tester.pumpAndSettle();
    // Nothing is sent before the explicit final confirmation.
    expect(execution.submits, 0);
    expect(find.textContaining('LIVE ORDER · REAL MONEY'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('room-order-final-confirm')));
    await tester.pumpAndSettle();
    expect(execution.submits, 1);
    expect(execution.lastKey, isNotNull);
    expect(execution.lastKey!.length, greaterThanOrEqualTo(8));
    // The server said ACKNOWLEDGED: never shown as filled.
    expect(find.text('Accepted by broker · not yet filled.'), findsOneWidget);
    expect(find.textContaining('Filled'), findsNothing);
  });

  testWidgets('a transport failure after dispatch is UNKNOWN and blocks new orders',
      (tester) async {
    final execution = _Execution(executable: true,
        submitError: StateError('connection reset'));
    await _openLiveTicket(tester, execution: execution, executable: true);
    await tester.tap(find.byKey(const ValueKey('room-ticket-review')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('room-order-final-confirm')));
    await tester.pumpAndSettle();
    expect(execution.submits, 1);
    expect(find.textContaining('Order outcome UNKNOWN'), findsOneWidget);
    // Review stays disabled until the outcome is reconciled.
    final review = tester.widget<FilledButton>(
        find.byKey(const ValueKey('room-ticket-review')));
    expect(review.onPressed, isNull);
  });
}

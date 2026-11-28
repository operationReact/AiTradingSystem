import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/data/authenticated_api_client.dart';
import 'package:trading_client/src/data/instrument_repository.dart';
import 'package:trading_client/src/features/trading_rooms/data/customer_execution_repository.dart';
import 'package:trading_client/src/features/trading_rooms/data/customer_market_repository.dart';
import 'package:trading_client/src/features/trading_rooms/presentation/minttick_room_order_sheet.dart';
import 'package:trading_client/src/theme/app_theme.dart';

// Mock-only. The execution repository is a fake and no HTTP request is made, so
// these tests cannot submit or reconcile a real broker order.

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

LiveEquityOrder order(String id, LiveEquityOrderStatus status,
    {int filled = 0, int quantity = 2}) =>
    LiveEquityOrder(
      id: id, roomId: _roomId, instrumentId: 202, tradingSymbol: 'SBIN-EQ',
      side: 'BUY', quantity: quantity, limitPrice: 250, status: status,
      filledQuantity: filled, brokerOrderId: null);

class _Execution extends Fake implements HttpCustomerExecutionRepository {
  _Execution({
    this.history = const [],
    this.submitResult,
    this.submitError,
    this.reconcileResult,
    this.listFails = false,
    this.executable = true,
  });

  /// Server-owned history. Reconcile replaces a row with what the server says.
  List<LiveEquityOrder> history;
  final LiveEquityOrder? submitResult;
  /// When set, submit throws this (after recording the server-side claim).
  final Object? submitError;
  final LiveEquityOrder? reconcileResult;
  final bool listFails;
  final bool executable;

  int preflights = 0;
  int submits = 0;
  int lists = 0;
  int reconciles = 0;
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
  Future<List<LiveEquityOrder>> listLiveEquityOrders(String roomId) async {
    lists++;
    if (listFails) throw StateError('history unavailable');
    return List.of(history);
  }

  @override
  Future<LiveEquityOrder> reconcileLiveEquityOrder(
      String roomId, String orderId) async {
    reconciles++;
    final result = reconcileResult;
    if (result == null) throw StateError('reconcile unavailable');
    history = [for (final o in history) o.id == orderId ? result : o];
    return result;
  }

  @override
  Future<LiveEquityOrder> submitLiveEquityOrder({
    required String roomId, required String idempotencyKey,
    required int instrumentId, required String side, required int quantity,
    required num limitPrice,
  }) async {
    submits++;
    lastKey = idempotencyKey;
    final error = submitError;
    if (error != null) {
      // The server claims the order before any broker request, so the record
      // exists even when the response is lost. Model that here.
      history = [
        order('claimed-$submits', LiveEquityOrderStatus.submitting),
        ...history,
      ];
      throw error;
    }
    final result = submitResult ??
        order('new-$submits', LiveEquityOrderStatus.acknowledged);
    history = [result, ...history];
    return result;
  }
}

LiveEquityCapability _capability({required bool executable}) =>
    LiveEquityCapability(
      roomId: _roomId, selectedBroker: 'UPSTOX', brokerConnected: true,
      orderRouteImplemented: true, executionEnabled: executable,
      blockers: executable ? const [] : const ['EXECUTION_DISABLED'],
    );

void _phoneSize(WidgetTester tester) {
  tester.view.physicalSize = const Size(390, 844);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
}

/// Opens the LIVE ticket through a launcher, as the room does, so tests can
/// close and reopen it and observe state that must survive the reopen.
Future<void> _launchTicket(WidgetTester tester, _Execution execution,
    {bool executable = true}) async {
  _phoneSize(tester);
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

Future<void> _closeAndReopen(WidgetTester tester) async {
  await tester.tap(find.text('Cancel'));
  await tester.pumpAndSettle();
  await tester.tap(find.text('Open ticket'));
  await tester.pumpAndSettle();
}

FilledButton _review(WidgetTester tester) => tester.widget<FilledButton>(
    find.byKey(const ValueKey('room-ticket-review')));

Future<void> _confirmSubmit(WidgetTester tester) async {
  await tester.tap(find.byKey(const ValueKey('room-ticket-review')));
  await tester.pumpAndSettle();
  await tester.tap(find.byKey(const ValueKey('room-order-final-confirm')));
  await tester.pumpAndSettle();
}

void main() {
  group('LiveEquityOrder decode (server contract)', () {
    test('accepts an acknowledged order with its broker ID', () {
      final decoded = LiveEquityOrder.decode({
        'id': 'o1', 'roomId': _roomId, 'broker': 'UPSTOX', 'instrumentId': 202,
        'tradingSymbol': 'SBIN-EQ', 'exchange': 'NSE', 'side': 'BUY',
        'quantity': 2, 'limitPrice': 250, 'state': 'ACKNOWLEDGED',
        'brokerOrderId': '2602000001', 'filledQuantity': 0,
      });
      expect(decoded.status, LiveEquityOrderStatus.acknowledged);
      expect(decoded.brokerOrderId, '2602000001');
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

    test('only SUBMITTING and UNKNOWN need reconciliation', () {
      expect(LiveEquityOrderStatus.submitting.needsReconciliation, isTrue);
      expect(LiveEquityOrderStatus.unknown.needsReconciliation, isTrue);
      for (final status in [
        LiveEquityOrderStatus.acknowledged,
        LiveEquityOrderStatus.partiallyFilled,
        LiveEquityOrderStatus.filled,
        LiveEquityOrderStatus.rejected,
        LiveEquityOrderStatus.cancelled,
      ]) {
        expect(status.needsReconciliation, isFalse, reason: status.name);
      }
    });
  });

  testWidgets('gate off: the ticket never calls the submit route', (tester) async {
    final execution = _Execution(executable: false);
    await _launchTicket(tester, execution, executable: false);
    await tester.tap(find.byKey(const ValueKey('room-ticket-review')));
    await tester.pumpAndSettle();
    expect(execution.preflights, 1);
    expect(execution.submits, 0);
    expect(find.text('Submit LIVE order'), findsNothing);
  });

  testWidgets('gate on: nothing is sent before the explicit final confirmation',
      (tester) async {
    final execution = _Execution();
    await _launchTicket(tester, execution);
    await tester.tap(find.byKey(const ValueKey('room-ticket-review')));
    await tester.pumpAndSettle();
    expect(execution.submits, 0);
    expect(find.textContaining('LIVE ORDER · REAL MONEY'), findsOneWidget);
  });

  testWidgets('acknowledged order shows ACCEPTED, never FILLED', (tester) async {
    final execution = _Execution();
    await _launchTicket(tester, execution);
    await _confirmSubmit(tester);
    expect(execution.submits, 1);
    expect(find.text('Accepted by broker · not yet filled.'), findsOneWidget);
    expect(find.textContaining('Filled'), findsNothing);
  });

  testWidgets('partial fill is reported with the server quantity', (tester) async {
    final execution = _Execution(submitResult:
        order('p1', LiveEquityOrderStatus.partiallyFilled, filled: 1));
    await _launchTicket(tester, execution);
    await _confirmSubmit(tester);
    expect(find.text('Partially filled · 1 of 2.'), findsOneWidget);
    expect(find.textContaining('Filled ·'), findsNothing);
  });

  testWidgets('broker rejection is recorded and does not block a new order',
      (tester) async {
    final execution = _Execution(submitResult:
        order('r1', LiveEquityOrderStatus.rejected));
    await _launchTicket(tester, execution);
    await _confirmSubmit(tester);
    expect(find.text('Rejected by broker.'), findsOneWidget);
    expect(_review(tester).onPressed, isNotNull);
  });

  testWidgets('a definite server 4xx is a rejection with nothing placed',
      (tester) async {
    final execution = _Execution(
        submitError: ApiHttpFailure('LIVE_ORDER_REFUSED', 'corr-test', 409));
    await _launchTicket(tester, execution);
    await _confirmSubmit(tester);
    expect(find.text('Order rejected by the server. Nothing was placed.'),
        findsOneWidget);
    expect(execution.lists, 1, reason: 'no re-read needed for a definite 4xx');
    expect(_review(tester).onPressed, isNotNull);
  });

  testWidgets('broker/network uncertainty: UNKNOWN is shown and blocks new orders',
      (tester) async {
    final execution = _Execution(submitError: StateError('connection reset'));
    await _launchTicket(tester, execution);
    await _confirmSubmit(tester);
    expect(execution.submits, 1);
    // The server's own record (SUBMITTING) drives the unresolved banner.
    expect(find.byKey(const ValueKey('room-live-unresolved')), findsOneWidget);
    expect(find.textContaining('Order outcome UNKNOWN'), findsOneWidget);
    expect(_review(tester).onPressed, isNull);
  });

  testWidgets('duplicate prevention: a disabled review never submits',
      (tester) async {
    final execution = _Execution(history: [
      order('u1', LiveEquityOrderStatus.unknown),
    ]);
    await _launchTicket(tester, execution);
    expect(_review(tester).onPressed, isNull);
    await tester.tap(find.byKey(const ValueKey('room-ticket-review')));
    await tester.pumpAndSettle();
    expect(execution.submits, 0);
  });

  testWidgets('UNKNOWN survives close and reopen, then reconciles to ACCEPTED',
      (tester) async {
    final execution = _Execution(
      history: [order('u1', LiveEquityOrderStatus.unknown)],
      reconcileResult: order('u1', LiveEquityOrderStatus.acknowledged),
    );
    await _launchTicket(tester, execution);
    expect(find.byKey(const ValueKey('room-live-unresolved')), findsOneWidget);
    expect(_review(tester).onPressed, isNull);

    await tester.tap(find.byKey(const ValueKey('room-live-reconcile-u1')));
    await tester.pumpAndSettle();
    expect(execution.reconciles, 1);
    expect(find.text('Accepted by broker · not yet filled.'), findsOneWidget);
    expect(find.byKey(const ValueKey('room-live-unresolved')), findsNothing);
    expect(_review(tester).onPressed, isNotNull);

    // Reopening reads the server again and finds nothing unresolved.
    await _closeAndReopen(tester);
    expect(find.byKey(const ValueKey('room-live-unresolved')), findsNothing);
    expect(_review(tester).onPressed, isNotNull);
  });

  testWidgets('still unresolved after reconcile stays blocked on reopen',
      (tester) async {
    final execution = _Execution(
      history: [order('u1', LiveEquityOrderStatus.submitting)],
      reconcileResult: order('u1', LiveEquityOrderStatus.unknown),
    );
    await _launchTicket(tester, execution);
    await tester.tap(find.byKey(const ValueKey('room-live-reconcile-u1')));
    await tester.pumpAndSettle();
    expect(find.text(
        'Still unresolved. The broker has not confirmed this order yet.'),
        findsOneWidget);
    expect(_review(tester).onPressed, isNull);

    await _closeAndReopen(tester);
    expect(find.byKey(const ValueKey('room-live-unresolved')), findsOneWidget);
    expect(_review(tester).onPressed, isNull);
  });

  testWidgets('a failed reconcile never unlocks orders', (tester) async {
    final execution = _Execution(
      history: [order('u1', LiveEquityOrderStatus.unknown)],
      reconcileResult: null,
    );
    await _launchTicket(tester, execution);
    await tester.tap(find.byKey(const ValueKey('room-live-reconcile-u1')));
    await tester.pumpAndSettle();
    expect(find.text('Reconciliation could not be verified. Orders stay blocked.'),
        findsOneWidget);
    expect(_review(tester).onPressed, isNull);
  });

  testWidgets('unverifiable history blocks new orders rather than looking empty',
      (tester) async {
    final execution = _Execution(listFails: true);
    await _launchTicket(tester, execution);
    expect(find.byKey(const ValueKey('room-live-history-unverified')),
        findsOneWidget);
    expect(_review(tester).onPressed, isNull);
  });

  testWidgets('a partial fill from history keeps its remainder unresolved-free',
      (tester) async {
    // PARTIALLY_FILLED is a confirmed state, not an unresolved one: it must not
    // block a new order on its own. Only SUBMITTING and UNKNOWN do.
    final execution = _Execution(
        history: [order('p1', LiveEquityOrderStatus.partiallyFilled, filled: 1)]);
    await _launchTicket(tester, execution);
    expect(find.byKey(const ValueKey('room-live-unresolved')), findsNothing);
    expect(_review(tester).onPressed, isNotNull);
  });
}

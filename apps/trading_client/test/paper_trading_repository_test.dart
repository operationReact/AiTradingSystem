import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:trading_client/src/data/authenticated_api_client.dart';
import 'package:trading_client/src/data/operator_session.dart';
import 'package:trading_client/src/data/paper_trading_repository.dart';

void main() {
  test('reads authoritative PAPER portfolio and positions', () async {
    final session = OperatorSession()..setOperatorKey('operator-key');
    final repository = HttpPaperTradingRepository(
      AuthenticatedApiClient(
        session: session,
        client: MockClient((request) async {
          expect(request.method, 'GET');
          expect(request.url.path, '/api/paper-portfolio');
          expect(request.headers['X-API-Key'], 'operator-key');
          return http.Response(
            jsonEncode({
              'mode': 'PAPER',
              'liveTradingEnabled': false,
              'newOrdersHalted': false,
              'startingCapital': 100000,
              'cash': 75000,
              'invested': 25000,
              'marketValue': 26000,
              'equity': 101000,
              'realizedPnl': 500,
              'unrealizedPnl': 1000,
              'realizedPnlToday': 100,
              'exposurePercent': 25.7,
              'openPositions': 1,
              'cashReconciled': true,
              'markPriceSource': 'PERSISTED_MARK',
              'positions': [
                {
                  'instrumentId': 42,
                  'exchange': 'NSE',
                  'symbol': 'INFY-EQ',
                  'productType': 'DELIVERY',
                  'quantity': 10,
                  'averageCost': 2500,
                  'costBasis': 25000,
                  'markPrice': 2600,
                  'marketValue': 26000,
                  'unrealizedPnl': 1000,
                  'stopLoss': 2400,
                  'target': 2800,
                  'openedAt': '2026-10-30T10:00:00Z',
                }
              ],
              'asOf': '2026-10-31T00:00:00Z',
            }),
            200,
          );
        }),
        baseUri: Uri.parse('http://example.test'),
      ),
    );

    final portfolio = await repository.fetchPortfolio();

    expect(portfolio.mode, 'PAPER');
    expect(portfolio.liveTradingEnabled, isFalse);
    expect(portfolio.cashReconciled, isTrue);
    expect(portfolio.positions.single.symbol, 'INFY-EQ');
    expect(portfolio.positions.single.unrealizedPnl, 1000);
  });

  test('lists PAPER orders using authoritative paging contract', () async {
    final session = OperatorSession()..setOperatorKey('operator-key');
    final repository = HttpPaperTradingRepository(
      AuthenticatedApiClient(
        session: session,
        client: MockClient((request) async {
          expect(request.method, 'GET');
          expect(request.url.path, '/api/paper-trades');
          expect(request.url.queryParameters, {'page': '0', 'size': '20'});
          return http.Response(
            jsonEncode({
              'items': [
                {
                  'id': '00000000-0000-0000-0000-000000000001',
                  'symbol': 'INFY-EQ',
                  'side': 'BUY',
                  'quantity': 10,
                  'filledQuantity': 10,
                  'orderType': 'MARKET',
                  'productType': 'DELIVERY',
                  'averageFillPrice': 2501,
                  'entryPrice': 2500,
                  'stopLoss': 2400,
                  'target': 2800,
                  'status': 'FILLED',
                  'executionOrigin': 'AUTOMATED',
                  'lifecycleReason': 'STRATEGY_SIGNAL',
                  'rejectionReason': null,
                  'createdAt': '2026-10-30T10:00:00Z',
                  'updatedAt': '2026-10-30T10:00:01Z',
                  'correlationId': 'corr-1',
                }
              ],
              'page': 0,
              'size': 20,
              'totalItems': 1,
              'totalPages': 1,
            }),
            200,
          );
        }),
        baseUri: Uri.parse('http://example.test'),
      ),
    );

    final page = await repository.fetchOrders();

    expect(page.totalItems, 1);
    expect(page.items.single.status, 'FILLED');
    expect(page.items.single.averageFillPrice, 2501);
    expect(page.items.single.executionOrigin, 'AUTOMATED');
    expect(page.items.single.lifecycleReason, 'STRATEGY_SIGNAL');
  });

  test('submits exact PAPER order payload with explicit idempotency header', () async {
    final session = OperatorSession()..setOperatorKey('operator-key');
    late http.Request captured;
    final repository = HttpPaperTradingRepository(
      AuthenticatedApiClient(
        session: session,
        client: MockClient((request) async {
          captured = request;
          return http.Response(
            jsonEncode({
              'order': {
                'id': '00000000-0000-0000-0000-000000000002',
                'symbol': 'INFY-EQ',
                'side': 'BUY',
                'quantity': 2,
                'filledQuantity': 0,
                'orderType': 'MARKET',
                'productType': 'DELIVERY',
                'averageFillPrice': null,
                'entryPrice': 2500,
                'stopLoss': 2400,
                'target': 2800,
                'status': 'RISK_APPROVED',
                'rejectionReason': null,
                'createdAt': '2026-10-31T00:00:00Z',
                'updatedAt': '2026-10-31T00:00:00Z',
                'correlationId': 'corr-2',
              },
              'decision': {
                'decision': 'APPROVE',
                'reasonCodes': ['WITHIN_POLICY'],
              },
              'fill': null,
              'replayed': false,
            }),
            201,
          );
        }),
        baseUri: Uri.parse('http://example.test'),
      ),
    );

    final result = await repository.createOrder(
      CreatePaperOrderCommand(
        symbol: 'INFY-EQ',
        side: 'BUY',
        quantity: 2,
        referencePrice: 2500,
        stopLoss: 2400,
        target: 2800,
        signalGeneratedAt: DateTime.parse('2026-10-31T00:00:00Z'),
        productType: 'DELIVERY',
      ),
      idempotencyKey: 'paper-test-1234',
    );

    expect(captured.method, 'POST');
    expect(captured.url.path, '/api/paper-orders');
    expect(captured.headers['X-API-Key'], 'operator-key');
    expect(captured.headers['Idempotency-Key'], 'paper-test-1234');
    expect(jsonDecode(captured.body), {
      'symbol': 'INFY-EQ',
      'side': 'BUY',
      'quantity': 2,
      'referencePrice': 2500,
      'stopLoss': 2400,
      'target': 2800,
      'signalGeneratedAt': '2026-10-31T00:00:00.000Z',
      'productType': 'DELIVERY',
      'orderType': 'MARKET',
      'limitPrice': null,
    });
    expect(result.order.status, 'RISK_APPROVED');
    expect(result.reasonCodes, ['WITHIN_POLICY']);
  });
}

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/data/execution_quality_presentation.dart';
import 'package:trading_client/src/data/paper_trading_repository.dart';
import 'package:trading_client/src/presentation/execution_quality_card.dart';

Map<String, dynamic> _qualityJson() => {
      'executionQualityVersion': 'execution-quality.v1',
      'state': 'FULLY_FILLED',
      'reasonCodes': <String>[],
      'requestedQuantity': 10,
      'filledQuantity': 10,
      'fillFraction': 1.0,
      'completionShortfallFraction': 0.0,
      'executionPlanFingerprint': '1' * 64,
      'fillResultFingerprint': '2' * 64,
      'feeScheduleFingerprint': '3' * 64,
      'costModelVersion': 'execution-cost-model.v1',
      'costModelFingerprint': '4' * 64,
      'expectedFeeTaxBps': 7.5,
      'expectedSlippageBps': 8.0,
      'expectedTotalCostBps': 15.5,
      'realizedAverageFillPrice': 2501.25,
      'realizedCost': {
        'totalFeeTaxBps': 7.7,
        'realizedSlippageBps': 10.0,
        'realizedTotalCostBps': 17.7,
      },
      'feeTaxDeltaBps': 0.2,
      'slippageDeltaBps': 2.0,
      'totalCostDeltaBps': 2.2,
      'arrivalGapBps': 3.0,
      'status': 'RESEARCH_EXECUTION_QUALITY_ONLY',
      'promotionAllowed': false,
      'canAuthorizeCapital': false,
    };

Map<String, dynamic> _orderResultJson({Object? executionQuality}) => {
      'order': {
        'id': 'order-v3-quality',
        'symbol': 'RELIANCE',
        'side': 'BUY',
        'quantity': 10,
        'filledQuantity': 10,
        'orderType': 'LIMIT',
        'productType': 'INTRADAY',
        'averageFillPrice': 2501.25,
        'entryPrice': 2500.0,
        'status': 'FILLED',
      },
      'fill': {
        'id': 'fill-v3-quality',
        'fillPrice': 2501.25,
        'grossAmount': 25012.50,
        'feeAmount': 18.75,
        'slippageAmount': 12.50,
        'netAmount': 25043.75,
        'costBasis': 25043.75,
        'realizedPnl': 0,
        'filledAt': '2026-11-03T04:31:00Z',
      },
      'executionQuality': executionQuality,
      'replayed': false,
    };

void main() {
  test('exact execution-quality.v1 evidence is preserved by PAPER detail parsing', () {
    final result = PaperOrderResult.fromJson(
      _orderResultJson(executionQuality: _qualityJson()),
    );

    final quality = result.executionQuality;
    expect(quality, isNotNull);
    expect(quality!.state, ExecutionQualityState.fullyFilled);
    expect(quality.costModelVersion, 'execution-cost-model.v1');
    expect(quality.expectedTotalCostBps, 15.5);
    expect(quality.realizedTotalCostBps, 17.7);
    expect(quality.totalCostDeltaBps, 2.2);
  });

  test('authority-bearing execution quality fails closed instead of reaching the UI', () {
    final unsafe = _qualityJson()..['canAuthorizeCapital'] = true;
    final result = PaperOrderResult.fromJson(
      _orderResultJson(executionQuality: unsafe),
    );

    expect(result.executionQuality, isNull);
  });

  test('internally inconsistent execution quality fails closed', () {
    final invalid = _qualityJson()
      ..['filledQuantity'] = 5
      ..['fillFraction'] = 1.0;
    final result = PaperOrderResult.fromJson(
      _orderResultJson(executionQuality: invalid),
    );

    expect(result.executionQuality, isNull);
  });

  testWidgets('exact V3 evidence renders expected-versus-realized TCA and cost model', (tester) async {
    final quality = ExecutionQualityPresentation.fromJson(_qualityJson());

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: ExecutionQualityCard(quality: quality),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('execution-quality-card')), findsOneWidget);
    expect(find.text('execution-cost-model.v1'), findsOneWidget);
    expect(find.text('15.50 bps'), findsOneWidget);
    expect(find.text('17.70 bps'), findsOneWidget);
    expect(find.text('+2.20 bps'), findsOneWidget);
    expect(find.textContaining('not available for this PAPER order'), findsNothing);
  });
}

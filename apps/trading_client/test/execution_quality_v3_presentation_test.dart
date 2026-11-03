import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/data/execution_quality_presentation.dart';
import 'package:trading_client/src/data/paper_trading_repository.dart';
import 'package:trading_client/src/presentation/execution_quality_card.dart';
import 'package:trading_client/src/presentation/execution_quality_panel.dart';

Map<String, dynamic> _filledQualityJson() => {
      'executionQualityVersion': 'execution-quality.v1',
      'state': 'FULLY_FILLED',
      'reasonCodes': <String>[],
      'side': 'BUY',
      'executionPlanFingerprint': 'a' * 64,
      'fillResultFingerprint': 'b' * 64,
      'feeScheduleFingerprint': 'c' * 64,
      'costModelVersion': 'execution-cost-model.v1',
      'costModelFingerprint': 'd' * 64,
      'requestedQuantity': 10,
      'filledQuantity': 10,
      'fillFraction': 1.0,
      'completionShortfallFraction': 0.0,
      'expectedFeeTaxBps': 5.0,
      'expectedSlippageBps': 7.0,
      'expectedTotalCostBps': 12.0,
      'realizedAverageFillPrice': 2501.25,
      'realizedCost': {
        'costModelVersion': 'execution-cost-model.v1',
        'feeTaxConfigVersion': 'india-fee-tax-config.v1',
        'slippageModelVersion': 'state-dependent-slippage.v1',
        'fillSimulatorVersion': 'fill-simulator.v1',
        'feeScheduleFingerprint': 'c' * 64,
        'totalFeeTaxBps': 5.2,
        'realizedSlippageBps': 9.0,
        'realizedTotalCostBps': 14.2,
      },
      'feeTaxDeltaBps': 0.2,
      'slippageDeltaBps': 2.0,
      'totalCostDeltaBps': 2.2,
      'arrivalGapBps': 1.5,
      'status': 'RESEARCH_EXECUTION_QUALITY_ONLY',
      'promotionAllowed': false,
      'canAuthorizeCapital': false,
    };

Map<String, dynamic> _unfilledQualityJson() => {
      ..._filledQualityJson(),
      'state': 'UNFILLED',
      'reasonCodes': ['NO_REALIZED_FILL'],
      'filledQuantity': 0,
      'fillFraction': 0.0,
      'completionShortfallFraction': 1.0,
      'realizedAverageFillPrice': null,
      'realizedCost': null,
      'feeTaxDeltaBps': null,
      'slippageDeltaBps': null,
      'totalCostDeltaBps': null,
      'arrivalGapBps': 1.5,
    };

PaperOrder _order() => PaperOrder(
      id: 'order-v3-quality',
      symbol: 'RELIANCE',
      side: 'BUY',
      quantity: 10,
      filledQuantity: 10,
      orderType: 'LIMIT',
      productType: 'INTRADAY',
      averageFillPrice: 2501.25,
      entryPrice: 2500,
      stopLoss: 2475,
      target: 2550,
      status: 'FILLED',
      rejectionReason: null,
      createdAt: DateTime.parse('2026-11-03T04:30:00Z'),
      updatedAt: DateTime.parse('2026-11-03T04:31:00Z'),
      correlationId: 'corr-v3-quality',
    );

PaperFill _legacyFill() => PaperFill(
      id: 'legacy-fill',
      fillPrice: 2501.25,
      grossAmount: 25012.50,
      feeAmount: 18.75,
      slippageAmount: 12.50,
      netAmount: 25043.75,
      costBasis: 25043.75,
      realizedPnl: 0,
      filledAt: DateTime.parse('2026-11-03T04:31:00Z'),
    );

void main() {
  test('strict parser accepts complete execution-quality.v1 evidence', () {
    final evidence = ExecutionQualityPresentation.fromJson(_filledQualityJson());

    expect(evidence.state, ExecutionQualityState.fullyFilled);
    expect(evidence.costModelVersion, 'execution-cost-model.v1');
    expect(evidence.expectedTotalCostBps, 12.0);
    expect(evidence.realizedTotalCostBps, 14.2);
    expect(evidence.totalCostDeltaBps, 2.2);
    expect(evidence.hasRealizedMetrics, isTrue);
  });

  test('unsafe or arithmetically tampered evidence fails closed', () {
    final unsafe = _filledQualityJson()..['promotionAllowed'] = true;
    expect(ExecutionQualityPresentation.tryFromJson(unsafe), isNull);

    final tampered = _filledQualityJson()..['expectedTotalCostBps'] = 11.0;
    expect(ExecutionQualityPresentation.tryFromJson(tampered), isNull);

    final wrongFeeLineage = _filledQualityJson();
    (wrongFeeLineage['realizedCost'] as Map<String, dynamic>)['feeScheduleFingerprint'] = 'e' * 64;
    expect(ExecutionQualityPresentation.tryFromJson(wrongFeeLineage), isNull);
  });

  test('PAPER result parser attaches optional exact V3 evidence only when valid', () {
    final parsed = PaperOrderResult.fromJson({
      'order': {
        'id': 'order-v3-quality',
        'symbol': 'RELIANCE',
        'side': 'BUY',
        'quantity': 10,
        'filledQuantity': 10,
        'orderType': 'LIMIT',
        'productType': 'INTRADAY',
        'averageFillPrice': 2501.25,
        'entryPrice': 2500,
        'status': 'FILLED',
      },
      'executionQuality': _filledQualityJson(),
      'replayed': false,
    });

    expect(parsed.executionQuality, isNotNull);
    expect(parsed.executionQuality!.totalCostDeltaBps, 2.2);

    final invalid = _filledQualityJson()..['canAuthorizeCapital'] = true;
    final rejected = PaperOrderResult.fromJson({
      'order': {'id': 'order-v3-quality'},
      'execution_quality': invalid,
      'replayed': false,
    });
    expect(rejected.executionQuality, isNull);
  });

  testWidgets('exact V3 card renders expected-versus-realized cost and lineage version', (tester) async {
    final evidence = ExecutionQualityPresentation.fromJson(_filledQualityJson());
    await tester.pumpWidget(
      MaterialApp(home: Scaffold(body: ExecutionQualityCard(quality: evidence))),
    );

    expect(find.text('Execution quality'), findsOneWidget);
    expect(find.text('execution-cost-model.v1'), findsOneWidget);
    expect(find.text('12.00 bps'), findsOneWidget);
    expect(find.text('14.20 bps'), findsOneWidget);
    expect(find.text('+2.20 bps'), findsOneWidget);
    expect(find.byKey(const ValueKey('execution-quality-unavailable')), findsNothing);
  });

  testWidgets('UNFILLED V3 evidence never renders realized-cost metrics', (tester) async {
    final evidence = ExecutionQualityPresentation.fromJson(_unfilledQualityJson());
    await tester.pumpWidget(
      MaterialApp(home: Scaffold(body: ExecutionQualityCard(quality: evidence))),
    );

    expect(find.text('Unfilled'), findsOneWidget);
    expect(find.byKey(const ValueKey('execution-quality-expected-total')), findsOneWidget);
    expect(find.byKey(const ValueKey('execution-quality-realized-total')), findsNothing);
    expect(find.byKey(const ValueKey('execution-quality-cost-delta')), findsNothing);
    expect(find.text('No Realized Fill'), findsOneWidget);
  });

  testWidgets('panel prefers exact V3 TCA over legacy persisted fill amounts', (tester) async {
    final detail = PaperOrderResult(
      order: _order(),
      replayed: false,
      reasonCodes: const [],
      fill: _legacyFill(),
      executionQuality: ExecutionQualityPresentation.fromJson(_filledQualityJson()),
    );

    await tester.pumpWidget(
      MaterialApp(home: Scaffold(body: ExecutionQualityPanel(detail: detail))),
    );

    expect(find.text('14.20 bps'), findsOneWidget);
    expect(find.text('₹18.75'), findsNothing);
    expect(find.textContaining('Expected-vs-realized TCA is not persisted'), findsNothing);
  });

  testWidgets('missing exact evidence renders an explicit unavailable message', (tester) async {
    await tester.pumpWidget(
      const MaterialApp(home: Scaffold(body: ExecutionQualityCard(quality: null))),
    );

    expect(find.byKey(const ValueKey('execution-quality-unavailable')), findsOneWidget);
    expect(find.textContaining('No expected-vs-realized metrics are inferred'), findsOneWidget);
  });
}

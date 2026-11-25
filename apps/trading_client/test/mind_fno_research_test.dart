import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/mintick_mind/data/mind_fno_research_repository.dart';
import 'package:trading_client/src/features/mintick_mind/presentation/mind_fno_research_panel.dart';

final _at = DateTime.utc(2026, 11, 20, 5);

Map<String, Object?> blocked({String reason = 'NIFTY_INDEX_CANDLES_UNAVAILABLE'}) => {
  'contractVersion': 'mind.fno-research.v1',
  'evaluatedAt': _at.toIso8601String(),
  'underlyingKey': 'NSE_INDEX|Nifty 50',
  'status': 'BLOCKED',
  'signal': 'NO_TRADE',
  'reasonCode': reason,
  'completedCandles': 0,
  'strategyId': null,
  'instrumentKey': null,
  'bid': null,
  'ask': null,
  'orderPlaced': false,
  'executable': false,
};

Map<String, Object?> candidate() => {
  ...blocked(),
  'status': 'RESEARCH_CANDIDATE',
  'signal': 'BUY_CALL_RESEARCH',
  'reasonCode': 'EMA8_CROSSED_ABOVE_EMA21',
  'completedCandles': 35,
  'strategyId': 'strategy.STR-181',
  'candleSource': 'ANGEL_ONE_SMARTAPI',
  'latestCompletedCloseAt': _at.subtract(const Duration(hours: 19)).toIso8601String(),
  'quoteSource': 'UPSTOX_V3_EXCHANGE_TIMED',
  'expiry': '2026-11-26',
  'strike': 25300,
  'instrumentKey': 'NSE_FO|61001',
  'lotSize': 65,
  'bid': 125,
  'ask': 126,
  'exchangeAt': _at.subtract(const Duration(seconds: 2)).toIso8601String(),
  'observedAt': _at.subtract(const Duration(seconds: 1)).toIso8601String(),
};

class _FakeResearch implements MindFnoResearchRepository {
  _FakeResearch(this.value);
  final MindFnoResearch value;
  @override
  Future<MindFnoResearch> evaluate() async => value;
}

void main() {
  test('source-backed long-call candidate is research only', () {
    final parsed = MindFnoResearch.parse(candidate());
    expect(parsed.status, 'RESEARCH_CANDIDATE');
    expect(parsed.strategyId, 'strategy.STR-181');
    expect(parsed.instrumentKey, 'NSE_FO|61001');
    expect(parsed.hasCandidate, isTrue);
  });

  test('data unavailable reports NO TRADE without a fabricated contract', () {
    final parsed = MindFnoResearch.parse(blocked());
    expect(parsed.hasCandidate, isFalse);
    expect(parsed.signal, 'NO_TRADE');
    expect(parsed.instrumentKey, isNull);
  });

  test('candidate with stale or synthetic quotes is rejected', () {
    final stale = candidate()
      ..['exchangeAt'] = _at.subtract(const Duration(minutes: 4)).toIso8601String();
    expect(() => MindFnoResearch.parse(stale), throwsFormatException);
    final fake = candidate()..['quoteSource'] = 'SYNTHETIC';
    expect(() => MindFnoResearch.parse(fake), throwsFormatException);
  });

  test('strategy mismatch or execution authority is rejected', () {
    final wrong = candidate()..['strategyId'] = 'strategy.STR-182';
    expect(() => MindFnoResearch.parse(wrong), throwsFormatException);
    final live = candidate()..['executable'] = true;
    expect(() => MindFnoResearch.parse(live), throwsFormatException);
    final illegal = blocked()..['signal'] = 'BUY_CALL_RESEARCH';
    expect(() => MindFnoResearch.parse(illegal), throwsFormatException);
  });

  testWidgets('F&O Mind panel shows real decision status with no trade button',
      (tester) async {
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(body: SingleChildScrollView(
        child: MindFnoResearchPanel(
          repository: _FakeResearch(MindFnoResearch.parse(candidate())),
        ),
      )),
    ));
    await tester.pumpAndSettle();
    expect(find.textContaining('LONG CALL'), findsOneWidget);
    expect(find.byKey(const ValueKey('mind-fno-research-only')), findsOneWidget);
    expect(find.textContaining('NSE_FO|61001'), findsOneWidget);
    expect(find.text('Place Order'), findsNothing);
  });

  testWidgets('F&O Mind panel explicitly abstains when source blocked',
      (tester) async {
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(body: MindFnoResearchPanel(
        repository: _FakeResearch(MindFnoResearch.parse(blocked())),
      )),
    ));
    await tester.pumpAndSettle();
    expect(find.textContaining('NO TRADE'), findsOneWidget);
    expect(find.textContaining('NIFTY INDEX CANDLES UNAVAILABLE'), findsOneWidget);
    expect(find.text('Place Order'), findsNothing);
  });
}

import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/upstox_analytics/fno_readiness_scorecard.dart';

void main() {
  test('dated planning review totals 55/100, never asserts PAPER readiness', () {
    final review = FnoReadinessScorecard.reviewedNovember14();
    expect(review.isValid, isTrue);
    expect(review.totalPoints, 100);
    expect(review.earnedPoints, 55);
    expect(review.paperTradable, isFalse);
    expect(review.label, contains('not runtime acceptance'));
  });

  test('rights and actual end-to-end fills remain unverified', () {
    final gates = FnoReadinessScorecard.reviewedNovember14().gates;
    for (final id in ['rights', 'end-to-end']) {
      final gate = gates.singleWhere((gate) => gate.id == id);
      expect(gate.earned, 0);
      expect(gate.state, FnoEvidenceState.notVerified);
    }
    expect(gates.singleWhere((gate) => gate.id == 'source-chain').earned, 15);
    expect(gates.singleWhere((gate) => gate.id == 'v3-stream').earned, 4);
  });
}

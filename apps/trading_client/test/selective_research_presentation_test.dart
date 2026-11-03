import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/data/research_repository.dart';
import 'package:trading_client/src/data/selective_research_presentation.dart';

void main() {
  Map<String, dynamic> candidatePresentation() => {
        'contract_version': 'selective-presentation.v1',
        'state': 'CANDIDATE',
        'calibrated_probability': 0.82,
        'probability_semantics': 'CALIBRATED_EVENT_PROBABILITY',
        'uncertainty_score': 0.20,
        'uncertainty_level': 'LOW',
        'evidence_quality': 'COMPLETE',
        'evaluation_assessment': 'QUALITY_PRESERVED_OR_IMPROVED',
        'reason_codes': ['CALIBRATED_PROBABILITY_EVIDENCE_ELIGIBLE'],
        'can_authorize_capital': false,
      };

  test('labels calibrated probability separately from uncertainty', () {
    final presentation = SelectiveResearchPresentation.fromJson(candidatePresentation());

    expect(presentation.probabilityLabel, 'Calibrated probability 82.0%');
    expect(presentation.uncertaintyLabel, 'Uncertainty LOW 20.0%');
    expect(presentation.isAbstain, isFalse);
  });

  test('ABSTAIN withholds probability instead of inventing confidence', () {
    final presentation = SelectiveResearchPresentation.fromJson({
      ...candidatePresentation(),
      'state': 'ABSTAIN',
      'calibrated_probability': null,
      'probability_semantics': 'WITHHELD',
      'evaluation_assessment': 'INSUFFICIENT_EVIDENCE',
      'reason_codes': ['SELECTIVE_EVALUATION_INSUFFICIENT_EVIDENCE'],
    });

    expect(presentation.probabilityLabel, 'ABSTAIN · calibrated probability withheld');
    expect(presentation.calibratedProbability, isNull);
    expect(presentation.isAbstain, isTrue);
  });

  test('rejects ABSTAIN payload that leaks a probability', () {
    expect(
      () => SelectiveResearchPresentation.fromJson({
        ...candidatePresentation(),
        'state': 'ABSTAIN',
        'probability_semantics': 'WITHHELD',
      }),
      throwsFormatException,
    );
  });

  test('rejects raw or generic confidence semantics for a candidate', () {
    expect(
      () => SelectiveResearchPresentation.fromJson({
        ...candidatePresentation(),
        'probability_semantics': 'MODEL_CONFIDENCE',
      }),
      throwsFormatException,
    );
  });

  test('rejects research payload that attempts capital authority', () {
    expect(
      () => SelectiveResearchPresentation.fromJson({
        ...candidatePresentation(),
        'can_authorize_capital': true,
      }),
      throwsFormatException,
    );
  });

  test('research candidate accepts optional selective presentation contract', () {
    final candidate = ResearchCandidate.fromJson({
      'candidateId': 7,
      'scanRunId': 'scan-1',
      'instrumentId': 1001,
      'tradingSymbol': 'RELIANCE-EQ',
      'exchange': 'NSE',
      'interval': 'FIVE_MINUTE',
      'strategy': 'V3',
      'score': 0.74,
      'referencePrice': 100,
      'suggestedStop': 98,
      'suggestedTarget': 104,
      'candleTime': '2026-01-12T06:00:00Z',
      'reasons': ['fixture'],
      'candidateFingerprint': 'a' * 64,
      'selectivePresentation': candidatePresentation(),
    });

    expect(candidate.selectivePresentation, isNotNull);
    expect(candidate.selectivePresentation!.probabilityLabel, 'Calibrated probability 82.0%');
    expect(candidate.selectivePresentation!.uncertaintyLabel, 'Uncertainty LOW 20.0%');
  });
}

class SelectiveResearchPresentation {
  const SelectiveResearchPresentation({
    required this.contractVersion,
    required this.state,
    required this.calibratedProbability,
    required this.probabilitySemantics,
    required this.uncertaintyScore,
    required this.uncertaintyLevel,
    required this.evidenceQuality,
    required this.evaluationAssessment,
    required this.reasonCodes,
    required this.canAuthorizeCapital,
  });

  final String contractVersion;
  final String state;
  final double? calibratedProbability;
  final String probabilitySemantics;
  final double uncertaintyScore;
  final String uncertaintyLevel;
  final String evidenceQuality;
  final String evaluationAssessment;
  final List<String> reasonCodes;
  final bool canAuthorizeCapital;

  factory SelectiveResearchPresentation.fromJson(Map<String, dynamic> json) {
    final state = '${json['state'] ?? ''}';
    final probabilitySemantics = '${json['probability_semantics'] ?? json['probabilitySemantics'] ?? ''}';
    final probability = (json['calibrated_probability'] ?? json['calibratedProbability']) as num?;
    final uncertainty = (json['uncertainty_score'] ?? json['uncertaintyScore']) as num?;
    final canAuthorizeCapital = (json['can_authorize_capital'] ?? json['canAuthorizeCapital']) == true;

    if (json['contract_version'] != 'selective-presentation.v1' &&
        json['contractVersion'] != 'selective-presentation.v1') {
      throw const FormatException('Unsupported selective presentation contract');
    }
    if (state != 'CANDIDATE' && state != 'ABSTAIN') {
      throw const FormatException('Unsupported selective presentation state');
    }
    if (canAuthorizeCapital) {
      throw const FormatException('Research presentation cannot authorize capital');
    }
    if (uncertainty == null || uncertainty < 0 || uncertainty > 1) {
      throw const FormatException('Invalid uncertainty score');
    }
    if (state == 'CANDIDATE') {
      if (probability == null || probability < 0 || probability > 1) {
        throw const FormatException('Candidate requires bounded calibrated probability');
      }
      if (probabilitySemantics != 'CALIBRATED_EVENT_PROBABILITY') {
        throw const FormatException('Candidate probability must be explicitly calibrated');
      }
    } else {
      if (probability != null || probabilitySemantics != 'WITHHELD') {
        throw const FormatException('ABSTAIN must withhold probability');
      }
    }

    return SelectiveResearchPresentation(
      contractVersion: 'selective-presentation.v1',
      state: state,
      calibratedProbability: probability?.toDouble(),
      probabilitySemantics: probabilitySemantics,
      uncertaintyScore: uncertainty.toDouble(),
      uncertaintyLevel: '${json['uncertainty_level'] ?? json['uncertaintyLevel'] ?? 'UNKNOWN'}',
      evidenceQuality: '${json['evidence_quality'] ?? json['evidenceQuality'] ?? 'UNKNOWN'}',
      evaluationAssessment: '${json['evaluation_assessment'] ?? json['evaluationAssessment'] ?? 'UNKNOWN'}',
      reasonCodes: (json['reason_codes'] ?? json['reasonCodes'] as List<dynamic>? ?? const <dynamic>[])
          .map((value) => '$value')
          .toList(growable: false),
      canAuthorizeCapital: false,
    );
  }

  bool get isAbstain => state == 'ABSTAIN';

  String get probabilityLabel => isAbstain
      ? 'ABSTAIN · calibrated probability withheld'
      : 'Calibrated probability ${((calibratedProbability ?? 0) * 100).toStringAsFixed(1)}%';

  String get uncertaintyLabel =>
      'Uncertainty $uncertaintyLevel ${(uncertaintyScore * 100).toStringAsFixed(1)}%';
}

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
    final contractVersion = '${json['contract_version'] ?? json['contractVersion'] ?? ''}';
    final state = '${json['state'] ?? ''}';
    final probabilitySemantics = '${json['probability_semantics'] ?? json['probabilitySemantics'] ?? ''}';
    final probability = (json['calibrated_probability'] ?? json['calibratedProbability']) as num?;
    final uncertainty = (json['uncertainty_score'] ?? json['uncertaintyScore']) as num?;
    final uncertaintyLevel = '${json['uncertainty_level'] ?? json['uncertaintyLevel'] ?? ''}';
    final evidenceQuality = '${json['evidence_quality'] ?? json['evidenceQuality'] ?? ''}';
    final evaluationAssessment = '${json['evaluation_assessment'] ?? json['evaluationAssessment'] ?? ''}';
    final rawReasonCodes = json['reason_codes'] ?? json['reasonCodes'];
    final canAuthorizeCapital = (json['can_authorize_capital'] ?? json['canAuthorizeCapital']) == true;

    if (contractVersion != 'selective-presentation.v1') {
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
    if (!const {'LOW', 'MEDIUM', 'HIGH'}.contains(uncertaintyLevel)) {
      throw const FormatException('Unsupported uncertainty level');
    }
    if (!const {'COMPLETE', 'DEGRADED', 'INSUFFICIENT'}.contains(evidenceQuality)) {
      throw const FormatException('Unsupported evidence quality');
    }
    if (!const {
      'QUALITY_PRESERVED_OR_IMPROVED',
      'QUALITY_REGRESSED',
      'INSUFFICIENT_EVIDENCE',
    }.contains(evaluationAssessment)) {
      throw const FormatException('Unsupported selective evaluation assessment');
    }
    if (state == 'CANDIDATE') {
      if (probability == null || probability < 0 || probability > 1) {
        throw const FormatException('Candidate requires bounded calibrated probability');
      }
      if (probabilitySemantics != 'CALIBRATED_EVENT_PROBABILITY') {
        throw const FormatException('Candidate probability must be explicitly calibrated');
      }
      if (evidenceQuality != 'COMPLETE' || evaluationAssessment != 'QUALITY_PRESERVED_OR_IMPROVED') {
        throw const FormatException('Candidate requires complete preserved-quality evidence');
      }
    } else {
      if (probability != null || probabilitySemantics != 'WITHHELD') {
        throw const FormatException('ABSTAIN must withhold probability');
      }
    }

    return SelectiveResearchPresentation(
      contractVersion: contractVersion,
      state: state,
      calibratedProbability: probability?.toDouble(),
      probabilitySemantics: probabilitySemantics,
      uncertaintyScore: uncertainty.toDouble(),
      uncertaintyLevel: uncertaintyLevel,
      evidenceQuality: evidenceQuality,
      evaluationAssessment: evaluationAssessment,
      reasonCodes: rawReasonCodes is List
          ? rawReasonCodes.map((value) => '$value').toList(growable: false)
          : const <String>[],
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

enum ExecutionQualityState {
  fullyFilled,
  partiallyFilled,
  unfilled,
  inconclusive,
}

class ExecutionQualityPresentation {
  const ExecutionQualityPresentation({
    required this.state,
    required this.reasonCodes,
    required this.requestedQuantity,
    required this.filledQuantity,
    required this.fillFraction,
    required this.completionShortfallFraction,
    required this.executionPlanFingerprint,
    required this.fillResultFingerprint,
    required this.feeScheduleFingerprint,
    required this.costModelVersion,
    required this.costModelFingerprint,
    required this.expectedFeeTaxBps,
    required this.expectedSlippageBps,
    required this.expectedTotalCostBps,
    required this.realizedAverageFillPrice,
    required this.realizedFeeTaxBps,
    required this.realizedSlippageBps,
    required this.realizedTotalCostBps,
    required this.feeTaxDeltaBps,
    required this.slippageDeltaBps,
    required this.totalCostDeltaBps,
    required this.arrivalGapBps,
  });

  final ExecutionQualityState state;
  final List<String> reasonCodes;
  final int requestedQuantity;
  final int filledQuantity;
  final double fillFraction;
  final double completionShortfallFraction;
  final String executionPlanFingerprint;
  final String fillResultFingerprint;
  final String feeScheduleFingerprint;
  final String? costModelVersion;
  final String? costModelFingerprint;
  final double? expectedFeeTaxBps;
  final double? expectedSlippageBps;
  final double? expectedTotalCostBps;
  final double? realizedAverageFillPrice;
  final double? realizedFeeTaxBps;
  final double? realizedSlippageBps;
  final double? realizedTotalCostBps;
  final double? feeTaxDeltaBps;
  final double? slippageDeltaBps;
  final double? totalCostDeltaBps;
  final double? arrivalGapBps;

  bool get hasRealizedMetrics =>
      state == ExecutionQualityState.fullyFilled ||
      state == ExecutionQualityState.partiallyFilled;

  bool get hasExpectedMetrics => expectedTotalCostBps != null;

  String get stateLabel => switch (state) {
        ExecutionQualityState.fullyFilled => 'Fully filled',
        ExecutionQualityState.partiallyFilled => 'Partially filled',
        ExecutionQualityState.unfilled => 'Unfilled',
        ExecutionQualityState.inconclusive => 'Inconclusive',
      };

  factory ExecutionQualityPresentation.fromJson(Map<String, dynamic> json) {
    final version = _string(json, 'executionQualityVersion', 'execution_quality_version');
    final status = _string(json, 'status');
    if (version != 'execution-quality.v1') {
      throw const FormatException('Unsupported execution-quality version');
    }
    if (status != 'RESEARCH_EXECUTION_QUALITY_ONLY') {
      throw const FormatException('Unsupported execution-quality status');
    }
    if (_bool(json, 'promotionAllowed', 'promotion_allowed') != false ||
        _bool(json, 'canAuthorizeCapital', 'can_authorize_capital') != false) {
      throw const FormatException('Execution quality cannot carry promotion or capital authority');
    }

    final state = _parseState(_string(json, 'state'));
    final requestedQuantity = _requiredInt(json, 'requestedQuantity', 'requested_quantity');
    final filledQuantity = _requiredInt(json, 'filledQuantity', 'filled_quantity');
    final fillFraction = _requiredDouble(json, 'fillFraction', 'fill_fraction');
    final shortfall = _requiredDouble(
      json,
      'completionShortfallFraction',
      'completion_shortfall_fraction',
    );
    if (requestedQuantity < 1 || filledQuantity < 0 || filledQuantity > requestedQuantity) {
      throw const FormatException('Invalid execution-quality quantities');
    }
    final expectedFillFraction = filledQuantity / requestedQuantity;
    if ((fillFraction - expectedFillFraction).abs() > 1e-9 ||
        (shortfall - (1.0 - fillFraction)).abs() > 1e-9 ||
        fillFraction < 0 ||
        fillFraction > 1 ||
        shortfall < 0 ||
        shortfall > 1) {
      throw const FormatException('Invalid execution-quality fill fractions');
    }

    final expectedFeeTax = _optionalDouble(json, 'expectedFeeTaxBps', 'expected_fee_tax_bps');
    final expectedSlippage = _optionalDouble(json, 'expectedSlippageBps', 'expected_slippage_bps');
    final expectedTotal = _optionalDouble(json, 'expectedTotalCostBps', 'expected_total_cost_bps');
    final realizedCost = _map(json, 'realizedCost', 'realized_cost');
    final realizedFill = _optionalDouble(
      json,
      'realizedAverageFillPrice',
      'realized_average_fill_price',
    );
    final realizedFeeTax = realizedCost == null
        ? null
        : _optionalDouble(realizedCost, 'totalFeeTaxBps', 'total_fee_tax_bps');
    final realizedSlippage = realizedCost == null
        ? null
        : _optionalDouble(realizedCost, 'realizedSlippageBps', 'realized_slippage_bps');
    final realizedTotal = realizedCost == null
        ? null
        : _optionalDouble(realizedCost, 'realizedTotalCostBps', 'realized_total_cost_bps');
    final feeDelta = _optionalDouble(json, 'feeTaxDeltaBps', 'fee_tax_delta_bps');
    final slippageDelta = _optionalDouble(json, 'slippageDeltaBps', 'slippage_delta_bps');
    final totalDelta = _optionalDouble(json, 'totalCostDeltaBps', 'total_cost_delta_bps');

    final expectedMetrics = [expectedFeeTax, expectedSlippage, expectedTotal];
    final realizedMetrics = [
      realizedFill,
      realizedFeeTax,
      realizedSlippage,
      realizedTotal,
      feeDelta,
      slippageDelta,
      totalDelta,
    ];
    switch (state) {
      case ExecutionQualityState.fullyFilled:
      case ExecutionQualityState.partiallyFilled:
        if (expectedMetrics.any((value) => value == null) ||
            realizedMetrics.any((value) => value == null)) {
          throw const FormatException('Filled execution quality requires complete metrics');
        }
      case ExecutionQualityState.unfilled:
        if (expectedMetrics.any((value) => value == null) ||
            realizedMetrics.any((value) => value != null)) {
          throw const FormatException('Unfilled execution quality has invalid metrics');
        }
      case ExecutionQualityState.inconclusive:
        if (realizedMetrics.any((value) => value != null)) {
          throw const FormatException('Inconclusive execution quality cannot expose realized metrics');
        }
    }

    return ExecutionQualityPresentation(
      state: state,
      reasonCodes: _stringList(json, 'reasonCodes', 'reason_codes'),
      requestedQuantity: requestedQuantity,
      filledQuantity: filledQuantity,
      fillFraction: fillFraction,
      completionShortfallFraction: shortfall,
      executionPlanFingerprint: _requiredFingerprint(
        json,
        'executionPlanFingerprint',
        'execution_plan_fingerprint',
      ),
      fillResultFingerprint: _requiredFingerprint(
        json,
        'fillResultFingerprint',
        'fill_result_fingerprint',
      ),
      feeScheduleFingerprint: _requiredFingerprint(
        json,
        'feeScheduleFingerprint',
        'fee_schedule_fingerprint',
      ),
      costModelVersion: _nullableString(json, 'costModelVersion', 'cost_model_version'),
      costModelFingerprint: _nullableFingerprint(
        json,
        'costModelFingerprint',
        'cost_model_fingerprint',
      ),
      expectedFeeTaxBps: expectedFeeTax,
      expectedSlippageBps: expectedSlippage,
      expectedTotalCostBps: expectedTotal,
      realizedAverageFillPrice: realizedFill,
      realizedFeeTaxBps: realizedFeeTax,
      realizedSlippageBps: realizedSlippage,
      realizedTotalCostBps: realizedTotal,
      feeTaxDeltaBps: feeDelta,
      slippageDeltaBps: slippageDelta,
      totalCostDeltaBps: totalDelta,
      arrivalGapBps: _optionalDouble(json, 'arrivalGapBps', 'arrival_gap_bps'),
    );
  }

  static ExecutionQualityPresentation? tryFromJson(dynamic value) {
    if (value is! Map) return null;
    try {
      return ExecutionQualityPresentation.fromJson(Map<String, dynamic>.from(value));
    } on FormatException {
      return null;
    } on TypeError {
      return null;
    }
  }
}

ExecutionQualityState _parseState(String value) => switch (value) {
      'FULLY_FILLED' => ExecutionQualityState.fullyFilled,
      'PARTIALLY_FILLED' => ExecutionQualityState.partiallyFilled,
      'UNFILLED' => ExecutionQualityState.unfilled,
      'INCONCLUSIVE' => ExecutionQualityState.inconclusive,
      _ => throw const FormatException('Unsupported execution-quality state'),
    };

String _requiredFingerprint(Map<String, dynamic> json, String camel, String snake) {
  final value = _string(json, camel, snake);
  if (!RegExp(r'^[a-f0-9]{64}$').hasMatch(value)) {
    throw const FormatException('Invalid execution-quality fingerprint');
  }
  return value;
}

String? _nullableFingerprint(Map<String, dynamic> json, String camel, String snake) {
  final value = _nullableString(json, camel, snake);
  if (value == null) return null;
  if (!RegExp(r'^[a-f0-9]{64}$').hasMatch(value)) {
    throw const FormatException('Invalid execution-quality fingerprint');
  }
  return value;
}

String _string(Map<String, dynamic> json, String first, [String? second]) {
  final value = _value(json, first, second);
  if (value is! String || value.isEmpty) {
    throw const FormatException('Missing execution-quality string');
  }
  return value;
}

String? _nullableString(Map<String, dynamic> json, String first, [String? second]) {
  final value = _value(json, first, second);
  if (value == null) return null;
  if (value is! String || value.isEmpty) {
    throw const FormatException('Invalid execution-quality string');
  }
  return value;
}

bool? _bool(Map<String, dynamic> json, String first, [String? second]) {
  final value = _value(json, first, second);
  return value is bool ? value : null;
}

int _requiredInt(Map<String, dynamic> json, String first, [String? second]) {
  final value = _value(json, first, second);
  if (value is! num || !value.isFinite || value != value.roundToDouble()) {
    throw const FormatException('Invalid execution-quality integer');
  }
  return value.toInt();
}

double _requiredDouble(Map<String, dynamic> json, String first, [String? second]) {
  final value = _optionalDouble(json, first, second);
  if (value == null) throw const FormatException('Missing execution-quality number');
  return value;
}

double? _optionalDouble(Map<String, dynamic> json, String first, [String? second]) {
  final value = _value(json, first, second);
  if (value == null) return null;
  if (value is! num || !value.isFinite) {
    throw const FormatException('Invalid execution-quality number');
  }
  return value.toDouble();
}

Map<String, dynamic>? _map(Map<String, dynamic> json, String first, [String? second]) {
  final value = _value(json, first, second);
  if (value == null) return null;
  if (value is! Map) throw const FormatException('Invalid execution-quality object');
  return Map<String, dynamic>.from(value);
}

List<String> _stringList(Map<String, dynamic> json, String first, [String? second]) {
  final value = _value(json, first, second);
  if (value == null) return const [];
  if (value is! List) throw const FormatException('Invalid execution-quality reason codes');
  final result = <String>[];
  for (final item in value) {
    if (item is! String || item.isEmpty) {
      throw const FormatException('Invalid execution-quality reason code');
    }
    result.add(item);
  }
  return List.unmodifiable(result);
}

dynamic _value(Map<String, dynamic> json, String first, [String? second]) {
  if (json.containsKey(first)) return json[first];
  if (second != null && json.containsKey(second)) return json[second];
  return null;
}

import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:trading_client/src/data/ai_scanner_repository.dart';
import 'package:trading_client/src/data/authenticated_api_client.dart';
import 'package:trading_client/src/data/operator_session.dart';

void main() {
  test('parses successful zero-eligible scanner snapshot', () async {
    final repository = _repository((request) async {
      expect(request.method, 'GET');
      expect(request.url.path, '/api/ai/scanner');
      expect(request.url.queryParameters['sort'], 'EXPECTED_NET_EDGE');
      expect(request.url.queryParameters['limit'], '50');
      return http.Response(
        jsonEncode({
          'contract_version': 'ai-scanner-snapshot.v1',
          'generated_at': '2026-11-03T04:02:00Z',
          'as_of': null,
          'universe_id': null,
          'universe_version': null,
          'source_status': 'NO_RANKED_DATA',
          'source_count': 0,
          'filtered_count': 0,
          'eligible_count': 0,
          'zero_eligible_trades': true,
          'opportunities': <Object>[],
          'can_authorize_capital': false,
        }),
        200,
      );
    });

    final snapshot = await repository.scan();

    expect(snapshot.sourceStatus, AiScannerSourceStatus.noRankedData);
    expect(snapshot.zeroEligibleTrades, isTrue);
    expect(snapshot.eligibleCount, 0);
    expect(snapshot.opportunities, isEmpty);
    expect(snapshot.asOf, isNull);
    expect(snapshot.canAuthorizeCapital, isFalse);
  });

  test('sends explicit filters and preserves abstain probability withholding', () async {
    final repository = _repository((request) async {
      expect(request.url.queryParameters['state'], 'ABSTAIN');
      expect(request.url.queryParameters['sector'], 'Technology');
      expect(request.url.queryParameters['uncertainty'], 'HIGH');
      expect(request.url.queryParameters['minRegimeFit'], '0.4');
      expect(request.url.queryParameters['minLiquidityPercentile'], '0.6');
      expect(request.url.queryParameters['sort'], 'LIQUIDITY');
      expect(request.url.queryParameters['limit'], '10');
      return http.Response(jsonEncode(_abstainSnapshot()), 200);
    });

    final snapshot = await repository.scan(
      query: const AiScannerQuery(
        state: AiScannerState.abstain,
        sector: 'Technology',
        uncertainty: AiScannerUncertainty.high,
        minRegimeFit: 0.4,
        minLiquidityPercentile: 0.6,
        sort: AiScannerSort.liquidity,
        limit: 10,
      ),
    );

    final opportunity = snapshot.opportunities.single;
    expect(opportunity.state, AiScannerState.abstain);
    expect(opportunity.calibratedProbability, isNull);
    expect(opportunity.expectedNetEdgeBps, -5.0);
    expect(opportunity.uncertaintyLevel, 'HIGH');
    expect(opportunity.timeToExpirySeconds, 180);
    expect(opportunity.reasonCodes, ['UNCERTAINTY_TOO_HIGH']);
    expect(opportunity.canAuthorizeCapital, isFalse);
    expect(snapshot.zeroEligibleTrades, isTrue);
  });

  test('fails closed when abstain exposes calibrated probability', () {
    final json = _abstainSnapshot();
    final opportunity =
        (json['opportunities'] as List<dynamic>).single as Map<String, dynamic>;
    opportunity['calibrated_probability'] = 0.55;

    expect(
      () => AiScannerSnapshot.fromJson(json),
      throwsA(isA<FormatException>()),
    );
  });

  test('fails closed when API claims capital authority', () {
    final json = _abstainSnapshot()..['can_authorize_capital'] = true;

    expect(
      () => AiScannerSnapshot.fromJson(json),
      throwsA(isA<FormatException>()),
    );
  });

  test('query validates client-side bounds before making a request', () {
    expect(
      () => const AiScannerQuery(limit: 0).toQueryParameters(),
      throwsA(isA<ArgumentError>()),
    );
    expect(
      () => const AiScannerQuery(minRegimeFit: 1.1).toQueryParameters(),
      throwsA(isA<ArgumentError>()),
    );
    expect(
      () => const AiScannerQuery(sector: '   ').toQueryParameters(),
      throwsA(isA<ArgumentError>()),
    );
  });
}

HttpAiScannerRepository _repository(
  Future<http.Response> Function(http.Request request) handler,
) {
  final session = OperatorSession()..setOperatorKey('operator-key');
  return HttpAiScannerRepository(
    AuthenticatedApiClient(
      session: session,
      client: MockClient(handler),
      baseUri: Uri.parse('http://example.test'),
    ),
  );
}

Map<String, dynamic> _abstainSnapshot() => {
      'contract_version': 'ai-scanner-snapshot.v1',
      'generated_at': '2026-11-03T04:02:00Z',
      'as_of': '2026-11-03T04:00:00Z',
      'universe_id': 'INDIA_NSE_NIFTY500_LIQUID',
      'universe_version': 'a' * 64,
      'source_status': 'READY',
      'source_count': 1,
      'filtered_count': 1,
      'eligible_count': 0,
      'zero_eligible_trades': true,
      'opportunities': [
        {
          'instrument_id': 'NSE:INE001A01010',
          'symbol': 'AAA',
          'state': 'ABSTAIN',
          'direction': 'WATCH',
          'rank': 2,
          'percentile': 0.75,
          'sector_rank': 1,
          'liquidity_rank': 2,
          'sector': 'Technology',
          'calibrated_probability': null,
          'expected_net_edge_bps': -5.0,
          'estimated_round_trip_cost_bps': 10.0,
          'uncertainty_score': 0.7,
          'uncertainty_level': 'HIGH',
          'disagreement_score': 0.6,
          'regime': 'CHOPPY',
          'regime_fit': 0.4,
          'adv_notional': 15000000.0,
          'liquidity_percentile': 0.6,
          'valid_until': '2026-11-03T04:05:00Z',
          'time_to_expiry_seconds': 180,
          'risk_reward_inputs': {
            'upper_barrier_bps': 50.0,
            'lower_barrier_bps': 25.0,
            'horizon_bars': 12,
            'reward_risk_ratio': 2.0,
          },
          'reason_codes': ['UNCERTAINTY_TOO_HIGH'],
          'invalidation_conditions': ['UNCERTAINTY_NORMALIZED'],
          'opportunity_fingerprint': 'e' * 64,
          'can_authorize_capital': false,
        }
      ],
      'can_authorize_capital': false,
    };

import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:trading_client/src/auth/customer_identity.dart';
import 'package:trading_client/src/data/authenticated_api_client.dart';
import 'package:trading_client/src/data/operator_session.dart';
import 'package:trading_client/src/features/mintick_mind/data/mind_evidence_repository.dart';

class _TestIdentity extends UnavailableCustomerIdentity {
  const _TestIdentity();
  @override
  bool get isConfigured => true;
  @override
  Future<String?> accessToken() async => 'test-token';
}

Map<String, dynamic> _payload() => {
  'contractVersion': 'mind.evidence.v1',
  'generatedAt': '2026-11-09T09:00:00Z',
  'sourceStatus': 'RECORDED',
  'scanRunId': '6f5b64fb-6f86-4efd-8acc-6b37713bd51c',
  'scanCompletedAt': '2026-11-09T08:58:00Z',
  'source': 'PERSISTED_RESEARCH_SCAN',
  'entries': [{
    'id': 'candidate-7',
    'kind': 'RESEARCH_CANDIDATE',
    'source': 'PERSISTED_RESEARCH_SCAN_CANDIDATE',
    'instrument': 'NSE:INFY-EQ',
    'strategy': 'MA_RECLAIM',
    'referenceCode': 'SCAN_CANDIDATE',
    'observedAt': '2026-11-09T08:55:00Z',
    'dataCutoffAt': '2026-11-09T08:56:00Z',
    'fingerprint': 'candidate-fingerprint',
    'usability': 'RESEARCH_ONLY',
    'knownAtStatus': 'NOT_INDEPENDENTLY_VERIFIED',
    'executable': false,
  }],
  'blockingReasons': ['NO_VERIFIED_DECISION_EVIDENCE'],
  'rejectedCandidates': 0,
  'decisionPosture': 'ABSTAIN',
  'verifiedSignal': false,
  'executionEnabled': false,
};

void main() {
  test('Cognito GET only, bounded evidence query and no broker access', () async {
    final original = MintTickIdentity.current;
    MintTickIdentity.current = const _TestIdentity();
    addTearDown(() => MintTickIdentity.current = original);
    final session = OperatorSession()..markCustomerAuthenticated();
    final client = MockClient((request) async {
      expect(request.method, 'GET');
      expect(request.url.path, '/api/customer/mind/v1/evidence');
      expect(request.url.queryParameters, {'limit': '20'});
      expect(request.headers['Authorization'], 'Bearer test-token');
      expect(request.headers.containsKey('X-API-Key'), isFalse);
      return http.Response(jsonEncode(_payload()), 200);
    });

    final repo = HttpMindEvidenceRepository(
      AuthenticatedApiClient(
        session: session, client: client,
        baseUri: Uri.parse('https://api.minttick.com')),
    );
    final result = await repo.load();
    expect(result.sourceStatus, 'RECORDED');
    expect(result.records.single.instrument, 'NSE:INFY-EQ');
    expect(result.records.single.usability, 'RESEARCH_ONLY');
    expect(result.blockingReasons, contains('NO_VERIFIED_DECISION_EVIDENCE'));
  });

  test('rejects claimed verified signals and executable candidates', () {
    for (final key in ['executionEnabled', 'verifiedSignal']) {
      expect(() => MindEvidenceLedger.fromJson(_payload()..[key] = true),
        throwsFormatException);
    }
    final unsafe = _payload();
    ((unsafe['entries'] as List).first as Map<String, dynamic>)['executable'] = true;
    expect(() => MindEvidenceLedger.fromJson(unsafe), throwsFormatException);
    final spoofed = _payload();
    ((spoofed['entries'] as List).first as Map<String, dynamic>)['source'] = 'SYNTHETIC';
    expect(() => MindEvidenceLedger.fromJson(spoofed), throwsFormatException);
  });

  test('rejects missing source timestamps and missing candidate fingerprint', () {
    final future = _payload();
    ((future['entries'] as List).first as Map<String, dynamic>)['observedAt'] =
      '2026-11-10T09:00:00Z';
    expect(() => MindEvidenceLedger.fromJson(future), throwsFormatException);

    final noFingerprint = _payload();
    ((noFingerprint['entries'] as List).first as Map<String, dynamic>)
      ['fingerprint'] = null;
    expect(() => MindEvidenceLedger.fromJson(noFingerprint), throwsFormatException);
  });

  test('partial scan may expose blocker diagnostics but never candidate entries', () {
    final payload = _payload();
    payload['sourceStatus'] = 'PARTIAL';
    expect(() => MindEvidenceLedger.fromJson(payload), throwsFormatException);

    payload['entries'] = [{
      'id': 'diagnostic-12', 'kind': 'RESEARCH_DIAGNOSTIC',
      'source': 'PERSISTED_RESEARCH_SCAN_DIAGNOSTIC',
      'instrument': null, 'strategy': 'MA_RECLAIM',
      'referenceCode': 'MISSING_CANDLES',
      'observedAt': '2026-11-09T08:55:00Z',
      'dataCutoffAt': null, 'fingerprint': null,
      'usability': 'BLOCKER_ONLY',
      'knownAtStatus': 'NOT_INDEPENDENTLY_VERIFIED',
      'executable': false,
    }];
    expect(MindEvidenceLedger.fromJson(payload).records.single.kind,
      'RESEARCH_DIAGNOSTIC');
  });

  test('unavailable ledger has no hidden run or entries', () {
    final empty = _payload();
    empty['sourceStatus'] = 'UNAVAILABLE';
    empty['scanRunId'] = null;
    empty['scanCompletedAt'] = null;
    empty['source'] = null;
    empty['entries'] = [];
    expect(MindEvidenceLedger.fromJson(empty).records, isEmpty);
    empty['scanRunId'] = 'forbidden';
    expect(() => MindEvidenceLedger.fromJson(empty), throwsFormatException);
  });
}

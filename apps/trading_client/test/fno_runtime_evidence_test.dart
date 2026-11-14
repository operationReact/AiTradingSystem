import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/upstox_analytics/fno_runtime_evidence.dart';

void main() {
  test('source probe and registered provider do not imply PAPER readiness', () {
    const evidence = FnoRuntimeEvidence.auditedNovember14;
    expect(evidence.sourceProbe, FnoRuntimeProof.verified);
    expect(evidence.liveQuoteVerified, isFalse);
    expect(evidence.paperRoundTripVerified, isFalse);
    expect(evidence.providerLabel,
        'REGISTERED / NO RIGHTS RECEIPTS / NO BACKFILL JOBS');
  });

  test('PAPER readiness requires every independent receipt', () {
    const evidence = FnoRuntimeEvidence(
      sourceProbe: FnoRuntimeProof.verified,
      exchangeLttTick: FnoRuntimeProof.verified,
      displayPaperRights: FnoRuntimeProof.blocked,
      paperBuy: FnoRuntimeProof.verified,
      paperSellClose: FnoRuntimeProof.verified,
      androidDevice: FnoRuntimeProof.verified,
      providerRegistered: true,
      rightsMetadataRows: 0,
      backfillJobs: 0,
    );
    expect(evidence.paperRoundTripVerified, isFalse);
  });
}

/// Runtime evidence boundaries for the read-only Upstox operations surface.
/// A successful source probe is NOT a live quote, rights grant, or PAPER fill.
enum FnoRuntimeProof { verified, notVerified, blocked }

class FnoRuntimeEvidence {
  const FnoRuntimeEvidence({
    required this.sourceProbe,
    required this.exchangeLttTick,
    required this.displayPaperRights,
    required this.paperBuy,
    required this.paperSellClose,
    required this.androidDevice,
    required this.providerRegistered,
    required this.rightsMetadataRows,
    required this.backfillJobs,
  });

  final FnoRuntimeProof sourceProbe;
  final FnoRuntimeProof exchangeLttTick;
  final FnoRuntimeProof displayPaperRights;
  final FnoRuntimeProof paperBuy;
  final FnoRuntimeProof paperSellClose;
  final FnoRuntimeProof androidDevice;
  final bool providerRegistered;
  final int? rightsMetadataRows;
  final int? backfillJobs;

  bool get liveQuoteVerified => exchangeLttTick == FnoRuntimeProof.verified;
  bool get paperRoundTripVerified =>
      displayPaperRights == FnoRuntimeProof.verified &&
      liveQuoteVerified &&
      paperBuy == FnoRuntimeProof.verified &&
      paperSellClose == FnoRuntimeProof.verified &&
      androidDevice == FnoRuntimeProof.verified;

  String get providerLabel => !providerRegistered
      ? 'PROVIDER NOT REGISTERED'
      : rightsMetadataRows == null || backfillJobs == null
          ? 'REGISTERED / DB RECEIPTS UNKNOWN'
          : rightsMetadataRows == 0 && backfillJobs == 0
              ? 'REGISTERED / NO RIGHTS RECEIPTS / NO BACKFILL JOBS'
              : 'REGISTERED / CHECK RIGHTS AND JOB RECEIPTS';

  /// Dated independent audit only. Never use as a live polling response.
  static const auditedNovember14 = FnoRuntimeEvidence(
    sourceProbe: FnoRuntimeProof.verified,
    exchangeLttTick: FnoRuntimeProof.notVerified,
    displayPaperRights: FnoRuntimeProof.blocked,
    paperBuy: FnoRuntimeProof.notVerified,
    paperSellClose: FnoRuntimeProof.notVerified,
    androidDevice: FnoRuntimeProof.notVerified,
    providerRegistered: true,
    rightsMetadataRows: 0,
    backfillJobs: 0,
  );
}

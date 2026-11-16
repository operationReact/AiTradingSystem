"""Synthetic-only fail-closed checks for Worker 4 receipt diagnostic."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("gate", Path(__file__).with_name("worker4_receipt_gate.py"))
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)

T = "2026-11-14T10:00:00Z"
def receipt(**overrides):
    r = dict(sourceId="fixture", authorizationReference="fixture-only", dataFamily="RATIO",
             listingEpisode="fixture-episode", partitionKey="fixture-partition",
             sourceRecordId="fixture-record", revision="v1", receiptId="fixture-receipt",
             publishedAt=T, availableAt=T, observedAt=T, committedAt=T,
             useAuthorized=True, persistenceVerified=True)
    r.update(overrides)
    return r

class GateTest(unittest.TestCase):
    def test_unknown_rights_fail_closed(self):
        self.assertEqual(gate.evaluate([receipt(useAuthorized=None)], T)["eligibleUniqueUnits"], 0)

    def test_unknown_persistence_fail_closed(self):
        self.assertEqual(gate.evaluate([receipt(persistenceVerified=False)], T)["rejectedByReason"]["PERSISTENCE_UNKNOWN"], 1)

    def test_late_revision_abstains(self):
        self.assertEqual(gate.evaluate([receipt(availableAt="2026-11-15T10:00:00Z")], T)["eligibleUniqueUnits"], 0)

    def test_future_commit_not_persisted_at_cutoff(self):
        result = gate.evaluate([receipt(committedAt="2026-11-15T10:00:00Z")], T)
        self.assertEqual(result["eligibleUniqueUnits"], 0)
        self.assertEqual(result["rejectedByReason"]["FUTURE_KNOWLEDGE"], 1)

    def test_availability_before_publication_rejected(self):
        result = gate.evaluate([receipt(availableAt="2026-11-14T09:00:00Z")], T)
        self.assertEqual(result["rejectedByReason"]["TEMPORAL_ORDER_INVALID"], 1)

    def test_commit_before_observation_rejected(self):
        result = gate.evaluate([receipt(committedAt="2026-11-14T09:00:00Z")], T)
        self.assertEqual(result["rejectedByReason"]["TEMPORAL_ORDER_INVALID"], 1)

    def test_duplicate_retry_does_not_inflate(self):
        self.assertEqual(gate.evaluate([receipt(), receipt(receiptId="retry")], T)["eligibleUniqueUnits"], 1)

    def test_receipt_id_conflict_cannot_inflate_units(self):
        result = gate.evaluate([receipt(), receipt(sourceRecordId="other-record")], T)
        self.assertEqual(result["eligibleUniqueUnits"], 1)
        self.assertEqual(result["rejectedByReason"]["RECEIPT_ID_CONFLICT"], 1)

    def test_distinct_receipts_for_distinct_records(self):
        result = gate.evaluate([receipt(), receipt(sourceRecordId="other-record", receiptId="other-receipt")], T)
        self.assertEqual(result["eligibleUniqueUnits"], 2)

    def test_revision_is_distinct(self):
        self.assertEqual(gate.evaluate([receipt(), receipt(revision="v2", receiptId="revision-v2")], T)["eligibleUniqueUnits"], 2)

    def test_revision_reusing_receipt_id_is_rejected(self):
        result = gate.evaluate([receipt(), receipt(revision="v2")], T)
        self.assertEqual(result["eligibleUniqueUnits"], 1)
        self.assertEqual(result["rejectedByReason"]["RECEIPT_ID_CONFLICT"], 1)

    def test_missing_timestamp_abstains(self):
        self.assertEqual(gate.evaluate([receipt(publishedAt=None)], T)["eligibleUniqueUnits"], 0)

    def test_invalid_cutoff_unknown(self):
        self.assertIsNone(gate.evaluate([receipt()], "not-a-date")["eligibleUniqueUnits"])

if __name__ == "__main__":
    unittest.main()

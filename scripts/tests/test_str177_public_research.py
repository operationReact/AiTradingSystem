import json
import py_compile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RESEARCH = ROOT / "infra/ec2-paper/research"


class PublicResearchEvidenceTest(unittest.TestCase):
    def test_offline_scripts_compile(self):
        for name in ("nse_udiff_fut_parser.py", "str177_recovery_trace.py"):
            py_compile.compile(str(RESEARCH / name), doraise=True)

    def test_results_json_is_self_consistent_and_research_only(self):
        data = json.loads((RESEARCH / "str176_str177_public_oos_results.json").read_text())
        # Research-only labelling must be present and no certification implied.
        self.assertIn("RESEARCH-ONLY", data["dataset"]["note"])
        self.assertIn("does NOT satisfy", data["dataset"]["note"])
        blob = json.dumps(data).lower()
        self.assertNotIn("certified", blob)

        # STR-177: candidate list length matches the histogram bucket.
        s177 = data["str177"]
        hist_candidates = sum(v for k, v in s177["outcomeHistogram"].items()
                              if k.startswith("RESEARCH_CANDIDATE"))
        self.assertEqual(hist_candidates, len(s177["researchCandidates"]))
        self.assertEqual(sum(s177["outcomeHistogram"].values()), s177["windowsEvaluated"])

        # STR-176: aggregate net equals the sum of per-trade nets, each bps.
        s176 = data["str176"]
        for bps in ("20", "30", "50"):
            total = sum(t[f"net{bps}bps"] for t in s176["candidateTrades"])
            self.assertEqual(total, s176["aggregateNetPaiseByBps"][bps])
        # Honest framing: the module records there is no demonstrated edge.
        self.assertIn("no demonstrated edge", s176["note"].lower())


if __name__ == "__main__":
    unittest.main()

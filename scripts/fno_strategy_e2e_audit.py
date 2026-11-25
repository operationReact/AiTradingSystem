#!/usr/bin/env python3
"""Fail-closed, read-only, 43-strategy research integration evidence auditor.

This deliberately NEVER contacts brokers/AWS, accepts credentials, places orders,
or treats fictional JUnit fixtures as real NSE data. CI evidence is exclusively
compile + JUnit assertions, not true market-data or execution integration.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

START = 170
END = 212
EXPECTED_COUNT = END - START + 1
CATALOG = Path("backend/src/main/resources/mind/capability-manifest.v1.json")
SOURCE_ROOT = Path("backend/src/main/java/in/aitradingsystem/tradingrooms")
TEST_ROOT = Path("backend/src/test/java/in/aitradingsystem/tradingrooms")
DOC_ROOT = Path("docs")
PROOF_CLASS = "minttick.fno.43-research-evaluator-validation.v1"
UNVERIFIED = "NO_INDEPENDENT_RUNTIME_EVIDENCE"
# These are actual Java evaluator invocations in a reviewed research service,
# NOT proof that the source data or broker permissions were verified at runtime.
DIRECT_SOURCE_CALLSITES = (
    Path("backend/src/main/java/in/aitradingsystem/tradingrooms/"
         "FnoFuturesResearchService.java"),
    Path("backend/src/main/java/in/aitradingsystem/tradingrooms/"
         "FnoOptionsResearchService.java"),
    Path("backend/src/main/java/in/aitradingsystem/tradingrooms/"
         "FnoDebitSpreadResearchService.java"),
    Path("backend/src/main/java/in/aitradingsystem/tradingrooms/"
         "FnoCreditSpreadResearchService.java"),
    Path("backend/src/main/java/in/aitradingsystem/tradingrooms/"
         "FnoOwnedEquityOptionsResearchService.java"),
    Path("backend/src/main/java/in/aitradingsystem/tradingrooms/"
         "FnoEquityCollarResearchService.java"),
    Path("backend/src/main/java/in/aitradingsystem/tradingrooms/"
         "FnoRatioSpreadResearchService.java"),
    Path("backend/src/main/java/in/aitradingsystem/tradingrooms/"
         "FnoStraddleResearchService.java"),
    Path("backend/src/main/java/in/aitradingsystem/tradingrooms/"
         "FnoStrangleResearchService.java"),
    Path("backend/src/main/java/in/aitradingsystem/tradingrooms/"
         "FnoShortStraddleResearchService.java"),
    Path("backend/src/main/java/in/aitradingsystem/tradingrooms/"
         "FnoShortStrangleResearchService.java"),
    Path("backend/src/main/java/in/aitradingsystem/tradingrooms/"
         "FnoIronCondorResearchService.java"),
    Path("backend/src/main/java/in/aitradingsystem/tradingrooms/"
         "FnoIronButterflyResearchService.java"),
    Path("backend/src/main/java/in/aitradingsystem/tradingrooms/"
         "FnoLongButterflyResearchService.java"),
    Path("backend/src/main/java/in/aitradingsystem/tradingrooms/"
         "FnoCalendarSpreadResearchService.java"),
    Path("backend/src/main/java/in/aitradingsystem/tradingrooms/"
         "FnoDiagonalSpreadResearchService.java"),
    Path("backend/src/main/java/in/aitradingsystem/tradingrooms/"
         "FnoIvRankResearchService.java"),
    Path("backend/src/main/java/in/aitradingsystem/tradingrooms/"
         "FnoVolatilityCrushResearchService.java"),
    Path("backend/src/main/java/in/aitradingsystem/tradingrooms/"
         "FnoGammaScalpingResearchService.java"),
    Path("backend/src/main/java/in/aitradingsystem/tradingrooms/"
         "FnoDeltaHedgeResearchService.java"),
    Path("backend/src/main/java/in/aitradingsystem/tradingrooms/"
         "FnoBetaHedgeOptionsResearchService.java"),
    Path("backend/src/main/java/in/aitradingsystem/tradingrooms/"
         "FnoTailRiskPutHedgeResearchService.java"),
    Path("backend/src/main/java/in/aitradingsystem/tradingrooms/"
         "FnoPutSpreadCollarResearchService.java"),
    Path("backend/src/main/java/in/aitradingsystem/tradingrooms/"
         "FnoDefinedRiskSpreadRollResearchService.java"),
    Path("backend/src/main/java/in/aitradingsystem/tradingrooms/"
         "FnoExpiryRiskNeutralizationResearchService.java"),
    Path("backend/src/main/java/in/aitradingsystem/tradingrooms/"
         "FnoPortfolioProtectiveOverlayResearchService.java"),
    Path("backend/src/main/java/in/aitradingsystem/tradingrooms/"
         "FnoGreeksExposureRebalanceResearchService.java"),
    Path("backend/src/main/java/in/aitradingsystem/tradingrooms/"
         "FnoSkewBasedHedgeResearchService.java"),
)
SIGNAL_ONLY_CALLSITE = (
    Path("backend/src/main/java/in/aitradingsystem/tradingrooms/"
         "CustomerMindFnoLiveProposalService.java")
)
EXPECTED_SIGNAL_IDS = {"strategy.STR-181", "strategy.STR-182"}
DENIED_METHODS = ("executable", "orderPlaced")
OPTIONAL_DENIED_METHODS = ("paperTradeAuthorized", "liveTradeAuthorized")
METHOD_BODY = r"\bboolean\s+{method}\s*\(\s*\)\s*\{{\s*return\s+(true|false)\s*;\s*\}}"
CANDIDATE_CALL = r"\b{class_name}\s*\.\s*evaluate\s*\("


def manifest_items(root: Path) -> tuple[dict[str, Any], list[dict[str, Any]], list[str]]:
    problems: list[str] = []
    try:
        manifest = json.loads((root / CATALOG).read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        return {}, [], [f"MANIFEST_UNREADABLE: {exc}"]
    if not isinstance(manifest, dict) or not isinstance(manifest.get("items"), list):
        return {}, [], ["MANIFEST_ITEMS_INVALID"]
    selected = [
        item for item in manifest["items"]
        if isinstance(item, dict) and re.fullmatch(r"strategy\.STR-\d{3}", str(item.get("id", "")))
        and START <= int(item["id"].rsplit("-", 1)[1]) <= END
    ]
    ids = [item["id"] for item in selected]
    expected = {f"strategy.STR-{n}" for n in range(START, END + 1)}
    if len(ids) != EXPECTED_COUNT or set(ids) != expected or len(set(ids)) != len(ids):
        problems.append("CATALOG_EXACT_43_UNIQUE_CONTIGUOUS_IDS_REQUIRED")
    return manifest, sorted(selected, key=lambda item: int(item["id"].rsplit("-", 1)[1])), problems


def _evidence_path(item: dict[str, Any], kind: str, root: Path,
                   prefix: Path, suffix: str, problems: list[str]) -> Path | None:
    evidence = item.get("evidence")
    if not isinstance(evidence, list):
        problems.append(f"{item['id']}: EVIDENCE_ARRAY_MISSING")
        return None
    matches = [entry.get("path") for entry in evidence
               if isinstance(entry, dict) and entry.get("kind") == kind]
    if len(matches) != 1 or not isinstance(matches[0], str):
        problems.append(f"{item['id']}: EXACTLY_ONE_{kind}_EVIDENCE_REQUIRED")
        return None
    relative = Path(matches[0])
    # Explicit relative-path containment, including symlink defense.
    if relative.is_absolute() or ".." in relative.parts or not relative.is_relative_to(prefix):
        problems.append(f"{item['id']}: {kind}_PATH_OUTSIDE_APPROVED_ROOT")
        return None
    if suffix and relative.suffix != suffix:
        problems.append(f"{item['id']}: {kind}_WRONG_FILE_TYPE")
        return None
    resolved = (root / relative).resolve()
    if not resolved.is_relative_to((root / prefix).resolve()) or not resolved.is_file():
        problems.append(f"{item['id']}: {kind}_FILE_ABSENT_OR_UNTRUSTED")
        return None
    return relative


def _method_value(source: str, method: str) -> str:
    match = re.search(METHOD_BODY.format(method=re.escape(method)), source)
    return "MISSING" if match is None else match.group(1).upper()


def _suite_for_test(surefire: Path, class_name: str) -> tuple[int, int, int, int] | None:
    path = surefire / ("TEST-in.aitradingsystem.tradingrooms." + class_name + ".xml")
    if not path.is_file() or path.is_symlink():
        return None
    try:
        suite = ET.parse(path).getroot()
    except ET.ParseError:
        return None
    if suite.tag != "testsuite":
        return None
    if suite.get("name") != "in.aitradingsystem.tradingrooms." + class_name:
        return None
    try:
        result = tuple(int(suite.get(k, "-1")) for k in ("tests", "failures", "errors", "skipped"))
    except ValueError:
        return None
    if any(n < 0 for n in result) or result[0] < sum(result[1:]):
        return None
    # Surefire records test case children for every executed test.
    if len(suite.findall("testcase")) != result[0]:
        return None
    return result


def inspect(root: Path, surefire: Path | None,
            require_surefire: bool = False) -> dict[str, Any]:
    _, items, errors = manifest_items(root)
    direct = "\n".join(
        (root / path).read_text(encoding="utf-8")
        for path in DIRECT_SOURCE_CALLSITES if (root / path).is_file()
    )
    signal = (root / SIGNAL_ONLY_CALLSITE).read_text(encoding="utf-8") \
        if (root / SIGNAL_ONLY_CALLSITE).is_file() else ""
    rows = []
    for item in items:
        strategy_id = item["id"]
        issues: list[str] = []
        if item.get("kind") != "STRATEGY":
            issues.append("NOT_A_STRATEGY")
        if item.get("status") != "DATA_BLOCKED":
            issues.append("NON_BLOCKED_CATALOG_STATUS_REQUIRES_EXPLICIT_REVIEW")
        if not item.get("requiredInputs") or not isinstance(item["requiredInputs"], list):
            issues.append("REQUIRED_INPUT_PROVENANCE_NOT_DECLARED")
        impl = item.get("implementation")
        if not isinstance(impl, dict) or impl.get("runtime") != "JAVA_DETERMINISTIC" \
                or impl.get("match") != "EXACT" or not impl.get("code"):
            issues.append("IMPLEMENTATION_NOT_DETERMINISTIC_OR_NOT_DECLARED")
        source_path = _evidence_path(item, "SOURCE", root, SOURCE_ROOT, ".java", issues)
        test_path = _evidence_path(item, "TEST", root, TEST_ROOT, ".java", issues)
        doc_path = _evidence_path(item, "DOC", root, DOC_ROOT, ".md", issues)

        code, test = "", ""
        if source_path:
            code = (root / source_path).read_text(encoding="utf-8")
        if test_path:
            test = (root / test_path).read_text(encoding="utf-8")
        class_name = source_path.stem if source_path else ""
        test_class = test_path.stem if test_path else ""
        if code and not re.search(r"\bpublic\s+final\s+class\s+" + re.escape(class_name) + r"\b", code):
            issues.append("SOURCE_CLASS_MISMATCH")
        if code and strategy_id not in code:
            issues.append("STRATEGY_ID_NOT_IN_SOURCE")
        if code and not re.search(r"\b(?:public\s+)?static\s+Decision\s+evaluate\s*\(", code):
            issues.append("EVALUATOR_ENTRYPOINT_NOT_FOUND")
        if source_path and _method_value(code, "executable") != "FALSE":
            issues.append("EXECUTABLE_NOT_FAIL_CLOSED")
        if source_path and _method_value(code, "orderPlaced") != "FALSE":
            issues.append("ORDER_PLACED_NOT_FAIL_CLOSED")
        optional_order_boundaries = {m: _method_value(code, m) for m in OPTIONAL_DENIED_METHODS}
        if any(v == "TRUE" for v in optional_order_boundaries.values()):
            issues.append("PAPER_OR_LIVE_AUTHORIZATION_IN_RESEARCH_SOURCE")
        if test_path and not test_class.endswith("Test"):
            issues.append("JUNIT_TEST_CLASS_NAME_INVALID")
        test_declarations = len(re.findall(r"@Test\b", test))
        if test_path and test_declarations < 1:
            issues.append("NO_EXPLICIT_JUNIT_TEST_CASES")
        if test and strategy_id not in test:
            issues.append("STRATEGY_ID_NOT_EXERCISED_IN_JUNIT_FILE")
        suite = _suite_for_test(surefire, test_class) if surefire and test_class else None
        if require_surefire and (suite is None or suite[0] < 1):
            issues.append("JUNIT_SUREFIRE_EXECUTION_PROOF_MISSING")
        if suite is not None and (suite[1] or suite[2] or suite[3]):
            issues.append("JUNIT_FAILURE_ERROR_OR_SKIP")
        if suite is not None and suite[0] != test_declarations:
            # A divergent count must be inspected (e.g. parameterized tests).
            issues.append("JUNIT_DISCOVERED_AND_EXECUTED_COUNTS_DIFFER")

        # This probes only explicit source-level research call sites. No claim
        # is made that those paths ever ran against licensed production data.
        direct_call = bool(class_name) and re.search(
            CANDIDATE_CALL.format(class_name=re.escape(class_name)), direct) is not None
        signal_only = strategy_id in EXPECTED_SIGNAL_IDS and strategy_id in signal
        rows.append({
            "id": strategy_id,
            "name": item.get("name"),
            "manifestStatus": item.get("status"),
            "implementationCode": impl.get("code") if isinstance(impl, dict) else None,
            "source": source_path.as_posix() if source_path else None,
            "test": test_path.as_posix() if test_path else None,
            "documentation": doc_path.as_posix() if doc_path else None,
            "sourceEvaluatorPresent": bool(code) and not any(
                p in issues for p in ("SOURCE_CLASS_MISMATCH", "EVALUATOR_ENTRYPOINT_NOT_FOUND")),
            "declaredJunitCases": test_declarations,
            "executedJunitCases": suite[0] if suite else None,
            "passedJunitCases": suite[0] - sum(suite[1:]) if suite else None,
            "testSource": "FICTIONAL_FIXTURES_NOT_LIVE_MARKET_VALIDATION",
            "directResearchCallsiteLocated": direct_call,
            "signalRoutingOnlyLocated": bool(signal_only),
            "paperAndLiveResearchFlags": optional_order_boundaries,
            "realNseSourceValidation": UNVERIFIED,
            "brokerInventoryAndFillValidation": UNVERIFIED,
            "outOfSampleAfterCostValidation": UNVERIFIED,
            "paperExecutionReady": False,
            "liveExecutionReady": False,
            "strategyEndToEndReady": False,
            "blockingIssues": issues,
        })
        errors.extend(issues and [strategy_id + ": " + issue for issue in issues] or [])

    return {
        "contractVersion": PROOF_CLASS,
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "scope": {"start": START, "end": END, "expected": EXPECTED_COUNT},
        "evidenceTier": "RESEARCH_JUNIT_ONLY_NO_PROVIDER_OR_BROKER_ACCESS",
        "sourceMarketDataFetched": False,
        "providerCredentialsAccessed": False,
        "brokerOrdersPlaced": False,
        "awsActionsPerformed": False,
        "summary": {
            "strategies": len(rows),
            "implemented": sum(bool(r["sourceEvaluatorPresent"]) for r in rows),
            "junitEvidenceComplete": sum(r["executedJunitCases"] is not None
                                         and not r["blockingIssues"] for r in rows),
            "executedJunitCases": sum(r["executedJunitCases"] or 0 for r in rows),
            "sourceLevelDirectResearchCallsites": sum(r["directResearchCallsiteLocated"] for r in rows),
            "signalRoutingWithoutProvenEvaluatorCallsite": sum(
                r["signalRoutingOnlyLocated"] and not r["directResearchCallsiteLocated"] for r in rows),
            "realDataE2EValidated": 0,
            "paperTradingValidated": 0,
            "liveTradingValidated": 0,
        },
        "sourceLimitations": [
            "No live/historical licensed NSE provider evidence is supplied to this local CI job.",
            "Repo source research call sites are not evidence of source entitlement, deployment or execution.",
            "Independent, source-original dated replay and broker inventory/fill validation remain open.",
            "All 43 manifest entries remain DATA_BLOCKED; the suite cannot authorize PAPER or LIVE.",
        ],
        "strategies": rows,
        "errors": errors,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--surefire-dir", type=Path)
    parser.add_argument("--report", type=Path)
    parser.add_argument("--require-surefire", action="store_true")
    parser.add_argument("--list-tests", action="store_true")
    args = parser.parse_args(argv)
    root = args.repo_root.resolve()
    if args.list_tests:
        _, items, errors = manifest_items(root)
        if errors:
            print("; ".join(errors), file=sys.stderr)
            return 1
        classes = [
            Path(next(e["path"] for e in item["evidence"] if e["kind"] == "TEST")).stem
            for item in items
        ]
        print(",".join(classes))
        return 0
    surefire = args.surefire_dir
    if surefire and not surefire.is_absolute():
        surefire = root / surefire
    report = inspect(root, surefire, require_surefire=args.require_surefire)
    if args.report:
        output = args.report if args.report.is_absolute() else root / args.report
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps({
        "contractVersion": PROOF_CLASS,
        "summary": report["summary"],
        "errors": report["errors"][:20],
        "errorCount": len(report["errors"]),
        "report": str(args.report) if args.report else None,
    }, sort_keys=True))
    return 1 if report["errors"] else 0


if __name__ == "__main__":
    raise SystemExit(main())

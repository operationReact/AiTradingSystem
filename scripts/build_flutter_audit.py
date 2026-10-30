#!/usr/bin/env python3
"""Render traceability from observed snapshots; no status inferred from PR titles alone."""
import csv
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'docs/audit'
prs = json.loads((OUT / 'flutter-pr-input.json').read_text())
planned = json.loads((OUT / 'flutter-planned-prs.json').read_text())
ownership = {p['number']: p for p in json.loads((OUT / 'flutter-pr-ownership.json').read_text())}
figma = {p['phase']: p for p in json.loads((OUT / 'flutter-figma-coverage.json').read_text())}

def latest_runs(pr):
    latest = {}
    for run in pr.get('ciEvidence', {}).get('workflow_runs', []):
        if run['id'] > latest.get(run['name'], {}).get('id', 0):
            latest[run['name']] = run
    return latest.values()

with (OUT / 'flutter-historical-pr-review.csv').open('w', newline='') as f:
    writer = csv.writer(f)
    writer.writerow(['github_pr', 'title', 'state', 'merged_on_dev', 'reviewed_head', 'merge_commit', 'canonical_owners', 'supplemental_for', 'changed_file_count', 'backend_test_files_changed', 'legacy_frontend_files_changed', 'flutter_files_changed', 'latest_returned_ci', 'flutter_acceptance', 'review_scope'])
    for pr in sorted(prs, key=lambda p:p['number']):
        owner = ownership[pr['number']]
        ci = '; '.join(f"{r['name']}:{r['id']}:{r['conclusion']}" for r in latest_runs(pr)) or 'NOT_RETRIEVED'
        writer.writerow([pr['url'],pr['title'], 'MERGED' if pr['merged'] else 'CLOSED_UNMERGED',pr['mergeOnDev'],pr['headSha'],pr['devMergeSha'] or '', ';'.join(owner['plannedOwners']),';'.join(owner['supplementalFor']),len(pr['changedPaths']),len(pr['backendTestsChanged']),len(pr['legacyFrontendChanged']),len(pr['flutterPathsChanged']),ci,'GAP — no Flutter implementation evidence','metadata + changed paths + test inventory; CI snapshot for canonical merged PRs; NOT full code/runtime verification'])

ui_ids = {'P00-PR02','P01-PR05','P02-PR07','P03-PR06','P04-PR07','P05-PR08','P05-PR09','P06-PR09','P07-PR07','P08-PR06','P10-PR02','P11-PR01','P11-PR02','P11-PR03','P11-PR04'}
with (OUT / 'flutter-planned-pr-review.csv').open('w', newline='') as f:
    writer = csv.writer(f)
    writer.writerow(['planned_id','scope','work_items','canonical_figma_section','actual_primary_prs','supplemental_prs','implementation_evidence_status','flutter_impact','web','android','ios','acceptance_boundary'])
    for item in planned:
        phase=int(item['id'][1:3]); node=figma[phase]['sectionId']
        actual=[p for p in prs if item['id'] in ownership[p['number']]['plannedOwners'] and p['merged'] and p['mergeOnDev']]
        supplemental=[p['number'] for p in prs if item['id'] in ownership[p['number']]['supplementalFor'] and p['merged'] and p['mergeOnDev']]
        ui=item['id'] in ui_ids or phase in (9,21)
        impact='Flutter implementation/acceptance required' if ui else 'Assess backend/API contract; Flutter consumer/platform proof belongs to owning UI slice'
        status='MERGED_SCOPE_EVIDENCE — backend/legacy UI assessed separately' if actual else 'NO_CANONICAL_PRIMARY_MERGE_MAPPED — inspect baseline before claiming missing implementation'
        writer.writerow([item['id'],item['title'],';'.join(item['workItems']),f'https://www.figma.com/design/KjBWVCtUYona0bOGb5NDov?node-id={node.replace(":","-")}', ';'.join(str(p['number']) for p in actual),';'.join(map(str,supplemental)),status,impact,*(['GAP/UNVERIFIED']*3 if ui else ['N/A for backend code; consumer UNVERIFIED']*3),'No Flutter completion inferred; backend-only work need not be rewritten in Dart. Metadata mapping does not prove every acceptance criterion.'])

summary = {
    'snapshotCommit':'0a6283af38da7fa9a44692174b372994889068f6',
    'phaseCount':22,'plannedPrEntries':len(planned),'allocatedWorkItemIds':len({w for p in planned for w in p['workItems']}),
    'historicalPrCount':len(prs),'merged':sum(p['merged'] for p in prs),'closedUnmerged':sum(not p['merged'] for p in prs),
    'mergedOnDev':sum(p['mergeOnDev'] for p in prs),'legacyFrontendPrs':sum(bool(p['legacyFrontendChanged']) for p in prs),'flutterPrs':sum(bool(p['flutterPathsChanged']) for p in prs),
    'canonicalHeadsInspected':sum(p['number']>=74 and p['merged'] for p in prs),
    'canonicalHeadsWithReturnedRuns':sum(p['number']>=74 and bool(list(latest_runs(p))) for p in prs if p['merged']),
    'headsWithoutReturnedCi':[p['number'] for p in prs if p['number']>=74 and p['merged'] and not list(latest_runs(p))],
    'figmaPhaseSectionsPresent':sum(x['nodesPresent'] for x in figma.values()),'figmaVariantIdsPresent':sum(len(x['variants']) for x in figma.values()),
    'flutterRuntimeEvidence':'NONE','auditLimit':'All planned PR entries and returned historical PR metadata/paths are indexed; not a fresh line-by-line revalidation of every implementation or physical-device test.'
}
(OUT / 'flutter-audit-summary.json').write_text(json.dumps(summary,indent=2)+'\n')
print(json.dumps(summary,indent=2))

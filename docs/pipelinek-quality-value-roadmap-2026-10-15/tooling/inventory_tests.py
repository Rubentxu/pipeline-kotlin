#!/usr/bin/env python3
"""Inventory *all tracked test source files*, with heuristic hints, not deletion verdicts.

Stdlib-only. Never modifies the repo. Treats UAT/E2E as intentions and marks
uncertainty; always inspect behavior and replacement proof before retirement.
"""
import argparse
import csv
import hashlib
import pathlib
import re
import subprocess
import sys

FIELDS = (
    'id', 'path', 'module', 'kind_hint', 'runner_hint', 'observed_process_api',
    'test_annotated', 'disabled_annotations', 'release_scale_tags',
    'content_sha256', 'disposition', 'contract_id', 'replacement', 'evidence',
)


def tracked_sources(repo: pathlib.Path) -> list[pathlib.Path]:
    p = subprocess.run(['git', '-C', str(repo), 'ls-files', '-z'],
                       capture_output=True, check=True)
    paths = [pathlib.Path(v.decode('utf-8')) for v in p.stdout.split(b'\0') if v]
    return sorted(p for p in paths if '/src/test/' in '/' + p.as_posix()
                  and p.suffix in {'.kt', '.java'})


def classify(p: pathlib.Path, source: str) -> dict[str, str]:
    name = p.name
    explicit = bool(re.search(r'(?:Uat|UAT|E2E|E2e|EndToEnd|Acceptance)', name))
    integration = bool(re.search(r'(?:Integration|Smoke|Sandbox|Installed|Certification|Canary|Corpus)', name))
    observed = sorted(set(x for x in ('ProcessBuilder(', 'AppBinSupport', 'OwnedSubprocess',
                                     'Runtime.getRuntime().exec', 'installDist') if x in source))
    annotated = len(re.findall(r'@(?:Test|ParameterizedTest|TestFactory|RepeatedTest)\b', source))
    disabled = len(re.findall(r'@Disabled\b', source))
    tag = len(re.findall(r'@Tag\s*\(\s*["\']release-scale["\']', source))
    helper = name.startswith(('Abstract', 'Base')) or not name.endswith(('Test.kt', 'Test.java', 'Spec.kt', 'Spec.java', 'TestSmoke.kt'))
    kind = ('SUPPORT_OR_ABSTRACT' if helper else 'EXPLICIT_UAT_E2E' if explicit
            else 'PROCESS_USING_CANDIDATE' if observed else 'INTEGRATION_CANDIDATE'
            if integration else 'OTHER_TEST_SOURCE')
    return {
        'kind_hint': kind,
        'runner_hint': 'V2_GRADLE' if p.parts[0] == 'v2' else 'LEGACY_NOT_DEFAULT_V2',
        'observed_process_api': ';'.join(observed),
        'test_annotated': str(annotated),
        'disabled_annotations': str(disabled),
        'release_scale_tags': str(tag),
        'content_sha256': hashlib.sha256(source.encode('utf-8')).hexdigest(),
    }


def inventory(repo: pathlib.Path) -> list[dict[str, str]]:
    records = []
    for p in tracked_sources(repo):
        try:
            src = (repo / p).read_text(encoding='utf-8')
        except (UnicodeError, OSError) as exc:
            raise ValueError(f'Unreadable test source {p}: {exc}') from exc
        records.append({
            'id': 'PK-T-' + hashlib.sha256(p.as_posix().encode()).hexdigest()[:12],
            'path': p.as_posix(), 'module': p.as_posix().split('/src/test/')[0],
            **classify(p, src),
            'disposition': 'PENDING_REVIEW', 'contract_id': 'UNKNOWN',
            'replacement': 'NOT_PROVIDED', 'evidence': 'NOT_RUN',
        })
    return records


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--repo', type=pathlib.Path, default=pathlib.Path('.'))
    ap.add_argument('--output', type=pathlib.Path, required=True)
    args = ap.parse_args()
    repo = args.repo.resolve()
    if not (repo / '.git').exists():
        ap.error(f'Expected git checkout at {repo}')
    data = inventory(repo)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open('w', encoding='utf-8', newline='') as out:
        writer = csv.DictWriter(out, fieldnames=FIELDS)
        writer.writeheader()
        writer.writerows(data)
    print(f'INVENTORY_WRITTEN files={len(data)} path={args.output}')
    print('NOTE: content heuristics are candidates, not retirement authorization; '
          'review all paths and merge with PR #140 ledger by path.')
    return 0


if __name__ == '__main__':
    sys.exit(main())

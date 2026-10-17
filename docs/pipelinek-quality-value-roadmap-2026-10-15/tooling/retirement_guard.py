#!/usr/bin/env python3
"""Fail-closed retirement guard, with a pure decider and impure filesystem adapter.

Deletes only user-created ephemeral cycle probes, never existing repository
src/test UAT/E2E/AAT. Use --apply only after evidence/external review.
"""
import argparse
import hashlib
import json
import pathlib
import re
import subprocess
import sys
from dataclasses import dataclass


@dataclass(frozen=True)
class ProbeObservation:
    requested: str
    absolute_path: pathlib.Path | None
    inside_cycle: bool
    has_symlink: bool
    is_regular: bool
    sha256: str | None


@dataclass(frozen=True)
class Refused:
    reasons: tuple[str, ...]


@dataclass(frozen=True)
class Verified:
    targets: tuple[pathlib.Path, ...]


def inspect_paths(manifest: dict, cycle_root: pathlib.Path) -> tuple[ProbeObservation, ...]:
    """Impure edge: inspect OS state only; never conclude the test is valid."""
    base = (cycle_root / str(manifest.get('cycle_id', ''))).resolve()
    probes = manifest.get('probes')
    if not isinstance(probes, list):
        return ()
    readings = []
    for p in probes:
        rel = p.get('relative_path') if isinstance(p, dict) else None
        if not isinstance(rel, str) or not rel or pathlib.PurePath(rel).is_absolute():
            readings.append(ProbeObservation(str(rel), None, False, False, False, None))
            continue
        original = base / rel
        resolved = original.resolve()
        inside = base in resolved.parents
        curr = original
        has_symlink = False
        while curr != base and base in curr.parents:
            has_symlink = has_symlink or curr.is_symlink()
            curr = curr.parent
        regular = inside and resolved.is_file() and not has_symlink
        try:
            sha = hashlib.sha256(resolved.read_bytes()).hexdigest() if regular else None
        except OSError:
            regular, sha = False, None
        readings.append(ProbeObservation(rel, resolved, inside, has_symlink, regular, sha))
    return tuple(readings)


def adjudicate(manifest: dict, observations: tuple[ProbeObservation, ...]) -> Refused | Verified:
    """Pure core: total decision from immutable manifest facts and observations."""
    issues = []
    if manifest.get('status') != 'READY_TO_CLOSE':
        issues.append('Manifest is not READY_TO_CLOSE')
    cid = manifest.get('cycle_id')
    if not isinstance(cid, str) or not re.fullmatch(r'[A-Za-z0-9_-]+', cid):
        issues.append('Invalid cycle_id')
    sha = manifest.get('source_sha')
    if not isinstance(sha, str) or not re.fullmatch(r'[0-9a-fA-F]{40}', sha):
        issues.append('source_sha must be a full git SHA')
    probes = manifest.get('probes')
    if not isinstance(probes, list) or not probes:
        issues.append('No probes present')
        probes = []
    if len(probes) != len(observations):
        issues.append('Observation count mismatch')
    targets = []
    seen = set()
    for index, p in enumerate(probes):
        loc = f'probe[{index}]'
        if not isinstance(p, dict):
            issues.append(f'{loc}: not an object')
            continue
        if index >= len(observations):
            continue
        o = observations[index]
        rel = p.get('relative_path')
        if not isinstance(rel, str) or not rel or pathlib.PurePath(rel).is_absolute() or any(
            part in {'.', '..'} for part in pathlib.PurePath(rel).parts
        ):
            issues.append(f'{loc}: invalid relative_path')
            continue
        if rel != o.requested or not o.inside_cycle:
            issues.append(f'{loc}: path escapes cycle directory')
            continue
        if not o.is_regular or o.has_symlink or o.absolute_path is None:
            issues.append(f'{loc}: file missing, not regular or symlink')
            continue
        if o.absolute_path in seen:
            issues.append(f'{loc}: duplicate path')
        seen.add(o.absolute_path)
        if not p.get('id') or not p.get('contract_id'):
            issues.append(f'{loc}: missing id or contract_id')
        if p.get('kind') not in {'UAT', 'E2E', 'AAT'}:
            issues.append(f'{loc}: kind must be UAT/E2E/AAT')
        executed = p.get('executed')
        skipped = p.get('skipped')
        if type(executed) is not int or executed < 1:
            issues.append(f'{loc}: invalid or zero executed count')
            executed = 0
        if type(skipped) is not int or skipped < 0:
            issues.append(f'{loc}: invalid skipped count')
            skipped = executed
        if p.get('verdict') != 'PASS' or executed < 1:
            issues.append(f'{loc}: unexecuted or non-PASS probe')
        if skipped >= executed:
            issues.append(f'{loc}: all tests skipped')
        if p.get('mutation_or_negative') in (None, '', 'UNKNOWN'):
            issues.append(f'{loc}: missing mutation/negative check')
        if p.get('content_sha256') != o.sha256:
            issues.append(f'{loc}: file modified after attestation')
        targets.append(o.absolute_path)
    return Refused(tuple(issues)) if issues else Verified(tuple(targets))


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--cycle-root', required=True, type=pathlib.Path,
                    help='Existing .pipelinek-cycles directory OUTSIDE Git')
    ap.add_argument('--manifest', required=True, type=pathlib.Path,
                    help='Manifest outside the disposable cycle directory')
    ap.add_argument('--apply', action='store_true', help='Delete listed probes after validation')
    args = ap.parse_args()
    root = args.cycle_root.resolve()
    if root.name != '.pipelinek-cycles' or not root.is_dir():
        ap.error('cycle root must be an existing .pipelinek-cycles directory')
    if args.manifest.resolve().is_relative_to(root):
        ap.error('manifest must be outside the disposable cycle directory')
    git = subprocess.run(['git', '-C', str(root), 'rev-parse', '--show-toplevel'],
                         capture_output=True, text=True)
    if git.returncode == 0:
        ap.error('cycle root belongs to a git worktree; use an external workspace')
    try:
        manifest = json.loads(args.manifest.read_text(encoding='utf-8'))
    except (json.JSONDecodeError, OSError) as exc:
        ap.error(f'Cannot read manifest: {exc}')
    observed = inspect_paths(manifest, root)
    result = adjudicate(manifest, observed)
    if isinstance(result, Refused):
        print('RETIREMENT_REFUSED\n' + '\n'.join(f' - {x}' for x in result.reasons))
        return 2
    for path in result.targets:
        print(('DELETE' if args.apply else 'WOULD_DELETE') + ' ' + str(path))
    if args.apply:
        # Re-observe immediately before deleting to reduce accidental stale decisions.
        if inspect_paths(manifest, root) != observed:
            print('RETIREMENT_REFUSED: filesystem changed after validation')
            return 2
        for path in result.targets:
            path.unlink()
        print('CYCLE_PROBES_DELETED; no legacy source tests were touched')
    else:
        print('DRY_RUN_OK; no file was removed')
    return 0


if __name__ == '__main__':
    sys.exit(main())

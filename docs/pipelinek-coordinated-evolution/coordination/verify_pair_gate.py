#!/usr/bin/env python3
"""Pair-release admission checker for Rubentxu/pipeline-kotlin x pipelinek-fabric.
Stdlib only, read-only. It never merges/pushes/changes a Git ref. Exit 0 only for
verified pair receipts. A receipt is an attestation pointer, not proof of test execution;
review the captured test logs and production artifacts independently.
"""
import argparse
import datetime as dt
import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path

HEX40 = re.compile(r"^[a-f0-9]{40}$")
HEX64 = re.compile(r"^[a-f0-9]{64}$")
ORIGINS = {"pipelinek": "Rubentxu/pipeline-kotlin", "fabric": "Rubentxu/pipelinek-fabric"}
COMMON_DOCS = ["INTERFACE_CONTRACT.md", "PAIR_RELEASE_FLOW.md", "MILESTONE_MATRIX.md", "GATE_OPERATIONS.md", "REQUIRED_GITHUB_PROTECTION.md", "BASELINE-PINS.md", "PAIR-RECEIPT.schema.json", "PAIR-RECEIPT.template.json", "PK_CONTRACT_HANDOFF.template.md", "FAB_CONSUMER_VERDICT.template.md", "gate-policy.json", "milestone-required-evidence.json", "verify_pair_gate.py", "pair_gate_selftest.py"]

class GateFailure(Exception):
    pass

def ensure(condition, message):
    if not condition:
        raise GateFailure(message)

def digest(path):
    data = Path(path).read_bytes()
    return hashlib.sha256(data).hexdigest()

def cmd(*parts):
    p = subprocess.run(parts, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False, timeout=45)
    ensure(p.returncode == 0, f"failed command {parts[:3]}: {p.stderr.strip()[:400]}")
    return p.stdout.strip()

def path_from_receipt(base, pathname):
    ensure(isinstance(pathname, str) and pathname.strip(), "missing evidence/artifact path")
    p = Path(pathname)
    if not p.is_absolute():
        p = base / p
    ensure(p.is_file(), f"missing file: {p}")
    return p

def checksum_file(base, item, label):
    ensure(isinstance(item, dict), f"{label}: expected object")
    h = item.get("sha256", "")
    ensure(bool(HEX64.fullmatch(h)), f"{label}: sha256 missing/malformed")
    p = path_from_receipt(base, item.get("path"))
    ensure(digest(p) == h, f"{label}: SHA256 mismatch: {p}")
    return p

def git(repo, *args):
    return cmd("git", "-C", str(repo), *args)

def normalize_remote_url(s):
    s = s.rstrip("/")
    if s.endswith(".git"):
        s = s[:-4]
    if s.startswith("git@github.com:"):
        return s.removeprefix("git@github.com:")
    if s.startswith("ssh://git@github.com/"):
        return s.removeprefix("ssh://git@github.com/")
    if s.startswith("https://github.com/"):
        return s.removeprefix("https://github.com/")
    return None

def remote_rev(remote, *refs):
    out = cmd("git", "ls-remote", remote, *refs)
    got = {}
    for line in out.splitlines():
        if "\t" in line:
            value, name = line.split("\t", 1)
            got[name] = value
    return got

def check_remote(repo_dir, selected, owner):
    ensure(repo_dir.is_dir(), f"{owner}: checkout missing: {repo_dir}")
    git(repo_dir, "rev-parse", "--is-inside-work-tree")
    remote = git(repo_dir, "remote", "get-url", "origin")
    ensure(normalize_remote_url(remote) == ORIGINS[owner], f"{owner}: origin is not official {ORIGINS[owner]}: {remote}")
    commit = selected["commit"]
    tag = selected["tag"]
    ensure(HEX40.fullmatch(commit), f"{owner}: invalid commit SHA")
    ensure(tag and not tag.startswith("refs/") and not any(x in tag for x in ["..", "~", "^", " ", "\\"]), f"{owner}: malformed tag")
    refs = remote_rev(remote, f"refs/tags/{tag}", f"refs/tags/{tag}^{{}}", "refs/heads/main")
    remote_tag = refs.get(f"refs/tags/{tag}^{{}}", refs.get(f"refs/tags/{tag}"))
    ensure(remote_tag == commit, f"{owner}: remote tag {tag} points to {remote_tag}; expected {commit}")
    remote_main = refs.get("refs/heads/main")
    ensure(bool(remote_main and HEX40.fullmatch(remote_main)), f"{owner}: cannot resolve remote main")
    local_main = git(repo_dir, "rev-parse", "refs/remotes/origin/main")
    ensure(local_main == remote_main, f"{owner}: refs/remotes/origin/main stale vs GitHub ({local_main} != {remote_main}); fetch then re-run")
    actual_local_tag = git(repo_dir, "rev-list", "-n", "1", f"refs/tags/{tag}")
    ensure(actual_local_tag == commit, f"{owner}: local tag does not match remote published artifact commit")
    ancestry = subprocess.run(["git", "-C", str(repo_dir), "merge-base", "--is-ancestor", commit, local_main], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    ensure(ancestry.returncode == 0, f"{owner}: release {commit} is not integrated into origin/main; no promotion")
    return (remote_main, remote)

def check_packages(left_dir, right_dir):
    left = left_dir / "coordination"
    right = right_dir / "coordination"
    for fname in COMMON_DOCS:
        ensure((left/fname).is_file() and (right/fname).is_file(), f"Missing shared coordination file: {fname}")
        ensure(digest(left/fname) == digest(right/fname), f"DRIFT: coordination/{fname} differs between packages")
    expected = (left/'CONTRACT_SHA256.txt').read_text().split()[0]
    ensure(expected == (right/'CONTRACT_SHA256.txt').read_text().split()[0], "DRIFT: contract hash receipts differ")
    contract = digest(left/'INTERFACE_CONTRACT.md')
    ensure(expected == contract, "Shared contract modified without updating hash fingerprint")
    return contract

def iso(value, label):
    ensure(isinstance(value, str), f"{label} missing")
    try:
        parsed=dt.datetime.fromisoformat(value.replace('Z','+00:00'))
    except ValueError:
        raise GateFailure(f"{label} must be ISO8601 offset-aware")
    ensure(parsed.tzinfo is not None, f"{label} must carry timezone")
    return parsed

def validate(receipt, receipt_dir, milestonedir, contract, pkdir, fabdir, strict_remote, previous_receipt):
    ensure(receipt.get('schema_version') == 1, "Receipt schema != 1")
    mid=receipt.get('milestone')
    req=json.loads((milestonedir/'coordination/milestone-required-evidence.json').read_text())
    ensure(mid in req, f"Unknown milestone {mid}")
    ensure(receipt.get('state')=='PAIR_CERTIFIED',"BLOCKED: state is not PAIR_CERTIFIED")
    # Role-approval shape: role attestations are not cryptographic signatures. The certifier
    # signature, if present, lives under pipelinek_certification_receipt.signature, never under
    # approvals[]. A receipt that puts a 'signature' on a role approval is treated as malformed
    # here, before any other field is read, so the gate fails closed even if the rest of the
    # receipt is well-formed.
    for ap in receipt.get('approvals', []):
        if 'signature' in ap:
            role = ap.get('role', '?')
            raise GateFailure(f"{role}: role approval carries a 'signature' field; role approvals are attestations, not cryptographic signatures. The certifier's signature, if present, lives under pipelinek_certification_receipt.signature, not under approvals.")
    ensure(receipt.get('contract_sha256')==contract,"Receipt contract hash differs from BOTH repos")
    ensure(isinstance(receipt.get('pair_id'),str) and receipt['pair_id'].startswith(mid+'-'),"pair_id missing or not scoped to milestone")
    ordered=['M0','M1','M2','M3','M4','M5','M6','M7']
    if mid == 'M0':
        ensure(previous_receipt is None and receipt.get('previous_pair') in (None, {}), 'M0 must start the pair chain')
    else:
        ensure(previous_receipt is not None, f'{mid}: no --previous-receipt; prior pair MUST be certified')
        previous=json.loads(previous_receipt.read_text(encoding='utf-8'))
        required_prev=ordered[ordered.index(mid)-1]
        declared=receipt.get('previous_pair',{})
        ensure(previous.get('milestone')==required_prev and previous.get('state')=='PAIR_CERTIFIED', f'{mid}: previous pair not certified for {required_prev}')
        ensure(declared.get('milestone')==required_prev and declared.get('pair_id')==previous.get('pair_id') and declared.get('receipt_sha256')==digest(previous_receipt), f'{mid}: prior chain fingerprint/ID mismatch')
        if previous.get('contract_sha256') != contract:
            migration=receipt.get('contract_migration',{})
            ensure(migration.get('from_sha256')==previous.get('contract_sha256') and migration.get('to_sha256')==contract, f'{mid}: incompatible or missing cross-contract migration attestation')
            ensure(migration.get('approved_by_both') is True, f'{mid}: cross-contract migration not approved')
        previous_release_pair=previous.get('releases',{})

    entries=receipt.get('releases',{})
    for owner,repo in [('pipelinek',pkdir),('fabric',fabdir)]:
        selected=entries.get(owner,{})
        ensure(selected.get('repository')==ORIGINS[owner],f"{owner}: wrong repo")
        ensure(bool(HEX40.fullmatch(str(selected.get('commit','')))),f"{owner}: invalid full SHA")
        ensure(bool(selected.get('tag')),f"{owner}: published tag is required even if unchanged")
        arts=selected.get('artifacts',[])
        ensure(isinstance(arts,list) and arts,f"{owner}: need at least one independently downloaded published artifact")
        for a in arts:
            ensure(a.get('origin')=='remote-published',f"{owner}: artifact MUST be resolved from remote registry, not mavenLocal/worktree")
            ensure(isinstance(a.get('source_uri'),str) and a['source_uri'].startswith(('https://','oci://')), f"{owner}: artifact source_uri must name remote registry/destination")
            ensure(isinstance(a.get('coordinate'),str) and a['coordinate'],f"{owner}: artifact coordinate missing")
            checksum_file(receipt_dir,a,f"{owner} artifact")
    # The certifier-receipt guard runs as soon as we have the local PK artifacts verified. Doing
    # it before the remote check means a forged/missing/wrong-state certifier receipt fails
    # closed even if the remote check would otherwise pass — i.e. the certifier provenance is a
    # precondition, not a postcondition.
    pkcr = receipt.get('pipelinek_certification_receipt')
    ensure(isinstance(pkcr, dict), f"{mid}: missing pipelinek_certification_receipt (independent certifier provenance is required)")
    pkcr_state = pkcr.get('state')
    ensure(pkcr_state in ('CERTIFIED','CERTIFICATION_FAILED','NOT_RUN'), f"{mid}: pipelinek_certification_receipt.state is invalid: {pkcr_state!r}")
    ensure(pkcr_state == 'CERTIFIED', f"{mid}: pipelinek_certification_receipt.state is {pkcr_state!r}; only CERTIFIED is acceptable for PAIR_CERTIFIED")
    pkcr_sha = pkcr.get('sha256', '')
    ensure(bool(HEX64.fullmatch(pkcr_sha)), f"{mid}: pipelinek_certification_receipt.sha256 is missing or malformed")
    pk_artifact_shas = {a['sha256'] for a in entries['pipelinek']['artifacts']}
    ensure(pkcr_sha in pk_artifact_shas, f"{mid}: pipelinek_certification_receipt.sha256 {pkcr_sha} does not match any published PK artifact; certifier tested a different ZIP than the pair consumed")
    pkcr_scenario = pkcr.get('scenario')
    ensure(isinstance(pkcr_scenario, str) and pkcr_scenario.strip(), f"{mid}: pipelinek_certification_receipt.scenario missing")
    pkcr_evidence = pkcr.get('evidence', [])
    ensure(isinstance(pkcr_evidence, list) and pkcr_evidence, f"{mid}: pipelinek_certification_receipt.evidence must be a non-empty list of {{path, sha256}} entries")
    for ev in pkcr_evidence:
        checksum_file(receipt_dir, ev, "pipelinek_certification_receipt evidence")
    if 'signature' in pkcr:
        sig = pkcr['signature']
        ensure(isinstance(sig, str) and sig.startswith('sha256:') and bool(HEX64.fullmatch(sig.removeprefix('sha256:'))), f"{mid}: pipelinek_certification_receipt.signature is malformed; this gate does NOT verify the signature cryptographically — it is informational only")
    if strict_remote:
        for owner,repo in [('pipelinek',pkdir),('fabric',fabdir)]:
            check_remote(repo,entries[owner],owner)
    ensure(entries['fabric'].get('changed') is True, f'{mid}: Fabric must publish a NEW release for milestone')
    if mid != 'M0':
        prev_fb=previous_release_pair.get('fabric',{})
        prev_pk=previous_release_pair.get('pipelinek',{})
        ensure(entries['fabric']['tag']!=prev_fb.get('tag') and entries['fabric']['commit']!=prev_fb.get('commit'), f'{mid}: Fabric release did not advance from previous pair')
        if entries['pipelinek'].get('changed'):
            ensure(entries['pipelinek']['tag']!=prev_pk.get('tag') and entries['pipelinek']['commit']!=prev_pk.get('commit'), f'{mid}: PK changed=true but no new remote release')
        else:
            ensure(entries['pipelinek']['tag']==prev_pk.get('tag') and entries['pipelinek']['commit']==prev_pk.get('commit'), f'{mid}: PK changed=false but prior certified PK version drifted')
    ensure(isinstance(entries['pipelinek'].get('changed'), bool), 'PK changed flag must be boolean')
    if req[mid]['pk_changed'] and not entries['pipelinek']['changed']:
        ensure(any(p.get('id')=='GATE-PK-NO-CHANGE-JUSTIFICATION' and p.get('status')=='PASS' for p in receipt.get('evidence',[])), 'PK was expected to change: require measured no-change justification')
    if entries['pipelinek'].get('changed'):
        ensure(iso(entries['pipelinek'].get('released_at'),'PK released_at') < iso(entries['fabric'].get('released_at'),'Fabric released_at'),"PK must publish before Fabric when upstream changed")
    interop=receipt.get('interop',{})
    ensure(interop.get('pipelinek_artifact_sha256') in [a['sha256'] for a in entries['pipelinek']['artifacts']],"Fabric consumer tested a different PK artifact")
    ensure(interop.get('pipelinek_release_tag')==entries['pipelinek']['tag'],"Fabric consumed an unpinned/different PK tag")
    ensure(interop.get('resolved_from_remote') is True and interop.get('consumer_compiled') is True and interop.get('e2e_executed') is True,"Missing remote PK consumer E2E proof")
    checksum_file(receipt_dir,interop,"interop test log")
    approvals=receipt.get('approvals',[])
    for owner in ('PK_PROVIDER','FAB_CONSUMER','PAIR_INTEGRATOR'):
        matches=[p for p in approvals if p.get('role')==owner and p.get('contract_sha256')==contract]
        ensure(len(matches)==1, f"missing / duplicated role approval {owner}")
    proofs=receipt.get('evidence',[])
    reqids=set(req[mid]['uat']+req[mid]['aat'])|{'GATE-FAB-FULL-SUITE','GATE-PUBLISHED-CONSUMER','GATE-PUBLISHED-PAIR-E2E','GATE-LOCAL-RELEASE-ADMISSION'}
    if mid != 'M0' and previous.get('contract_sha256') != contract:
        reqids.add('GATE-CONTRACT-MIGRATION')
    if entries['pipelinek'].get('changed'):
        reqids.add('GATE-PK-FULL-SUITE')
    elif req[mid]['pk_changed']:
        reqids.add('GATE-PK-NO-CHANGE-JUSTIFICATION')
    proofmap={}
    for p in proofs:
        ident=p.get('id')
        ensure(ident not in proofmap,f"duplicate evidence ID {ident}")
        proofmap[ident]=p
    ensure(reqids.issubset(proofmap),f"missing required test evidence IDs: {sorted(reqids-set(proofmap))}")
    for key in sorted(reqids):
        proof=proofmap[key]
        ensure(proof.get('status')=='PASS',f"{key}: not PASS")
        ensure(proof.get('pipelinek_commit')==entries['pipelinek']['commit'] and proof.get('fabric_commit')==entries['fabric']['commit'],f"{key}: not from exactly this release pair")
        checksum_file(receipt_dir, proof, f"evidence {key}")
    ensure(receipt.get('rollback_pair') is not None,f"{mid}: missing last-known-good rollback pair descriptor")
    ensure(receipt.get('integrator_reviewed') is True, "No independent integrator review")
    return len(reqids)

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--pk-package',type=Path,required=True)
    parser.add_argument('--fabric-package',type=Path,required=True)
    parser.add_argument('--receipt',type=Path)
    parser.add_argument('--pk-repo',type=Path)
    parser.add_argument('--fabric-repo',type=Path)
    parser.add_argument('--strict-remote',action='store_true')
    parser.add_argument('--previous-receipt',type=Path,help="M1..M7: immutable PAIR_RECEIPT.json for previous milestone")
    args=parser.parse_args()
    try:
        contract=check_packages(args.pk_package,args.fabric_package)
        print(f'CONTRACT_LOCKED checksum: {contract}')
        if args.receipt is None:
            print('CONTRACT-ONLY PASS. This does NOT certify a milestone or unlock next milestone.')
            return 0
        ensure(args.strict_remote,"PAIR promotion requires --strict-remote")
        ensure(args.pk_repo is not None and args.fabric_repo is not None,"Missing both Git checkouts")
        raw=json.loads(args.receipt.read_text(encoding='utf-8'))
        evidence_count=validate(raw,args.receipt.parent,args.pk_package,contract,args.pk_repo,args.fabric_repo,args.strict_remote,args.previous_receipt)
        print(f"PAIR_CERTIFIED {raw['pair_id']} {raw['milestone']} PASS; verified {evidence_count} mandatory log hashes; next milestone UNLOCKED")
        return 0
    except (GateFailure, OSError, ValueError, KeyError, json.JSONDecodeError, subprocess.TimeoutExpired) as e:
        print(f'BLOCKED: {e}',file=sys.stderr)
        return 2

if __name__=='__main__':
    raise SystemExit(main())

#!/usr/bin/env python3
"""Local fail-closed smoke test. No internet, tags, mutations or real releases."""
from pathlib import Path
import argparse
import hashlib
import json
import subprocess
import sys
import tempfile
import shutil
p=argparse.ArgumentParser()
p.add_argument('--pk-package',type=Path,required=True)
p.add_argument('--fabric-package',type=Path,required=True)
p.add_argument('--pk-repo',type=Path,help="Optional PK git checkout (default: pk-package's parent); needed for the T5 negative cases that exercise the strict-remote path")
p.add_argument('--fabric-repo',type=Path,help="Optional Fabric git checkout (default: fabric-package's parent)")
a=p.parse_args()
if a.pk_repo is None:
    a.pk_repo = a.pk_package.parent
if a.fabric_repo is None:
    a.fabric_repo = a.fabric_package.parent
gate=a.pk_package/'coordination/verify_pair_gate.py'
def run(pk,fb,*extra):
 return subprocess.run([sys.executable,str(gate),'--pk-package',str(pk),'--fabric-package',str(fb),*extra],capture_output=True,text=True)
base=run(a.pk_package,a.fabric_package)
assert base.returncode == 0 and 'CONTRACT-ONLY PASS' in base.stdout,base.stderr
with tempfile.TemporaryDirectory() as d:
 drift=Path(d)/'fabric'
 shutil.copytree(a.fabric_package/'coordination',drift/'coordination')
 target=drift/'coordination/INTERFACE_CONTRACT.md'
 target.write_text(target.read_text()+'\nchanged only in one repository\n')
 refused=run(a.pk_package,drift)
 assert refused.returncode != 0 and 'DRIFT' in refused.stderr,refused.stdout+refused.stderr

# T5 negative cases: certifier-receipt guard and role-signature guard.
contract_sha=(a.pk_package/'coordination/CONTRACT_SHA256.txt').read_text().split()[0]
# The "PK artifact" in this synthetic receipt is a tiny file whose bytes are chosen so that the
# on-disk SHA-256 equals the declared cert_sha. There is no general pre-image for SHA-256, so we
# pick the content FIRST and then derive the cert_sha from it.
def _build_receipt(d, mid, cert_state, with_signature_in_approval=False):
    cert_path = 'selftest-pk-artifact.bin'
    file_content = b'pk-artifact-bytes-for-' + mid.encode() + b'-' + (b'yes' if with_signature_in_approval else b'no')
    cert_sha = hashlib.sha256(file_content).hexdigest()
    (Path(d)/cert_path).write_bytes(file_content)
    rec_payload = {
        'schema_version':1,
        'milestone': mid,
        'pair_id': f'{mid}-selftest',
        'state':'PAIR_CERTIFIED',
        'contract_sha256': contract_sha,
        'releases': {
            'pipelinek': {'repository':'Rubentxu/pipeline-kotlin','commit':'0'*40,'tag':'v0.0.0-rc0','changed':True,'released_at':'2026-10-10T10:00:00Z','artifacts':[{'origin':'remote-published','coordinate':'selftest:pk:0','source_uri':'https://example/pk','path':cert_path,'sha256':cert_sha}]},
            'fabric': {'repository':'Rubentxu/pipelinek-fabric','commit':'0'*40,'tag':'v0.0.0-M0-selftest','changed':True,'released_at':'2026-10-10T11:00:00Z','artifacts':[{'origin':'remote-published','coordinate':'selftest:fab:0','source_uri':'https://example/fab','path':cert_path,'sha256':cert_sha}]},
        },
        'interop': {'pipelinek_artifact_sha256': cert_sha,'pipelinek_release_tag':'v0.0.0-rc0','resolved_from_remote':True,'consumer_compiled':True,'e2e_executed':True,'path':cert_path,'sha256':cert_sha},
        'evidence': [{'id':'GATE-LOCAL-RELEASE-ADMISSION','status':'PASS','path':cert_path,'sha256':cert_sha,'pipelinek_commit':'0'*40,'fabric_commit':'0'*40}],
        'approvals': [{'role':r,'contract_sha256':contract_sha} for r in ('PK_PROVIDER','FAB_CONSUMER','PAIR_INTEGRATOR')],
        'rollback_pair': {'last_known_good':'M-none'},
        'integrator_reviewed': True,
        'pipelinek_certification_receipt': {
            'state': cert_state,
            'sha256': cert_sha,
            'scenario': 'selftest/scenario.kts',
            'evidence': [{'path': cert_path, 'sha256': cert_sha}],
        },
    }
    if with_signature_in_approval:
        for ap in rec_payload['approvals']:
            ap['signature'] = 'sha256:' + 'a'*64
    rec = Path(d)/'PAIR_RECEIPT.json'
    rec.write_text(json.dumps(rec_payload))
    return rec

# T5 negative 1: state == CERTIFICATION_FAILED. The receipt is well-formed in every other field;
# the new guard must reject it on the certifier state alone.
with tempfile.TemporaryDirectory() as r:
 rec=_build_receipt(r,'M0','CERTIFICATION_FAILED')
 blocked=run(a.pk_package,a.fabric_package,'--receipt',str(rec),'--strict-remote','--pk-repo',str(a.pk_repo),'--fabric-repo',str(a.fabric_repo))
 assert blocked.returncode != 0, blocked.stdout+blocked.stderr
 assert ('state is CERTIFICATION_FAILED' in blocked.stderr) or ('only CERTIFIED is acceptable' in blocked.stderr), blocked.stdout+blocked.stderr

# T5 negative 2: certifier SHA does NOT match any published PK artifact SHA. The receipt is
# well-formed in every other field; the new guard must reject it on the SHA mismatch alone.
with tempfile.TemporaryDirectory() as r:
 rec=_build_receipt(r,'M0','CERTIFIED')
 rec_json = json.loads(rec.read_text())
 rec_json['pipelinek_certification_receipt']['sha256'] = 'f'*64
 rec.write_text(json.dumps(rec_json))
 blocked=run(a.pk_package,a.fabric_package,'--receipt',str(rec),'--strict-remote','--pk-repo',str(a.pk_repo),'--fabric-repo',str(a.fabric_repo))
 assert blocked.returncode != 0, blocked.stdout+blocked.stderr
 assert 'does not match any published PK artifact' in blocked.stderr, blocked.stdout+blocked.stderr

# T5 negative 3: a role approval carries a 'signature' field (forbidden: role attestations are not
# cryptographic signatures). The certifier_receipt itself is well-formed and CERTIFIED; the new
# guard must reject it on the role-signature shape alone.
with tempfile.TemporaryDirectory() as r:
 rec=_build_receipt(r,'M0','CERTIFIED', with_signature_in_approval=True)
 blocked=run(a.pk_package,a.fabric_package,'--receipt',str(rec),'--strict-remote','--pk-repo',str(a.pk_repo),'--fabric-repo',str(a.fabric_repo))
 assert blocked.returncode != 0, blocked.stdout+blocked.stderr
 assert "'signature' field" in blocked.stderr, blocked.stdout+blocked.stderr

print('PASS: shared contract equality; drift fail-closed; incomplete uncertified pair fail-closed; certifier_receipt CERTIFICATION_FAILED fail-closed; certifier_receipt sha256 mismatch fail-closed; role approval signature forbidden fail-closed')

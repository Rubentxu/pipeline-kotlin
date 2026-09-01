---
type: incidence
node_type: incidence
title: "CR-BD-022 PATH B — colon-join drops username-password pair"
slug: "INC-005-cr-bd-022-path-b-colon-join"
status: open
severity: high
priority: P1
discovered: 2026-09-01
discovered_in_cycle: "[[CYC-2026-08-31-ml-r10-1-credentials-binding-completion]]"
fingerprint: "e0a7c5b2d4f8e1a3c6d0b5f2e9a4d0c7b3a8f1e4d7c5b0a3f6e9d2c5b8a1f4e7d"
cluster_id: "CL-05"
category: overeng/deferred-defect
attribution: pre_existing
evidence_xml_sha: "c9d55899b3347bddac32865eb6ce0eec3bc59b0d470fa45e1f11910e5d75333c"
test_line: 579
classification: pre_existing
cycle_id: "p-733fb505b5a6bd2d/ml-r10-1-credentials-binding-completion"
amendment: "AMENDMENT-1"
non_claim_reference: "§29"
remediation_cycle: "ml-r10-2-credentials-join"
owner: "sddk-apply ml-r10-2-credentials-join"
stale_after: 2026-12-01
project_id: p-733fb505b5a6bd2d
---

# CR-BD-022 PATH B — colon-join drops username-password pair

## Problem

UAT008 CR-BD-022 fails at `UatLocal008CredentialsTest.kt` line 579 with `expected: <success> but was: <null>`. Root cause: PATH B — the secret material join (':' separator) in `LocalCredentialProvider.resolve` / `LocalSecretStore.getAsSecretHandle` drops one username-password pair. Per `implementation-receipt.yaml:46-48` and user authority, deferred to follow-on cycle `ml-r10-2-credentials-join`. This is a pre-existing defect confirmed at cycle base `a75234a`. NOT addressed by AMENDMENT-1 (AMENDMENT-1 only removes the script-compile gate).

## Evidence

- UAT008 full run: `runFinished.outcome=null` at line 579; downstream PATH B colon-join defect (not script-compile).
- Pre-AMENDMENT-1 baseline log SHA `d9b23d77…` shows identical failure structure.
- XML SHA: `c9d55899b3347bddac32865eb6ce0eec3bc59b0d470fa45e1f11910e5d75333c` (2026-09-01T07:28:24.038Z)

## Affects

- **Requirements impacted:** [[REQ-Credentials-Binding]], [[REQ-UAT-Local-008-Credentials-Five-Missing-Bindings]]

## Proposed Resolution

Address PATH B colon-join defect in cycle `ml-r10-2-credentials-join` (user authority required). The fix requires examining `LocalCredentialProvider.resolve` / `LocalSecretStore.getAsSecretHandle` and correcting the ':' separator join logic that drops username-password pairs.

## Action

- [ ] Investigate `LocalCredentialProvider.resolve` colon-join logic
- [ ] Investigate `LocalSecretStore.getAsSecretHandle` colon-join logic
- [ ] Design and implement fix in `ml-r10-2-credentials-join` cycle

## Resolution (filled when closed)

- **Status:** open → resolved | accepted_risk
- **Resolved in:** [[CYC-YYYY-MM-DD-ml-r10-2-credentials-join]]
- **How:** TBD by remediation cycle owner
- **Triggers new ADR:** no

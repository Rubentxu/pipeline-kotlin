---
type: incidence
node_type: incidence
title: "CR-BD-027 CredentialUsed emission count=0 — D-2 H0-path emission gated"
slug: "INC-007-cr-bd-027-credential-used-emission"
status: open
severity: medium
priority: P2
discovered: 2026-09-01
discovered_in_cycle: "[[CYC-2026-08-31-ml-r10-1-credentials-binding-completion]]"
fingerprint: "a2c9e7d4b6f1a3e8c0d5b9a2f6e1c4b7a0d3f8e5c2b9a4f7d1e6c0b3a8f5e2d9c"
cluster_id: "CL-05"
category: overeng/deferred-defect
attribution: pre_existing
evidence_xml_sha: "c9d55899b3347bddac32865eb6ce0eec3bc59b0d470fa45e1f11910e5d75333c"
test_line: 825
classification: pre_existing
cycle_id: "p-733fb505b5a6bd2d/ml-r10-1-credentials-binding-completion"
amendment: "AMENDMENT-1"
non_claim_reference: "§29"
remediation_cycle: "H0-wiring-completion"
owner: "apply phase of recovery round (H0-wiring-completion)"
stale_after: 2026-12-01
project_id: p-733fb505b5a6bd2d
---

# CR-BD-027 CredentialUsed emission count=0 — D-2 H0-path emission gated

## Problem

UAT008 CR-BD-027 fails at `UatLocal008CredentialsTest.kt` line 825 with `Should have at least 2 CredentialUsed events. Got: 0`. D-2 H0-path `CredentialUsed` emission is gated on the success path which is not enabled in the test infrastructure. Per non-claim §29, this is pre-existing D-2 emission gating. This is a pre-existing defect confirmed at cycle base `a75234a`. NOT addressed by AMENDMENT-1.

## Evidence

- UAT008 full run: `CredentialUsed` events emitted count = 0 instead of >= 2 at line 825.
- XML SHA: `c9d55899b3347bddac32865eb6ce0eec3bc59b0d470fa45e1f11910e5d75333c` (2026-09-01T07:28:24.038Z)

## Affects

- **Requirements impacted:** [[REQ-Credentials-Binding]], [[REQ-UAT-Local-008-Credentials-Five-Missing-Bindings]]

## Proposed Resolution

Enable D-2 H0-path `CredentialUsed` emission per binding per SUCCESS inner step. Tracked together with H0-wiring-completion. The emission is gated because the H0 binding success path is not enabled in the test infra (same root cause as CR-BD-018/019/020/021).

## Action

- [ ] Enable H0 binding success path in UAT008 test infra (unblocks emission)
- [ ] Confirm `CredentialUsed` events are emitted correctly per binding

## Resolution (filled when closed)

- **Status:** open → resolved | accepted_risk
- **Resolved in:** [[CYC-YYYY-MM-DD-ml-rXX-h0-wiring-completion]]
- **How:** TBD by remediation cycle owner
- **Triggers new ADR:** no

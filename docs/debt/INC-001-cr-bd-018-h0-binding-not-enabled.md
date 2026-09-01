---
type: incidence
node_type: incidence
title: "CR-BD-018 sshUserPrivateKey binding — H0 not enabled in test infra"
slug: "INC-001-cr-bd-018-h0-binding-not-enabled"
status: open
severity: high
priority: P2
discovered: 2026-09-01
discovered_in_cycle: "[[CYC-2026-08-31-ml-r10-1-credentials-binding-completion]]"
fingerprint: "a4c2e1d8b7f5a3c9e2d6b1f8a4c7e3d0b9a6f2c5e8d1b4a7f0c3e6d9b2a5f8c1"
cluster_id: "CL-05"
category: overeng/deferred-defect
attribution: pre_existing
evidence_xml_sha: "c9d55899b3347bddac32865eb6ce0eec3bc59b0d470fa45e1f11910e5d75333c"
test_line: 375
classification: pre_existing
cycle_id: "p-733fb505b5a6bd2d/ml-r10-1-credentials-binding-completion"
amendment: "AMENDMENT-1"
non_claim_reference: "§29"
remediation_cycle: "H0-wiring-completion"
owner: "apply phase of recovery round (H0-wiring-completion)"
stale_after: 2026-12-01
project_id: p-733fb505b5a6bd2d
---

# CR-BD-018 sshUserPrivateKey binding — H0 not enabled in test infra

## Problem

UAT008 CR-BD-018 fails at `UatLocal008CredentialsTest.kt` line 375 with `expected: <success> but was: <null>`. The H0 wiring (v0.24.0) is not enabled by the UAT008 test infrastructure, so the binding success path cannot be exercised. This is a pre-existing defect at cycle base `a75234a`, confirmed by worktree evidence (sha256:uat008-cr-bd-018-pre-existing). NOT addressed by AMENDMENT-1 — AMENDMENT-1 only removes the script-compile gate; binding semantics are downstream executor territory.

## Evidence

- UAT008 full run: 18 PASS / 8 FAIL / 1 SKIP (CR-BD-034). CR-BD-018 fails at line 375 assertion on `runFinished.outcome=null`.
- XML SHA: `c9d55899b3347bddac32865eb6ce0eec3bc59b0d470fa45e1f11910e5d75333c` (2026-09-01T07:28:24.038Z)
- Log SHA: `2ee73ae9…`

## Affects

- **Requirements impacted:** [[REQ-Credentials-Binding]], [[REQ-UAT-Local-008-Credentials-Five-Missing-Bindings]]

## Proposed Resolution

Enable H0 binding success path in UAT008 test infra. H0 wiring (v0.24.0) must be activated so that the `sshUserPrivateKey` binding executor path can reach a successful outcome. Out of AMENDMENT-1 scope; tracked as H0-wiring-completion follow-on.

## Action

- [ ] Enable H0 binding success path in UAT008 test infra
- [ ] Re-run UAT008 to confirm CR-BD-018 passes

## Resolution (filled when closed)

- **Status:** open → resolved | accepted_risk
- **Resolved in:** [[CYC-YYYY-MM-DD-ml-rXX-h0-wiring-completion]]
- **How:** TBD by remediation cycle owner
- **Triggers new ADR:** no

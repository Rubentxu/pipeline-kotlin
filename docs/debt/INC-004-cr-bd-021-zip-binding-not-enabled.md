---
type: incidence
node_type: incidence
title: "CR-BD-021 ZIP binding — H0 not enabled in test infra"
slug: "INC-004-cr-bd-021-zip-binding-not-enabled"
status: open
severity: high
priority: P2
discovered: 2026-09-01
discovered_in_cycle: "[[CYC-2026-08-31-ml-r10-1-credentials-binding-completion]]"
fingerprint: "d9f6b4a3c0e7b1f5a8d3c6e2b9a4f0d7c5b1e8a3f6d0c4b9a2f5e7d1c8b3a6f0e"
cluster_id: "CL-05"
category: overeng/deferred-defect
attribution: pre_existing
evidence_xml_sha: "c9d55899b3347bddac32865eb6ce0eec3bc59b0d470fa45e1f11910e5d75333c"
test_line: 530
classification: pre_existing
cycle_id: "p-733fb505b5a6bd2d/ml-r10-1-credentials-binding-completion"
amendment: "AMENDMENT-1"
non_claim_reference: "§29"
remediation_cycle: "H0-wiring-completion"
owner: "apply phase of recovery round (H0-wiring-completion)"
stale_after: 2026-12-01
project_id: p-733fb505b5a6bd2d
---

# CR-BD-021 ZIP binding — H0 not enabled in test infra

## Problem

UAT008 CR-BD-021 fails at `UatLocal008CredentialsTest.kt` line 530 with `expected: <success> but was: <null>`. Same root cause as CR-BD-018/019/020: the H0 wiring (v0.24.0) is not enabled by the UAT008 test infrastructure, so the ZIP binding success path cannot be exercised. This is a pre-existing defect at cycle base `a75234a`, confirmed by worktree evidence. NOT addressed by AMENDMENT-1.

## Evidence

- UAT008 full run: identical structure to CR-BD-018 (`runFinished.outcome=null` at line 530).
- XML SHA: `c9d55899b3347bddac32865eb6ce0eec3bc59b0d470fa45e1f11910e5d75333c` (2026-09-01T07:28:24.038Z)

## Affects

- **Requirements impacted:** [[REQ-Credentials-Binding]], [[REQ-UAT-Local-008-Credentials-Five-Missing-Bindings]]

## Proposed Resolution

Enable H0 binding success path. Tracked together with CR-BD-018/019/020 under H0-wiring-completion.

## Action

- [ ] Enable H0 binding success path in UAT008 test infra (same fix as CR-BD-018/019/020)
- [ ] Re-run UAT008 to confirm CR-BD-021 passes

## Resolution (filled when closed)

- **Status:** open → resolved | accepted_risk
- **Resolved in:** [[CYC-YYYY-MM-DD-ml-rXX-h0-wiring-completion]]
- **How:** TBD by remediation cycle owner
- **Triggers new ADR:** no

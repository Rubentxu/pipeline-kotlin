---
type: incidence
node_type: incidence
title: "CR-BD-020 CERTIFICATE binding — H0 not enabled in test infra"
slug: "INC-003-cr-bd-020-certificate-binding-not-enabled"
status: open
severity: high
priority: P2
discovered: 2026-09-01
discovered_in_cycle: "[[CYC-2026-08-31-ml-r10-1-credentials-binding-completion]]"
fingerprint: "c8e5a3f2b7d1c6a4e9f0b5d8a2c7f1e6b3a8d4c9f5e2b7a0c6d3f8a1b4e7c0d9"
cluster_id: "CL-05"
category: overeng/deferred-defect
attribution: pre_existing
evidence_xml_sha: "c9d55899b3347bddac32865eb6ce0eec3bc59b0d470fa45e1f11910e5d75333c"
test_line: 481
classification: pre_existing
cycle_id: "p-733fb505b5a6bd2d/ml-r10-1-credentials-binding-completion"
amendment: "AMENDMENT-1"
non_claim_reference: "§29"
remediation_cycle: "H0-wiring-completion"
owner: "apply phase of recovery round (H0-wiring-completion)"
stale_after: 2026-12-01
project_id: p-733fb505b5a6bd2d
---

# CR-BD-020 CERTIFICATE binding — H0 not enabled in test infra

## Problem

UAT008 CR-BD-020 fails at `UatLocal008CredentialsTest.kt` line 481 with `expected: <success> but was: <null>`. Same root cause as CR-BD-018/019: the H0 wiring (v0.24.0) is not enabled by the UAT008 test infrastructure, so the CERTIFICATE binding success path cannot be exercised. This is a pre-existing defect at cycle base `a75234a`, confirmed by worktree evidence. NOT addressed by AMENDMENT-1.

## Evidence

- UAT008 full run: identical structure to CR-BD-018 (`runFinished.outcome=null` at line 481).
- XML SHA: `c9d55899b3347bddac32865eb6ce0eec3bc59b0d470fa45e1f11910e5d75333c` (2026-09-01T07:28:24.038Z)

## Affects

- **Requirements impacted:** [[REQ-Credentials-Binding]], [[REQ-UAT-Local-008-Credentials-Five-Missing-Bindings]]

## Proposed Resolution

Enable H0 binding success path. Tracked together with CR-BD-018/019 under H0-wiring-completion.

## Action

- [ ] Enable H0 binding success path in UAT008 test infra (same fix as CR-BD-018/019)
- [ ] Re-run UAT008 to confirm CR-BD-020 passes

## Resolution (filled when closed)

- **Status:** open → resolved | accepted_risk
- **Resolved in:** [[CYC-YYYY-MM-DD-ml-rXX-h0-wiring-completion]]
- **How:** TBD by remediation cycle owner
- **Triggers new ADR:** no

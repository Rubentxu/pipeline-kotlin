---
type: incidence
node_type: incidence
title: "CR-BD-032 nested withCredentials — outer binding shadowed after inner restore"
slug: "INC-008-cr-bd-032-nested-restoration"
status: open
severity: high
priority: P1
discovered: 2026-09-01
discovered_in_cycle: "[[CYC-2026-08-31-ml-r10-1-credentials-binding-completion]]"
fingerprint: "b3d0f8e5c7a2b4f9d1e6c0b3a7f2e5d8c1b4a9f0e3d6c7b2a5f1e4d0c3b8a7f5e"
cluster_id: "CL-05"
category: overeng/deferred-defect
attribution: pre_existing
evidence_xml_sha: "c9d55899b3347bddac32865eb6ce0eec3bc59b0d470fa45e1f11910e5d75333c"
test_line: 1061
classification: pre_existing
cycle_id: "p-733fb505b5a6bd2d/ml-r10-1-credentials-binding-completion"
amendment: "AMENDMENT-1"
non_claim_reference: "§29"
remediation_cycle: "ml-r10-4-nested-restoration"
owner: "sddk-apply ml-r10-4-nested-restoration"
stale_after: 2026-12-01
project_id: p-733fb505b5a6bd2d
---

# CR-BD-032 nested withCredentials — outer binding shadowed after inner restore

## Problem

UAT008 CR-BD-032 fails at `UatLocal008CredentialsTest.kt` line 1061 with `Outer binding should be visible: expected: <true> but was: <false>`. Nested `withCredentials`: inner step shadows outer binding because `envModel` reset on inner restore does not re-apply outer bindings. Per user authority, deferred to `ml-r10-4-nested-restoration`. This is a pre-existing defect confirmed at cycle base `a75234a`. NOT addressed by AMENDMENT-1.

## Evidence

- UAT008 full run: outer binding visibility = `false` at line 1061; inner `withCredentials` restored envModel but did not re-apply outer bindings.
- XML SHA: `c9d55899b3347bddac32865eb6ce0eec3bc59b0d470fa45e1f11910e5d75333c` (2026-09-01T07:28:24.038Z)

## Affects

- **Requirements impacted:** [[REQ-Credentials-Binding]], [[REQ-UAT-Local-008-Credentials-Five-Missing-Bindings]]

## Proposed Resolution

Fix nested `withCredentials` restoration in cycle `ml-r10-4-nested-restoration` (user authority required): on inner restore, re-apply outer bindings without overwriting user mutations. The `effectiveShOptions` lifecycle must be handled in-place (not by-value `Map.plus`) to preserve outer binding visibility.

## Action

- [ ] Investigate `envModel` restoration logic in nested `withCredentials` blocks
- [ ] Design fix to re-apply outer bindings on inner restore
- [ ] Design and implement fix in `ml-r10-4-nested-restoration` cycle

## Resolution (filled when closed)

- **Status:** open → resolved | accepted_risk
- **Resolved in:** [[CYC-YYYY-MM-DD-ml-r10-4-nested-restoration]]
- **How:** TBD by remediation cycle owner
- **Triggers new ADR:** no

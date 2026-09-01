---
type: incidence
node_type: incidence
title: "CR-BD-026 StepStarted event missing — audit ordering defect"
slug: "INC-006-cr-bd-026-audit-ordering"
status: open
severity: medium
priority: P2
discovered: 2026-09-01
discovered_in_cycle: "[[CYC-2026-08-31-ml-r10-1-credentials-binding-completion]]"
fingerprint: "f1b8d6c3e7f4a2d8c1e5b9f3a6d0c4b8e1f5a9d3c6b2a7f0e4d8c1b5a9f3e6d2c"
cluster_id: "CL-05"
category: overeng/deferred-defect
attribution: pre_existing
evidence_xml_sha: "c9d55899b3347bddac32865eb6ce0eec3bc59b0d470fa45e1f11910e5d75333c"
test_line: 775
classification: pre_existing
cycle_id: "p-733fb505b5a6bd2d/ml-r10-1-credentials-binding-completion"
amendment: "AMENDMENT-1"
non_claim_reference: "§29"
remediation_cycle: "ml-r10-3-audit-ordering"
owner: "sddk-apply ml-r10-3-audit-ordering"
stale_after: 2026-12-01
project_id: p-733fb505b5a6bd2d
---

# CR-BD-026 StepStarted event missing — audit ordering defect

## Problem

UAT008 CR-BD-026 fails at `UatLocal008CredentialsTest.kt` line 775 with `StepStarted event must be present: expected: not <null>`. Audit ordering defect: the `StepStarted` event is emitted after the audited operation, not before. Per user authority, deferred to `ml-r10-3-audit-ordering`. This is a pre-existing defect confirmed at cycle base `a75234a`. NOT addressed by AMENDMENT-1.

## Evidence

- UAT008 full run: `assertNotNull` on `StepStarted` event at line 775 fails; no `StepStarted` event in audit stream.
- XML SHA: `c9d55899b3347bddac32865eb6ce0eec3bc59b0d470fa45e1f11910e5d75333c` (2026-09-01T07:28:24.038Z)

## Affects

- **Requirements impacted:** [[REQ-UAT-Local-008-Credentials-Five-Missing-Bindings]]

## Proposed Resolution

Fix audit ordering in cycle `ml-r10-3-audit-ordering` (user authority required): emit `StepStarted` before the audited operation. The root cause is either in the executor emit ordering or in `InMemoryEventStore` sequence assignment.

## Action

- [ ] Investigate audit event emission ordering in executor
- [ ] Investigate InMemoryEventStore sequence assignment
- [ ] Design and implement fix in `ml-r10-3-audit-ordering` cycle

## Resolution (filled when closed)

- **Status:** open → resolved | accepted_risk
- **Resolved in:** [[CYC-YYYY-MM-DD-ml-r10-3-audit-ordering]]
- **How:** TBD by remediation cycle owner
- **Triggers new ADR:** no

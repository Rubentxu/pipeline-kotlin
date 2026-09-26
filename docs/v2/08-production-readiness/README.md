# Production Readiness Action Plan — PipelineK

**Source:** technical audit of `Rubentxu/pipeline-kotlin` over `main @ acc903875d70f939713786d71a6331bb6ccf7dc9`.

**Purpose:** convert the audit findings into an executable closure plan that can be incorporated directly into the repository.

## Scope

This package does not modify product code. It defines the work required to:

1. restore a single verifiable current-state view;
2. close the open RP-5 product gate;
3. remove ambiguity between historical receipts and current certification;
4. reconcile open PRs and dependency drift;
5. harden CI/release admission;
6. reduce the architectural concentration in `CanonicalDurableRunCoordinator`;
7. establish measurable exit criteria for "production ready".

## Reading order

1. `EXECUTIVE_SUMMARY.md`
2. `PRIORITIZATION_MATRIX.md`
3. `ACTION_CATALOG.md`
4. `EXECUTION_PLAN.md`
5. `TIMELINE.md`
6. `PRODUCTION_READY_GATE.md`
7. `SUCCESS_METRICS.md`
8. `RISK_REGISTER.md`
9. `OPEN_INFORMATION_GAPS.md`
10. `backlog/IMPLEMENTATION_BACKLOG.md`
11. `uat/PR_ACTION_UAT_MATRIX.md`
12. `adr-proposals/*`

## Operating rule

Historical receipts remain immutable evidence for their original SHA. Current state MUST be derived from the exact candidate SHA and must never inherit certification implicitly from a previous SHA.

## Completion definition

The plan is considered complete when:

- the exact release candidate SHA has a fresh full gate;
- all mandatory tests are green or explicitly removed from the supported contract by a normative decision;
- HAR-007 is closed;
- the release artifact is reproducible and externally verified;
- SAST, secret scan, SBOM, dependency/SCA, coverage and targeted mutation checks are current for the candidate;
- two external repositories pass dogfooding;
- the final immutable admission receipt declares `RP-5 PRODUCT_GATE_GO`;
- no current-state document contradicts that receipt.

# Implementation Backlog

## Phase 1

### PR-001 — Current-state projection
- [ ] define input schema
- [ ] define deterministic output schema
- [ ] include HEAD/candidate/release/gate/harness/blockers/next
- [ ] reject conflicting candidate identities
- [ ] create reproducibility test
- [ ] document source precedence

### PR-002 — SESSION_POINTER reduction
- [ ] link generated current view
- [ ] preserve only current SHA, next WU, first command
- [ ] remove narrative history copied from journal

### PR-003 — UAT split
- [ ] retain normative UAT definitions
- [ ] move current result state into generated view
- [ ] ensure one result per UAT per candidate SHA

### PR-004 — PR reconciliation
- [ ] export all open PRs
- [ ] classify product/docs/dependency
- [ ] mark stacks
- [ ] mark superseded
- [ ] close obsolete PRs after evidence review
- [ ] produce immutable reconciliation receipt

### PR-005 — Dependencies
- [ ] Kotlin/toolchain batch
- [ ] Actions batch
- [ ] security-sensitive libraries individually
- [ ] affected tests
- [ ] SCA output

### PR-006 — Admission check
- [ ] define check payload
- [ ] bind candidate SHA + artifact digest + harness verdict digest
- [ ] publish check
- [ ] make it required
- [ ] prove stale verdict fails closed

### PR-007 — Debt
- [ ] reconcile D-001..D-004
- [ ] mark already resolved items
- [ ] link evidence
- [ ] remove stale NEXT priority

## Phase 2

### PR-008 — Full-suite classification
- [ ] run full suite
- [ ] capture XML
- [ ] enumerate every failure
- [ ] classify every failure
- [ ] open focused WU for each REAL_DEFECT
- [ ] remove no test without normative evidence

### PR-009 — HAR-007
- [ ] resolve normative contract
- [ ] RED contract test
- [ ] implementation
- [ ] cwd restore proof
- [ ] failure continuation/abort proof
- [ ] event proof
- [ ] replay proof
- [ ] harness PASS

### PR-010 — Candidate
- [ ] freeze SHA
- [ ] freeze build inputs
- [ ] generate manifest
- [ ] produce artifact
- [ ] record hash

### PR-011 — L5
- [ ] exact candidate run
- [ ] 0 mandatory failures
- [ ] immutable XML bundle
- [ ] receipt

### PR-012 — Security
- [ ] detekt
- [ ] gitleaks
- [ ] SBOM
- [ ] SCA
- [ ] SHA-pin audit

### PR-013 — Coverage
- [ ] Kover aggregate
- [ ] critical package diff
- [ ] coordinator coverage review
- [ ] report

### PR-014 — Mutation
- [ ] replay policies
- [ ] reconcilers
- [ ] codecs
- [ ] failure decisions
- [ ] survivor triage

### PR-015 — Memory
- [ ] controlled host
- [ ] 3+ runs
- [ ] baseline
- [ ] approved SLO
- [ ] candidate PASS

### PR-016 — RP-5 GO
- [ ] two external repositories
- [ ] intentional failure
- [ ] replay/recovery
- [ ] reproducible distribution
- [ ] external harness
- [ ] final receipt
- [ ] admission check PASS

## Phase 3

### PR-017
- [ ] extract lifecycle
- [ ] golden behavior
- [ ] architecture fitness

### PR-018
- [ ] extract body traversal/policies
- [ ] prove retry/timeout/dir/parallel compatibility
- [ ] architecture fitness

### PR-019
- [ ] create InvocationEngine
- [ ] create RecoveryEngine
- [ ] relocate compatibility adapters to composition root
- [ ] ban compatibility seam in coordinator

### PR-020
- [ ] reduce coordinator
- [ ] add constructor/dependency complexity budget
- [ ] add shared-model composition integration suite
- [ ] prove semantic merge gate

### PR-021
- [ ] common permission constants
- [ ] affected module tests

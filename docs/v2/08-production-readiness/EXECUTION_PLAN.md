# Execution Plan

## Phase 1 — Quick Wins / Restore Truth

### Goal

Before changing behavior, restore a single trustworthy operational view and reduce branch ambiguity.

### PR-001 — Materialized current-state projection

**What**

Introduce a generated current-state document that contains only:
- exact HEAD SHA;
- active phase/WU;
- candidate tag/SHA;
- last certified stable release;
- current full-gate state;
- current harness state;
- open blocking defects;
- open PR classifications;
- next executable WU.

**How**

Use repository facts as inputs and render a deterministic Markdown/YAML view. Historical receipts remain unchanged.

**Owner role:** DevOps/release engineer  
**Prerequisites:** repository read access, receipt naming rules, GitHub CLI/API access.  
**Risk:** generator itself becomes another source of truth.  
**Mitigation:** generated file MUST embed source SHAs and fail if required inputs disagree.

**Acceptance**
- same repository state => byte-identical output;
- stale SHA is detected;
- no manually edited status field is required.

---

### PR-002 — Reduce SESSION_POINTER

Convert `.agent/SESSION_POINTER.md` into a small pointer to PR-001 output and the first command to execute.

Do not retain historical narrative already available in `WORK_JOURNAL.md`.

**Owner:** technical lead  
**Acceptance:** <=100 meaningful lines preferred; exact HEAD and next WU machine-checkable.

---

### PR-003 — Split normative UAT matrix from current results

`PRODUCTION_READY_UAT_MATRIX.md` should answer **what must pass**.

A generated current-state view should answer **what passed at the exact candidate SHA**.

Historical updates should stop being appended to the normative table.

**Owner:** QA/test-infrastructure engineer  
**Acceptance:** each UAT has exactly one normative definition and zero ambiguous current states.

---

### PR-004 — PR reconciliation

Create one table for all currently open PRs.

For each:
- purpose;
- dependency;
- superseded by;
- candidate inclusion;
- action = KEEP / STACK / SUPERSEDE / CLOSE / DEPENDENCY.

Special attention:
- workspace/cwd series #90/#95/#96/#97;
- obsolete docs-only WUs;
- Dependabot PRs.

**Owner:** technical lead  
**Acceptance:** no two open product PRs claim the same semantic closure without an explicit stack relationship.

---

### PR-005 — Dependency batches

After PR classification:
- group Kotlin/toolchain updates;
- group GitHub Actions;
- process runtime/security-sensitive libraries separately;
- run affected tests + SCA;
- avoid upgrading all 14 PRs simultaneously.

**Owner:** DevOps + Kotlin engineer  
**Acceptance:** no stale dependency PR older than the agreed maintenance window; SCA result archived.

---

### PR-006 — Admission check

Create one mandatory branch-protection check representing the result of the release admission authority.

The check should not duplicate every test. It should validate:
- candidate SHA;
- harness verdict identity;
- immutable evidence digest;
- final state PASS.

**Owner:** DevOps/release engineer  
**Acceptance:** branch protection refuses merge/promotion when the admission check is absent, stale, failed or belongs to another SHA.

---

### PR-007 — Debt ledger reconciliation

Mark D-002 and any other already-closed item as resolved using current evidence. Do not delete history.

**Owner:** technical lead  
**Acceptance:** every active debt item has a current owner phase and evidence that it is still open.

---

## Phase 2 — Critical Production Readiness Closure

### PR-008 — Classify the 41 failures

Run the exact full suite on the reconciled base.

For each failure assign exactly one class:

1. REAL_DEFECT
2. MANDATORY_CONTRACT
3. LEGACY_UNSUPPORTED
4. FIXTURE_DRIFT
5. OBSOLETE_TEST
6. INFRA_FLAKE

No failure may remain "pre-existing" without disposition.

**Owner:** QA/test-infrastructure + Kotlin engineer.

**Acceptance**
- 41/41 classified;
- mandatory set = 0 unexplained failures;
- removed/disabled tests require explicit evidence why they are not mandatory.

---

### PR-009 — Close HAR-007

The audit only establishes that HAR-007 is open. The implementation choice must preserve the intended public contract and should follow the already proposed typed design path rather than an implicit boolean bag.

Minimum work:
- freeze expected `dir` failure behavior;
- add RED test;
- implement;
- prove cwd restore;
- prove sibling continuation or explicit abort mode as specified;
- prove event/journal/replay semantics;
- execute harness scenario.

**Owner:** senior Kotlin/runtime engineer.

**Risk:** behavior change affects Jenkins parity, body failure semantics or replay.

**Mitigation:** separate ADT/contract tests + binary harness; do not combine with workspace PR merges.

---

### PR-010 — Candidate freeze

Create an immutable candidate identity after PR-008 and PR-009.

Manifest must contain:
- source SHA;
- build inputs;
- artifact hashes;
- contract/profile;
- UAT set.

No functional commits after freeze. Any fix produces a new candidate.

---

### PR-011 — Fresh full gate

Run the complete L5/full suite for the candidate.

Required evidence:
- command;
- start/end;
- exact SHA;
- JDK/OS;
- XML count;
- tests/failures/errors/skips;
- digest of report bundle.

Acceptance: **0 mandatory failures**.

---

### PR-012 — Security and supply chain recertification

For the exact candidate:
- detekt/SAST;
- gitleaks;
- CycloneDX SBOM;
- dependency/SCA result;
- action pin verification.

Do not reuse evidence from a previous SHA.

---

### PR-013 — Candidate coverage

Rebuild Kover aggregation.

Do not set a global coverage increase as the only gate.

Report:
- total line/branch/class;
- critical packages;
- coordinator;
- replay policies;
- reconcilers;
- codecs;
- security/workspace policies.

Acceptance: no material regression from the last accepted baseline without explicit justification.

---

### PR-014 — Targeted mutation

Mutation is limited to high-value decision logic.

Acceptance:
- every surviving mutation in critical policy code has one of:
  - new killing test;
  - proven equivalent;
  - accepted documented risk.

---

### PR-015 — Memory SLO

Repeat the large-output/soak scenario that previously showed ~11 GB RSS.

Define:
- measurement method;
- baseline;
- candidate limit;
- regression budget.

If measurement cannot be stabilized, mark UNKNOWN and keep the performance gate open.

---

### PR-016 — Final RP-5 admission

Run:
- clean install;
- reproducible dist;
- hashes;
- two external repositories;
- intentional failure and recovery;
- external harness;
- final immutable receipt.

Only this action may declare:

`RP-5 PRODUCT_GATE_GO`

---

## Phase 3 — Strategic Hardening

### Design rule

Do not replace one God Object with twenty shallow services.

Target four deep boundaries:

1. `RunLifecycleEngine`
2. `BodyExecutionEngine`
3. `InvocationEngine`
4. `RecoveryEngine`

Internally favor:
- sealed commands;
- sealed decisions;
- explicit effects;
- pure reducers where possible.

### PR-017 — RunLifecycleEngine

Move:
- run open/finish;
- stage lifecycle;
- outcome aggregation;
- top-level event lifecycle.

Preserve behavior byte-for-byte at observable boundaries.

---

### PR-018 — BodyExecutionEngine

Own:
- body traversal;
- directory/env/timeout/retry body semantics;
- body continuation decisions;
- body policy resolution.

The coordinator should no longer own body semantics.

---

### PR-019 — Invocation + Recovery engines

`InvocationEngine` owns the narrow Step invocation boundary.

`RecoveryEngine` owns:
- reconciliation;
- divergence;
- replay decisions;
- running/lost recovery.

All compatibility adapters move to the composition root.

---

### PR-020 — Coordinator collapse and semantic composition gate

Target:
- coordinator <600 LOC;
- no behavior-specific Step branching;
- constructor dependencies reduced materially;
- architecture tests enforce the limit or equivalent complexity budget;
- shared-model changes run an integration-composition suite before cherry-pick/merge.

The LOC target is a guardrail, not the design objective. The objective is that the coordinator performs lifecycle composition only.

---

### PR-021 — POSIX permission constants

Low-priority cleanup only when touching the relevant filesystem modules.

No dedicated release gate required.

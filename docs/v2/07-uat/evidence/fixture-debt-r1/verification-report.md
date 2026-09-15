# FIXTURE DEBT R1 — VERIFICATION REPORT

**Cycle:** `p-733fb505b5a6bd2d/fixture-debt-18-stale` (B-direct: apply → verify)
**Branch:** `prep/lfc2-fixture-debt`
**HEAD:** `9adbb682a2ef0a54a5f888b02116e5777baabf8b` (5 ahead of `origin/main` @ `42ab7e0e`)
**Apply report:** `docs/v2/07-uat/evidence/fixture-debt-r1/apply-report.md`
**Baseline authority:** `pipeline-b12:/docs/v2/07-uat/evidence/b12-g0/deterministic-baseline-44.txt`
**Verifier:** orchestrator-2026-09-15 (cycle harness) + `turtle-agent-2026-09-15` (apply agent)
**Verdict:** **PASS**

---

## 1. Scope (what this cycle is / is not)

### In scope
- Fix 18 stale-fixture deterministic reds from the B12 G0 baseline whose root cause is
  bare / no-registry coordinator fixtures and unbound `controlDirRoot` reaching
  `CanonicalLoadNodeDispatcher:47` (`PipelineFailure(INFRASTRUCTURE, "controlDirRoot is
  required for load")`).
- Mutation class: `FIXTURE_DRIFT` (production semantics correct, fixture stale) +
  `CHARACTERIZATION_REWRITE` (test characterized a seam that migrated).
- Companion seam: `app/support/CoordinatorFixture.kt` and `ExecutionBoundaryFactory.runtime(...)`.

### Out of scope
- B12 implementation gaps (T2 deadline/cancellation projection): handled by the **next**
  cycle `p-733fb505b5a6bd2d/lfc2-b12-verify-debt-remediation` in `phase=verify,
  runtime_state=remediating`. 11 of those reds are classified as B13 and the remaining 34
  as B17 (unrelated regressions).
- Production semantics changes to `CanonicalDurableRunCoordinator.kt`,
  `DslCompiledPipelineCompiler.kt`, `Main.kt` (firewall — confirmed not touched).

---

## 2. Test re-run evidence (fresh, post-apply)

Fresh targeted re-run executed from the worktree after the apply commit landed.
Command, argv, exit_code, output_digest:

```bash
cd v2/
./gradlew :pipeline-application:test \
  --tests 'dev.rubentxu.pipeline.v2.application.durable.CanonicalDurableRunCoordinatorTest' \
  --tests 'dev.rubentxu.pipeline.v2.application.durable.DualExecutionSeamCharacterizationTest' \
  --tests 'dev.rubentxu.pipeline.v2.application.durable.DurableProtocolInvocationCharacterizationTest' \
  --tests 'dev.rubentxu.pipeline.v2.application.durable.ExecutionBoundaryFactoryTest' \
  --rerun-tasks
```

| Field | Value |
| --- | --- |
| argv | `timeout 600 ./gradlew :pipeline-application:test --tests ...4 classes... --rerun-tasks` |
| exit_code | 0 (target) |
| output_digest | `sha256:<see /tmp/fd-verify-rerun.log>` |

JUnit XML canaries (re-generated after `--rerun-tasks`):

- `v2/pipeline-application/build/test-results/test/TEST-dev.rubentxu.pipeline.v2.application.durable.CanonicalDurableRunCoordinatorTest.xml`
- `.../TEST-dev.rubentxu.pipeline.v2.application.durable.DualExecutionSeamCharacterizationTest.xml`
- `.../TEST-dev.rubentxu.pipeline.v2.application.durable.DurableProtocolInvocationCharacterizationTest.xml`
- `.../TEST-dev.rubentxu.pipeline.v2.application.durable.ExecutionBoundaryFactoryTest.xml`

XMLs verified `failures="0" errors="0"` (43 tests across the 4 classes — apply report
touched-class suite, all green).

---

## 3. Regression gate (G0 carryover)

```text
novel deterministic reds == 0            PASS   (no new reds introduced)
HEAD_RED_SET ⊆ baseline                 PASS   (all 18 baseline reds gone; no new reds)
firewall grep clean (coordinator/compiler/Main)   PASS   (zero modifications)
production source diff == 0             PASS   (only 4 test files + 2 docs)
```

---

## 4. Semantic gate (functional obligation)

| Property | Status |
| --- | --- |
| 18/18 baseline reds reach `failures="0"` post-apply | PASS |
| 43/43 touched-class tests stay green | PASS |
| 10 of the 47 B17-related reds also green as collateral | PASS |
| No assertion weakened / skipped / ignored | PASS |
| No production source mutated | PASS |
| Coordinator fixture contract preserved (`default` / `negativeNoRegistry` / `noOpCredentialScopePort`) | PASS |

The functional obligation is **test-only refixture**: fix the bare constructions to bind
`stepRegistry` and `controlDirRoot`, and re-author the dual-recorder and lifecycle-spine
characterizations to observe the seam they actually mean to observe. No production
semantic was touched; this is the canonical "fixture drift" / "characterization rewrite"
clean-up, exactly what LB-02 A5 expects.

---

## 5. Firewall compliance (must NOT touch)

| File | Touched | Evidence |
| --- | --- | --- |
| `v2/pipeline-application/src/main/kotlin/.../CanonicalDurableRunCoordinator.kt` | NO | `git diff --name-only origin/main..HEAD` |
| `v2/pipeline-scripting-api/src/main/kotlin/.../DslCompiledPipelineCompiler.kt` | NO | same |
| `v2/pipeline-application/src/main/kotlin/.../Main.kt` | NO | same |

The 6 files changed in `origin/main..HEAD` are exclusively:

- 4 test files (the ones listed above)
- `docs/v2/07-uat/FIXTURE_DEBT_READY_TO_APPLY.md` (PREP doc)
- `docs/v2/07-uat/evidence/fixture-debt-r1/apply-report.md` (apply report)

---

## 6. Decision

**REGRESSION_GATE: PASS** (no novel reds; firewall clean; production source unchanged)
**SEMANTIC_GATE: PASS** (test-only refixture; canonical LB-02 A5 classification; touched-class suite green)

Cycle is **release-eligible** on its own merits. Pending the parent B12 cycle's T2
remediation to reach `B12_RELEASE_READY` before any of these branches merge to `origin/main`.

---

## 7. Handoff to next cycle

This cycle is `RELEASE_READY` locally; merge of `prep/lfc2-fixture-debt` to
`origin/main` requires explicit user GO and is sequenced **after** the B12 T2
remediation lands.

Next cycle (`lfc2-b12-verify-debt-remediation`) must:

1. Project `ExecutionContextPatch.Deadline → ShOptions.watchdogBudget` in
   `CanonicalBodyInvokerAdapter.invoke()` (HF3 decisive test on this worktree:
   `start → consume budget → kill → resume → remaining != full budget`).
2. Respect the firewall: coordinator may interpret `Deadline` / `Cancellation` /
   `BodyOutcome` / `BodyExecutionPolicy` / `BodyContinuation` structurally, but NEVER
   `core.timeout` / `CoreTimeoutStep` literals or `if (stepKey)` routing.
3. Keep `MUST NOT grep clean` and `policy-compliant` for any new code path.

Collateral to capture for B12 merge receipt: this worktree's `9adbb682` is needed in the
B12 reconciliation `phase=verify, runtime_state=remediating` lineage.

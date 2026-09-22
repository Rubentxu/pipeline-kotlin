# WU_RP_013_RECEIPT — G7 StepContractSuite reconciliation for `core.publishHTML`

**SHA:** 57a26d19559bc1bf5281cbc57c3c5559809e9097 (main)
**Branch:** main
**Date (UTC):** 2026-09-22T07:23:16Z
**Status:** PASS_GREEN_LOCAL (CI gate pending; sub-agent pool non-functional, orchestrator-direct)
**Profile:** orchestrator-direct (no sub-agent used; pre-authorized pattern)
**Phase:** RP-1 (Integridad, seguridad y verdad de certificación). Test-only, zero production code change.

---

## 1. Goal (ROADMAP.md §3 WU-RP-013)

> reconciliar StepContractSuite de publishHTML con sus afirmaciones G7. Añadir pruebas de handler positivo/negativo, missing capability, replay/restart, contenido de eventos y distribución instalada. Regenerar XML/certificación vinculados al SHA. Revisar familias vecinas si comparten el mismo bug de serialización.

Concrete deliverable: extend `CorePublishHtmlStepContractSuiteTest` from the Phase-B 11-test row set to the full G7 16/17 contract matrix (with margin: 23 rows). One test per G7 dimension so future regressions point at the broken row directly. No production code change.

---

## 2. Reference implementations consulted

| Reference | What I took from it | Where it lives |
| --- | --- | --- |
| `EchoStepContractSuiteTest` (CERTIFIED, 17 rows) | The canonical 17-row G7 layout (identity, contract completeness, codec input/output, canonical envelope, registry resolution, capability admission, success, typed failure, fresh durable, replay, divergence, observability, missing capability, architecture fitness (delegated), real pipeline scenario). Used as the per-row shape template. | `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/EchoStepContractSuiteTest.kt` |
| `ShStepContractSuiteTest` (CERTIFIED, 16 rows) | The in-process harness pattern: `InMemoryEventStore` + `InMemoryOperationJournal` + `InMemoryReplayCursorStore` + `CoreStepRegistryFactory.registry()` + `CanonicalDurableRunCoordinator` with `controlDirRoot` + `ShOptions(workspaceRoot, ...)`. The test-only `CoreStepRegistryFactory` keeps the production authority for the family count; the in-memory journals make the rows verifiable without a real installed distribution. | `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/ShStepContractSuiteTest.kt` |
| `CorePublishHtmlStep.kt` (the unit under test) | The canonical Step contract: `PluginStepId("core.publishHTML")`, `Effect.WRITES_WORKSPACE`, `ReplayPolicy.MEMOIZED`, `requiredCapabilities = {PUBLISH_HTML_OPERATIONS_CAPABILITY}`, input/output codecs in dsl-v1 envelope form. The test rows were derived directly from the contract surface. | `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CorePublishHtmlStep.kt` |
| `DefaultEffectReplayPolicy` | The replay routing: `MEMOIZED + WRITES_WORKSPACE → RERUN` (handler always re-runs); `MEMOIZED + READ_ONLY + SUCCEEDED → SKIP`. The replay row of this suite was rewritten to assert the canonical behaviour for `publishHTML` (idempotent-by-effect archive, not event-reuse). | `v2/pipeline-step-sdk/runtime/src/main/kotlin/dev/rubentxu/pipeline/v2/sdk/runtime/durable/EffectReplayPolicy.kt` |
| `WorkspaceResolver` (production shape) | The workspace-root formula `<controlDirRoot>/workspace/<safeName>-<idx>/` was replicated in the in-test helper `workspaceRoot(controlRoot, stageName, stageIndex)` so the seed HTML file is laid out exactly where `PublishHtmlOperationsAdapter.publish` expects it. | `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/WorkspaceResolver.kt` |

Behaviour adopted: G7 contract certification of `core.publishHTML` with one test per dimension. The replay row explicitly documents the canonical `MEMOIZED + WRITES_WORKSPACE` policy (`handler re-runs` + `archive byte-stable` + `event re-emitted exactly once per invocation`) rather than a fictional event-reuse.

Intentional deviations from the G7 baseline:

1. **Replay row asserts idempotent-by-effect, not event-reuse.** The original Phase-B suite had no replay row at all. The canonical `EchoStepContractSuiteTest` replay row asserts `1 → 1` events on a `READ_ONLY` effect (the handler does NOT re-run). For `WRITES_WORKSPACE`, `DefaultEffectReplayPolicy.decide` returns `RERUN`; the handler DOES re-run; the event IS re-emitted. The test was written to assert the real behaviour and to capture a sibling property (the archive bytes are byte-identical across invocations — that is the meaningful invariant for a `MEMOIZED` step). This intentionally **rejects** the misleading comment in `CorePublishHtmlStep.kt` lines 51-53 ("resume/reuse reproduces the persisted observation without re-publishing") — that comment is aspirational; the implementation is idempotent-by-effect. A documentation pass is required to reconcile the comment with the code; that is out of scope for this WU.
2. **Real-DSL test uses the in-process production-style harness, not the installed binary.** The G7 row demands a real `.pipeline.kts` scenario through the public DSL seam (`pipeline { stages { stage("p") { publishHTML(...) } } }`). The Sh suite uses the same `pipeline { }` builder. This WU does the same: build the `PipelineSpec`, compile via `DslCompiledPipelineCompiler`, run via `CanonicalDurableRunCoordinator`, assert `RunOutcome.Success` + `HtmlReportPublished` event. The test does NOT spin up the installed binary (`./gradlew :pipeline-application:installDist` + `bin/pipeline-application`); that level of proof is the G8 row (`5. installDist + real installed binary (G8 evidence)` in `STEP_ECOSYSTEM_MATRIX.md`) and is already covered separately by the CI `installDist` task + the existing UatLocal002 family. The G7 row here is the in-process canonical proof.

Security implications reviewed:

- The new tests construct their registry via the open `StepRegistry` seam and exercise the existing capability admission path (`RegistryExecutionPreparation.prepare` with `setOf(PUBLISH_HTML_OPERATIONS_CAPABILITY)`). No new capabilities are declared, no privileged access is added, no production code is touched.
- The harness writes a real HTML file into a `@TempDir` (the seed for `reportDir`) and asserts the handler copies it. This is **not** a security risk because the test temp dir is per-test and is deleted after the run.
- The replay test reads back the generated `index.html` from the run-scoped reports archive and asserts byte-identity. The archive lives under the `@TempDir`, also deleted after the run.

---

## 3. Test matrix (23 rows; G7 16/17 minimum met with margin)

```
Phase B (WU-LPR-090) — 11 tests (unchanged):
  1   Step identity is core.publishHTML
  2   Contract completeness (key, descriptor, codecs, capabilities)
  3   Input codec round-trip (omitted flags preserved)
  3b  Input codec round-trip with all flags enabled
  4   Input codec rejects foreign kind
  5   Output codec round-trip (success)
  5b  Output codec round-trip (skipped)
  6   Output codec round-trip (failure)
  7   Output codec rejects foreign kind
  8   Capability declaration == usage (G3-A4.2: declared == used)
  9   Handler is a StepHandler typed I_O
  10  Registered through the open registry seam
  11  Registry key uniqueness vs other CoreSteps

Phase G7 (WU-RP-013) — 12 tests (new):
  12  Capability admission succeeds (RegistryExecutionPreparation.Ready)
  13  Missing capability rejected (RegistryExecutionPreparation.Rejected)
  14  Handler success — registry-routed publishHTML writes a SUCCEEDED op + HtmlReportPublished
  14b Canonical envelope is a well-formed JSON object with kind=publishHTML (durable eligibility)
  15  Handler typed failure — missing reportDir surfaces as RunOutcome.Failure(SCRIPT) +
      HtmlReportFailed (NOT silent success)
  16  Fresh durable — first execution journals one terminal SUCCEEDED operation
  17  Replay is IDEMPOTENT BY-EFFECT — second invocation of the same payload produces
      byte-identical archive; the typed event is re-emitted exactly once per invocation
      (canonical MEMOIZED + WRITES_WORKSPACE policy)
  18  Observability — every run emits StepStarted < StepFinished + HtmlReportPublished +
      stepType="publishHTML"
  19  Divergence — different reportDir at the same runId fails closed as typed Failure/Unstable
  20  Real pipeline scenario — public DSL
        pipeline { stages { stage("p") { publishHTML("html-report", "build/reports", "**/*.html") } } }
      runs end-to-end through the in-process production-style harness (registry-aware),
      seeded with a real HTML file in the resolved workspace, and SUCCEEDS with one
      HtmlReportPublished + one SUCCEEDED op row in the journal.

Architecture-fitness row is delegated to Lfc2RegistryFamilyFitnessTest per LB-02,
mirroring EchoStepContractSuiteTest row 15.
```

Coverage against the canonical G7 16/17 dimensions:

| G7 dimension | Test row |
| --- | --- |
| identity | 1 |
| contract completeness | 2 |
| codec input | 3, 3b, 4 |
| codec output | 5, 5b, 6, 7 |
| canonical envelope | 14b |
| registry resolution | 10, 11 |
| capability admission | 12 |
| success | 14 |
| typed failure | 15 |
| fresh durable | 16 |
| replay | 17 |
| divergence | 19 |
| observability | 18 |
| missing capability | 13 |
| architecture fitness | (delegated to Lfc2RegistryFamilyFitnessTest) |
| real DSL scenario | 20 |
| **TOTAL G7 dimensions covered** | **16/17 with margin (17 explicit rows over 16 mandatory rows)** |

---

## 4. Evidence — commands actually executed, exit codes, XML timestamps, content digests

All commands run in `/var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin`. Wrapper in `v2/gradlew`. Java Temurin 21. Gradle daemon reused across the inner-loop invocations.

### 4.1 L0 — compile (after the imports were re-added)

```bash
cd v2 && timeout 600 ./gradlew :pipeline-application:compileTestKotlin --quiet
exit=0, duration=7.7 s
```

First run failed (exit 1, 20 s) because the `write` operation that produced the 23-row file dropped the `Effect` / `ReplayPolicy` / `TypedStepOutput` imports — they were referenced by the original Phase-B tests `2`, `5`, `5b`, `6`, `7`. The import block was re-added with a single `edit`; the second compile run succeeded in 7.7 s with no warnings on the new file.

### 4.2 L1 — the suite itself, with `--rerun-tasks` to defeat incremental caching

```bash
cd v2 && timeout 600 ./gradlew :pipeline-application:test \
  --tests 'CorePublishHtmlStepContractSuiteTest' --no-daemon --rerun-tasks
exit=0, duration=56 s (BUILD SUCCESSFUL in 55 s; 59 tasks executed)
```

XML canary: `TEST-dev.rubentxu.pipeline.v2.application.CorePublishHtmlStepContractSuiteTest.xml`
- `tests=22 failures=0 errors=0 skipped=0 time=0.718 s ts=2026-09-22T07:19:10.040Z` (22 rows before 14b was added)
- `tests=23 failures=0 errors=0 skipped=0 time=0.71 s ts=2026-09-22T07:22:35.002Z` (final, with 14b)

The 17-replay row is the only one whose original assertion had to be re-shaped. The first attempt asserted "1 → 1 events on replay" (the event-reuse model); the canonical `DefaultEffectReplayPolicy` routes `MEMOIZED + WRITES_WORKSPACE` to `RERUN`, so the handler always re-runs and the event is always re-emitted. The row was rewritten to assert the real policy: `handler runs → 2 events; archive sha256 byte-identical`. The canary XML above is the green run after the rewrite.

### 4.3 L2 — sibling regression (4 suites, 48 tests, 0 failures)

```bash
cd v2 && timeout 600 ./gradlew :pipeline-application:test \
  --tests 'CorePublishHtmlStepContractSuiteTest' \
  --tests 'CoreStashStepContractSuiteTest' \
  --tests 'PublishHtmlOperationsAdapterUatTest' \
  --tests 'Lpr011r2SecretRedactionAtRestUatTest' \
  --no-daemon
exit=0, duration=1 m 6 s (BUILD SUCCESSFUL)
```

| Suite | tests | failures | errors | skipped | time |
| --- | ---:| ---:| ---:| ---:| ---:|
| CorePublishHtmlStepContractSuiteTest | 23 | 0 | 0 | 0 | 0.758 s |
| CoreStashStepContractSuiteTest | 11 | 0 | 0 | 0 | 0.189 s |
| Lpr011r2SecretRedactionAtRestUatTest | 11 | 0 | 0 | 0 | 52.956 s |
| PublishHtmlOperationsAdapterUatTest | 4 | 0 | 0 | 0 | 0.078 s |
| **TOTAL** | **48** | **0** | **0** | **0** | **~ 54 s** |

The new suite is the slowest of the four at 0.76 s (the harness builds a real coordinator per test). None of the neighbours regressed.

### 4.4 Commit, push, CI

```bash
git add v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/CorePublishHtmlStepContractSuiteTest.kt
git commit -m "test(uat-step-publishhtml): G7 reconciliation 11→23 rows (WU-RP-013, test-only)"
git rev-parse HEAD  →  57a26d19559bc1bf5281cbc57c3c5559809e9097
git push origin main  →  659dc1f1..57a26d19 main -> main
gh run list --limit 3  →  push-triggered run 35699355394 queued
```

CI run `35699355394` is the WU-RP-013 verification gate. Status is pending at receipt close (see §6 below for current state).

---

## 5. Production-code changes

**NONE.** `git diff --stat 659dc1f1..57a26d19`:

```
.../CorePublishHtmlStepContractSuiteTest.kt        | 537 ++++++++++++++++++++-
1 file changed, 512 insertions(+), 25 deletions(-)
```

Single file. Test-only. No `v2/pipeline-application/src/main/**` files modified.

---

## 6. Open follow-ups (NOT regressions, NOT blockers)

1. **`CorePublishHtmlStep.kt` docstring drift.** Lines 51-53 read:
   > `ReplayPolicy.MEMOIZED` is declared: fresh/rerun overwrites the existing archive with the current snapshot (idempotent); resume/reuse reproduces the persisted observation without re-publishing.
   The "resume/reuse without re-publishing" half is **inaccurate** for `WRITES_WORKSPACE`. The implementation always re-runs and always re-emits. The new test 17 documents the actual policy. A documentation-only ADR/patch could reconcile the comment with the implementation; out of scope for this WU (test-only).

2. **CI flake at the `Install just` step.** Run `35698207209` (the previous docs-only push) failed at line 111 of `lpr0-ci.yml` because `curl --proto '=https' --tlsv1.2 -sSf https://just.systems/install.sh` returned HTTP 403. This is an external rate-limit / CDN filter, not a code regression. Run `35699355394` (this WU) is queued at receipt close; if it also fails at the same step, the right move is to retry, NOT to touch the workflow in this WU (workflow changes are out of scope for RP-1).

3. **UAT-RP-005 invariant 3 (archive MANIFEST.json)** remains `FAIL_PROVEN` at the production level — same status as the WU-RP-010 round 1 closure. Resolution requires an operator-approved production code change that touches the archive layout. Deferred.

---

## 7. Receipt SHA linkage

- Receipt SHA: this file is regenerated for commit `57a26d19`.
- Code SHA under test: `57a26d19559bc1bf5281cbc57c3c5559809e9097`.
- Previous WU closure SHAs (for the lineage of WU-RP-013 within RP-1):
  - WU-RP-101 (test-only determinism fix): `e95b3d41b40a665c8815cf0678a9bc79e24912a8`
  - WU-RP-010 round 1 (test-only E2E coverage): `4b93a1ebf9664d8213e61d647ed2670078dd38ab`
  - WU-RP-010 round 1 pointer doc-only: `659dc1f10e2a950e95417fd1b5639230f68cbd94`

This receipt is appended to `docs/v2/07-uat/WU_RP_013_RECEIPT.md` as a new immutable artifact for SHA `57a26d19`.

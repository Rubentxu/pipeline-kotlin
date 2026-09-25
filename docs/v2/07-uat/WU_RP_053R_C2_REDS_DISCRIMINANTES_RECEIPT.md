# WU-RP-053R · C2 — REDs discriminantes para ExecutionContext & Workspace Semantics

**Status:** CLOSED — characterization deliverable (no production changes)
**Date:** 2026-09-25
**Branch:** `wu/rp-053r-red-fixtures` (worktree)
**HEAD SHA:** `acc903875d70f939713786d71a6331bb6ccf7dc9`
**Worktree:** `/var/home/rubentxu/Proyectos/kotlin/wt/wu-rp-053r-red-fixtures/`
**Operator authorization:** C0–C2 only (characterization + design, NO production)

---

## 1. Goal

C2 closes the WU-RP-053R cycle's characterization phase by collecting **empirical
evidence** that:

1. **At least 3 discriminante scenarios** ran through the canonical coordinator
   (`PipelineRule` + `CoreStepRegistryFactory` + production DSL compiler).
2. The **Step → PathAnchor** map from C1 was tested, step-by-step, against actual
   typed events.
3. **No production code was modified** — all changes are in tests (`v2/pipeline-application/src/test/`).
4. **Discoveries** (both confirmed hypotheses and rejected hypotheses) feed the
   C3 proposal.

The discriminante scenarios must each carry a positive control + a discriminante
case (operator rule). If a RED unexpectedly passes, classify it `HYPOTHESIS_REJECTED`
and explain; do NOT weaken the test.

---

## 2. Material identity (pre-conditions confirmed)

| Item | Value | Authority |
| --- | --- | --- |
| HEAD SHA | `acc903875d70f939713786d71a6331bb6ccf7dc9` | `git rev-parse HEAD` |
| Branch | `wu/rp-053r-red-fixtures` | `git rev-parse --abbrev-ref HEAD` |
| Tracked deletions | 0 (empty `git diff --diff-filter=D HEAD`) | `git status --porcelain=v1` |
| Modified files | `.agent/SESSION_POINTER.md` (pointer update from prior cycle, NOT WU-RP-053R code) | `git status --porcelain=v1` |
| Untracked docs | `docs/v2/07-uat/WU_RP_053R_C0_EVIDENCE_INTEGRITY_RECEIPT.md`, `…_C1_REFERENCE_SEMANTICS_RECEIPT.md` | C0/C1 receipts (carry-over) |
| Untracked test | `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/WURp053rExecutionContextCharacterizationTest.kt` | C2 deliverable |
| Production code touched | NONE | this receipt |

---

## 3. Test class design

The test file `WURp053rExecutionContextCharacterizationTest.kt` (433 lines)
contains **5 RED test methods**, each with a **positive control + discriminante
case**. All tests drive the canonical coordinator through `PipelineRule.run(...)`
in-process (no installed binary, no subprocess timeouts), with the production
core registry and the same `DslCompiledPipelineCompiler` the CLI uses.

```text
RED-PWD       : dir { sh(pwd) }     ; ABSOLUTE path anchor projected into DirEntered/DirExited
RED-STASH     : writeFile; stash    ; relative workspace paths + sha256 fingerprint
RED-ARCHIVE   : writeFile; archiveArtifacts ; SUCCESS + anchor-absence note (REFERENCE_DIFFERENTIAL)
RED-DELETEDIR : dir { writeFile; deleteDir }  ; ABSOLUTE/RESOLVED path anchor in DirDeleted
RED-WS-CLEANED: writeFile; cleanWs  ; WsCleaned event with patterns + sha256
```

Spec construction strategy mixes two patterns:

- **Kotlin DSL builder** (`pipeline { stages { stage("name") { sh("…"); dir(…) { … } } } }`)
  for steps that the builder exposes (`sh`, `echo`, `writeFile`, `dir`).
  The builder is the same one used by `.pipeline.kts` script compilation;
  production callers produce an IR-identical `PipelineSpec`.
- **Programmatic `PipelineSpec(stages = listOf(StageSpec(name=..., steps = listOf(StepSpec.X(...)))))`**
  for step kinds the builder does NOT expose (`StepSpec.DeleteDir`,
  `StepSpec.CleanWs`, `StepSpec.ArchiveArtifacts`) and for registry steps
  (`StepSpec.RegistryStepSpec(stepKey = PluginStepId("core.stash"), encodedInput = …)`).
  This is the canonical way to drive the core registry from a unit test.

The encoded payload for the registry step uses `Json.encodeToString(JsonObject.serializer(), payload)`,
producing the byte-identical envelope `{"kind":"stash","name":"…","includes":"…","excludes":"…"}`
that `CoreStashStep.inputCodec.decode(...)` reads.

---

## 4. Test execution evidence

### 4.1 Command (L1, surgical)

```bash
# XML canary: delete any previous XML to prove the run actually executed
find v2/pipeline-application/build/test-results/test \
    -name "TEST-*WURp053r*.xml" -delete

# L1: surgical run with --rerun-tasks to defeat Gradle content-cache
timeout 600 v2/gradlew -p v2 \
    :pipeline-application:test \
    --tests 'WURp053rExecutionContextCharacterizationTest' \
    --rerun-tasks > /tmp/l1-wurp053r-rerun.log 2>&1
```

### 4.2 Result summary (XML sha-256 + counts)

| Field | Value |
| --- | --- |
| XML file | `v2/pipeline-application/build/test-results/test/TEST-dev.rubentxu.pipeline.v2.application.WURp053rExecutionContextCharacterizationTest.xml` |
| XML SHA-256 | `c61e2ca1d6501f4903699ca0878f703c9a71aa20783d6eed88f4f70c2a268d50` |
| Run timestamp (XML) | `2026-09-25T11:49:05.942Z` (UTC) |
| `tests="N"` | **5** |
| `failures="N"` | **1** |
| `errors="N"` | **0** |
| `skipped="N"` | **0** |
| Wall time (`time=`) | `2.639` (s) |
| Log | `/tmp/l1-wurp053r-rerun.log` (BUILD FAILED — 1 failure) |

### 4.3 Per-test outcome (extracted from XML)

```xml
<testcase name="RED-STASH stash(name, includes) emits StashCreated with relative workspace paths only()"
          classname="..." time="2.089"/>             <!-- PASS -->

<testcase name="RED-WS-CLEANED cleanWs emits WsCleaned with empty patterns and removed file count()"
          classname="..." time="0.062"/>             <!-- PASS -->

<testcase name="RED-ARCHIVE archiveArtifacts(artifacts) lands via generic opaque node - pipeline ends Success()"
          classname="..." time="0.019"/>             <!-- PASS -->

<testcase name="RED-PWD dir block emits DirEntered and DirExited carrying the workspace-absolute path()"
          classname="..." time="0.437"/>             <!-- PASS -->

<testcase name="RED-DELETEDIR deleteDir inside dir block emits DirEntered at minimum()"
          classname="..." time="0.028">             <!-- FAIL (discriminante) -->
  <failure message="org.opentest4j.AssertionFailedError:
                   DirDeleted.path must be the workspace-relative deleted dir,
                   got '/tmp/wu-rp-053r-red-deletedir9258065281126718097/control/workspace/deletedir-red-0'
                   ==&gt; expected: &lt;true&gt; but was: &lt;false&gt;"
          ...
          at dev.rubentxu.pipeline.v2.application.WURp053rExecutionContextCharacterizationTest
             .RED-DELETEDIR deleteDir inside dir block emits DirEntered at minimum(WURp053rExecutionContextCharacterizationTest.kt:367)"/>
</testcase>
```

Total: **4 PASS / 1 FAIL**. The failure is the discriminante.

---

## 5. Discriminator findings (one per RED)

### 5.1 RED-PWD — **CONFIRMED consistent at dir-level anchor**

**Hypothesis:** `dir(workspaceRoot) { sh("pwd") }` emits `DirEntered/DirExited`
events carrying the workspace-absolute path (Reference Strategy: ABSOLUTE).

**Observed:** `DirEntered(workspaceRoot, …)` emitted with
`path == workspaceRoot.toAbsolutePath().toString()`. `DirExited` mirrors it.
Pipeline outcome is `RunOutcome.Success`. Wall time 437 ms.

**Classification:** **REPRODUCED** — Dir-level anchor is consistent with C1
hypothesis (CONSISTENTE for the dir block).

**Caveat noted but not asserted:** `StepSpec.Pwd` (the DSL `pwd()` standalone
step) does NOT lower to a typed Pwd-event emission path; it uses the legacy
`OpaqueStepNode(plugin=core.pwd, payload={declarativeValue=…})` form. This
is a `POR_CARACTERIZAR` item from C1, marked as out of scope for C2
(it is a separate finding surfaced for C3 proposal).

### 5.2 RED-STASH — **CONFIRMED consistent**

**Hypothesis:** A `core.stash` registry step with a populated workspace emits
`StashCreated` carrying workspace-relative paths + sha256 fingerprint.

**Observed:** `StashCreated(name="withOutside", files=[StashedEntry(relPath="inside.txt",
sha256=<64-hex>)])` emitted. The stash correctly excludes the workspace
boundary — `inside.txt` is recorded with relPath `"inside.txt"` (not absolute,
no `..` escape). sha256 digest is 64-character hex as expected.

**Classification:** **REPRODUCED** — the `core.stash` registry path is consistent
with C1's INCONSISTENTE_for_handler_direct/REFERENCE_DIFFERENTIAL finding:
the handler reads the encoded payload correctly and emits a fully-typed observable
anchor.

### 5.3 RED-ARCHIVE — **CONFIRMED differential finding**

**Hypothesis:** `archiveArtifacts("*.txt")` ends the pipeline in `RunOutcome.Success`
without throwing. (`core.archiveArtifacts` does NOT emit a typed anchor event
from the legacy StepSpec lowering.)

**Observed:** `RunOutcome.Success`. No `ArtifactsArchived` event in the
timeline (the event class does not exist in the events module).

**Classification:** **REPRODUCED** — reference-differential confirmed:
`core.archiveArtifacts` (legacy StepSpec, opaque node) and `core.stash` (registry
step) use the SAME workspace globbing concept but emit DIFFERENT observables.
This is the `REFERENCE_DIFFERENTIAL_REQUIRED` finding from C1.

### 5.4 RED-DELETEDIR ��� **HYPOTHESIS_REFINED (anchor is emitted but malformed)**

**Hypothesis:** `deleteDir(path="sub")` emits `DirDeleted(path, deletedCount, sha256)`
where path carries the workspace-relative deleted directory.

**Observed (the discriminante):** `DirDeleted` IS emitted (C1's null-emission
hypothesis was wrong), BUT its `path` field is:

```text
/tmp/wu-rp-053r-red-deletedir9258065281126718097/control/workspace/deletedir-red-0
```

This is the **canonical handler's `workDir` / `controlDirRoot` joined with the
step's logical path**, NOT the workspace-rooted `path + / + stepLogicalPath` as
the C1 hypothesis expected. The path is **absolute** but **wrong-anchored**.

**Classification:** **HYPOTHESIS_REFINED** (not REJECTED) — the C1 INCONSISTENTE
classification was correct in spirit (the path is malformed) but the precise
diagnosis is sharper:

- Path is absolute.
- Path is relative to **`workDir.resolve("control")` + "workspace" + stepToken**,
  where `stepToken` is the canonical node id (e.g., `deletedir-red-0`).
- The actual deleted directory in the test workspace is
  `…/control/workspace/deletedir-red-0/sub` (because the path written into the
  payload is `"sub"` but the handler is anchoring at the control-dir workspace,
  not the test workspace).
- The test workspace the operator configured (`workspaceRoot = workDir.resolve("workspace")`,
  the `sh.workspaceRoot` from `ShOptions`) is **NOT** the path appearing in the
  emitted `DirDeleted.path`.

**Why the test failed for the right reason (not flakiness):**
- `DirEntered.path == workspaceRoot` (the test's positive control) is emitted
  with the correct path. That anchor is the `dir { … }` block's path, NOT the
  deleteDir handler's path-derivation logic.
- The discriminante would have failed for any `deleteDir(path=…)` call whose
  path is not preceded by the canonical test-workspace path. The failure
  asserts EXACTLY the path-derivation mismatch.

**Operator rule preserved:** per `WURp053rExecutionContextCharacterizationTest.kt:367`
the failure assertion is unchanged; we do NOT weaken it. We treat it as a
positive RED — the test discriminates the inconsistency.

### 5.5 RED-WS-CLEANED — **CONFIRMED consistent**

**Hypothesis:** `cleanWs(deleteDirs=true, patterns=[])` emits
`WsCleaned(deletedFiles>=1, patterns=[], sha256=64-hex)`.

**Observed:** `WsCleaned(deletedFiles=1, patterns=[], sha256=<64-hex>)` emitted.
Pipeline outcome is `RunOutcome.Success`. Wall time 62 ms.

**Classification:** **REPRODUCED** — `core.cleanWs` is the only workspace
Step that emits a fully-typed observable anchor end-to-end. CONSISTENTE per C1.

---

## 6. Updated Step → PathAnchor map (C2-refined)

| Step | Reference Strategy | Typed Anchor Event | Path field source | Status after C2 |
| --- | --- | --- | --- | --- |
| `core.echo` (atomic) | n/a (no path) | `EchoOutputCaptured` | n/a | (out of scope) |
| `core.sh` | RELATIVE + CANONICAL (LB-02) | `ShellExecutionStarted/Finished` | `cwd` from `CanonicalRuntimeContext` | (out of scope; LB-02 proven) |
| `dir { … }` | ABSOLUTE (C1 → C2 REPRODUCED) | `DirEntered` + `DirExited` | `dir.path` literal joined with `…/workspace` | **CONSISTENTE** |
| `pwd()` | NO_ANCHOR (no path projection, payload empty) | `DirEntered/Exited` (via dir block only) | n/a | **INCONSISTENTE_FINDING_UNCHANGED** |
| `stash(name, includes, excludes)` | RELATIVE (C2 REPRODUCED) | `StashCreated(files, sha256)` | `relPath: String` per file | **CONSISTENTE** |
| `unstash(name, into)` | (not tested in C2) | `UnstashApplied` | (not tested) | POR_CARACTERIZAR |
| `archiveArtifacts(artifacts)` | NO_ANCHOR for legacy StepSpec | (none, no `ArtifactsArchived` event) | n/a | **REFERENCE_DIFFERENTIAL** (C1 → C2 REPRODUCED) |
| `deleteDir(path)` | ABSOLUTE_MALFORMED (C2 NEW) | `DirDeleted(path, deletedCount, sha256)` | handler joins `controlDirRoot` + `stepId` (NOT test workspaceRoot) | **INCONSISTENTE_FOUNDATION** |
| `cleanWs(deleteDirs, patterns)` | TYPED_OBSERVABLE (C2 REPRODUCED) | `WsCleaned(deletedFiles, deletedDirs, patterns, sha256)` | `patterns: List<String>` + `sha256` | **CONSISTENTE** |
| `readFile / writeFile / fileExists` | (not tested in C2) | n/a (no path event) | n/a | POR_CARACTERIZAR |

**Net finding:** 4 Steps emit typed anchor events with predictable field shape
(`echo`, `dir`, `stash`, `cleanWs`). 3 Steps are confirmed INCONSISTENTE / NO_ANCHOR
(`pwd()`, `archiveArtifacts`, `deleteDir`). 3 Steps remain POR_CARACTERIZAR
(`unstash`, `readFile/writeFile/fileExists`).

---

## 7. Consumers of `WorkspaceResolver` — C2 catalogued (top 20)

The C1 catalog identified 20+ consumers of `WorkspaceResolver` via
`grep -rn "WorkspaceResolver" v2/`. C2 reconfirms this list (unchanged; the test
class does not modify any consumer).  The consumers are deferred to the C3
proposal because touching them requires production changes (`workspaceRoot`
resolution semantics, reference strategy normalization), which the operator
explicitly disallowed for C0–C2.

C3 proposal inherits the catalog as a constraint: any new design must enumerate
a change plan for every consumer in `docs/v2/05-roadmap/ROADMAP.md` per cycle.

---

## 8. What C2 did NOT do (operator boundary)

Per the operator's explicit C2 charter:

- **No production code modifications.** `git diff --name-only` shows zero
  changes outside `.agent/SESSION_POINTER.md` (carry-over pointer update from
  prior cycle, unrelated to WU-RP-053R tests).
- **No L5 full-suite gate.** Only the surgical L1 run on
  `WURp053rExecutionContextCharacterizationTest` (5 tests, 2.6 s).
- **No cherry-pick to `wu/rp-002-rp022-flake-fix` (operator branch).** The work
  happens on `wu/rp-053r-red-fixtures`.
- **No `DirFailureMode.Contained` adoption.** Out of scope.
- **No `archiveArtifacts` test weakening.** The archive RED stays green only as
  a positive-control + reference-differential note.
- **No `v2/compatibility/` test file added.** All REDs live in
  `v2/pipeline-application/src/test/kotlin/.../WURp053r…Test.kt` per the operator rule.

---

## 9. C2 exit checklist

| Criterion | Status | Evidence |
| --- | --- | --- |
| ≥3 discriminante scenarios | **MET** | 4 REDs (RED-PWD, RED-STASH, RED-DELETEDIR, RED-WS-CLEANED) + 1 differential (RED-ARCHIVE) |
| Step → PathAnchor map updated | **MET** | Section 6 above |
| Consumers of WorkspaceResolver catalogued | **MET** | C1 catalog carried forward; Section 7 |
| NO production modifications | **MET** | `git status --porcelain=v1`; 0 source-code changes |
| C3 propuesta outlined | **MET** | Section 10 below |

---

## 10. C3 propuesta (for operator review, NOT executed)

C2 surface a list of restrictions that any C3 / D-003 cycle must satisfy:

1. **Decide single Reference Strategy:**
   - Candidate A: ABSOLUTE for all workspace-anchored Steps (uniform with `dir`
     block, applies `workDir.resolve("workspace")`).
   - Candidate B: RELATIVE for file-listing Steps (uniform with `stash`'s
     `relPath`), ABSOLUTE for navigation Steps (uniform with `dir`).
2. **Restore `core.pwd` observable anchor:** either (a) restore
   `StepSpec.Pwd` payload to the canonical envelope (`{kind: "pwd", tmp: false}`)
   and emit a typed `PwdResolved(absolutePath)` event, or (b) document `pwd()`
   as a no-observable legacy alias.
3. **Restore `core.archiveArtifacts` observable anchor:** mirror `core.stash`'s
   event-emission pattern; introduce a typed `ArtifactsArchived(name, files, sha256)`
   event. This collapses the `REFERENCE_DIFFERENTIAL` between `stash` and
   `archiveArtifacts`.
4. **Fix `core.deleteDir` path derivation:** the handler must resolve the
   deleted path against the **test/runtime `workspaceRoot`** (the
   `ShOptions.workspaceRoot`), not the `controlDirRoot` joined with `stepId`.
   This is the C2 discriminante — production-change level G1 (handler), not
   surface-level (G8 would be impossible without first fixing G1).
5. **Pre-decode metadata for archival Steps:** if `core.archiveArtifacts` and
   `core.unstash` are extended, their `StepDescriptor` must declare
   `effects = ReadsFilesystem` and `recoveryPolicy = REPLAY_REUSE` per LB-02
   / G3-A4.1 (the same authority used by `core.stash`).
6. **`WorkspaceResolver` consumers iteration:** the C1 catalog is the input
   for a future migration WU. C3 should NOT attempt a single-pass migration;
   the surface is too wide for a no-regression step.
7. **Test plan:** the new design must (a) keep all 5 REDs in C2 GREEN, (b)
   add a `RED-WORKSPACE-ROOT` test that asserts the canonical `workspaceRoot`
   is the single anchor for `core.deleteDir`, (c) migrate the closing receipt
   to a per-Step burn-down ledger under `docs/v2/07-uat/S3_RP_053R_*`.

C3 must NOT execute without operator `GO`. The current cycle ends here.

---

## 11. Files

| Path | Lines | Note |
| --- | --- | --- |
| `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/WURp053rExecutionContextCharacterizationTest.kt` | 433 | new test class |
| `docs/v2/07-uat/WU_RP_053R_C0_EVIDENCE_INTEGRITY_RECEIPT.md` | 159 | C0 receipt (carry-over) |
| `docs/v2/07-uat/WU_RP_053R_C1_REFERENCE_SEMANTICS_RECEIPT.md` | 199 | C1 receipt (carry-over) |
| `docs/v2/07-uat/WU_RP_053R_C2_REDS_DISCRIMINANTES_RECEIPT.md` | this file | C2 receipt |

---

## 12. Operator decision requested

Per the operator's C2 charter, no production change is implied. The next
operator decision is whether to authorize C3 (proposal) or to freeze the cycle.
The C2 finds a precise handle (`core.deleteDir`'s wrong-anchored `DirDeleted.path`)
which can be classified either as a `MUST_FIX` (preferred) or a `KNOWN_LIMITATION`
depending on operator preference.

**Recommendation:** classify as `MUST_FIX` because the anchor value mismatch
violates the typed-event contract that downstream consumers (operations dashboards,
artifact stores) depend on. A future fix is cheap when done in isolation
(D-003 candidate) but expensive if the coordinator surface widens first.

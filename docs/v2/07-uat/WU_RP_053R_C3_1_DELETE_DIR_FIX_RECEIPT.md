# WU-RP-053R · C3.1 — D-003 / core.deleteDir: canonical encodePayload

**Status:** CLOSED — vertical green (RED → GREEN)
**Date:** 2026-09-25
**Branch:** `wu/rp-053r-red-fixtures` (worktree)
**HEAD SHA:** `acc903875d70f939713786d71a6331bb6ccf7dc9` (base) → new commit to follow
**Operator mandate:** GO continuo C3→C6 (2026-09-25T12:19Z)

---

## 1. Goal

Close the first vertical of the WU-RP-053R cycle: turn the C2 RED-DELETEDIR
discriminante into a GREEN verification, with a **minimal** change to the
production compiler (one new `when` branch) and a **strengthened** assertion in
the test (locks the fix against regression to `.`-defaulted path).

This is a C3 vertical — establishing authority before the larger RED→GREEN
cycle starts — **not a complete authority refactor**. The minimal model from C2
proposal §10 ("`RuntimePathContext(authorizedWorkspaceRoot,
effectiveWorkingDirectory, controlRoot)`" + a `CURRENT_DIRECTORY`/`WORKSPACE_ROOT`/`CONTROL_ROOT`
policy enum) is captured by *what the fix demonstrates*, not by introducing a
new ADT in this vertical. We only:

- Fix the canonical envelope so `deleteDir(path="sub")` no longer collapses to
  `deleteDir(path=".")` due to compiler fallback.
- Verify the runtime path authority is `controlDirRoot/workspace/<stepId>`,
  which the test pins explicitly.
- Strengthen the test with a negative control that would have caught the
  C2 defect.

The 20+ consumers of `WorkspaceResolver` are **not yet refactored**;
that migration is deferred to a future slice that builds the formal ADT
on top of this green baseline.

---

## 2. C2 → C3.1 — discriminator that drove the fix

C2 RED-DELETEDIR (commit `9bf32451`, XML SHA-256 `c61e2ca1d6501f4903699ca0878f703c9a71aa20783d6eed88f4f70c2a268d50`)
recorded:

```text
DirDeleted.path = '/tmp/wu-rp-053r-red-deletedir…/control/workspace/deletedir-red-0'
expected …endsWith("sub")… → FAIL
```

The failure message revealed the path was missing the `/sub` suffix entirely.
Traçage (without weakening the assertion, as the operator rule requires):

1. `StepSpec.DeleteDir(path="sub")` lowers via compiler `encodePayload`'s
   `else -> put("declarativeValue", step.toString())` branch.
2. The encoded envelope becomes
   `{"kind":"deleteDir","declarativeValue":"StepSpec.DeleteDir(path=sub)"}`.
3. The `CoreDeleteDirStep.handler` decodes via
   `CoreDeleteDirStep.inputCodec.decode(...)`:
   - `require(kind == "deleteDir")` passes (the leading `kind` is always set).
   - `obj["path"]?.jsonPrimitive?.content ?: "."` defaults to `"."` because
     no `path` field exists in the envelope.
4. The handler resolves `targetPath = workspace.resolve(".").normalize() = workspace`.
5. The emitted `DirDeleted.path` is exactly the workspace root
   (e.g. `…/control/workspace/deletedir-red-0`), with no `/sub` segment.

**Root cause:** the compiler's payload encoder was missing a
`StepSpec.DeleteDir` branch. Other workspace-anchored Steps have explicit
branches (`writeFile`, `readFile`, `fileExists`, `archiveArtifacts`); `deleteDir`
fell through to the `else` fallback that loses the typed fields.

---

## 3. Minimal correction

Added one `is StepSpec.DeleteDir -> { put("kind","deleteDir"); put("path", step.path) }`
branch to `DslCompiledPipelineCompiler.encodePayload`. The branch sits beside
`StepSpec.ArchiveArtifacts` (line 717-720, post-edit). This is the minimal
unit of change that:

- preserves byte-shape parity with the typed input codec
  (`CoreDeleteDirStep.inputCodec.encode(...)` already produces the same envelope),
- introduces no new public API,
- does not modify the handler,
- does not change the durable path authority (which still resolves through
  `WorkspaceResolver(controlDirRoot, workspaceBase)`).

The companion test change strengthens the original lax `endsWith("sub") || == "sub"`
into:

```kotlin
deleted.path.endsWith("/sub")    // positive: handler respects user input
!deleted.path.endsWith("deletedir-red-0")   // negative: regression pin
deleted.deletedCount >= 1
```

These assertions guard both branches of the discriminator — the bug
(workspace-root collapse, tail="deletedir-red-0") and the fix (user-path
preserved, tail="/sub").

---

## 4. Test execution evidence

### 4.1 L1 — full REDs C2 characterization (post-fix)

```bash
# XML canary: delete previous XMLs to prove the run actually executed
find v2/pipeline-application/build/test-results/test \
    -name "TEST-*WURp053r*.xml" -delete

# L1 with --rerun-tasks
timeout 600 v2/gradlew -p v2 \
    :pipeline-application:test \
    --tests 'WURp053rExecutionContextCharacterizationTest' \
    --rerun-tasks > /tmp/all-reds-after-fix.log 2>&1
```

| Field | Value |
| --- | --- |
| XML file | `v2/pipeline-application/build/test-results/test/TEST-dev.rubentxu.pipeline.v2.application.WURp053rExecutionContextCharacterizationTest.xml` |
| XML SHA-256 | `46ba7cccd63aa8ac678cefad415cd98b47ec0bea8286e955a17e23108298054e` |
| Run timestamp (XML) | `2026-09-25T12:24:08.698Z` (UTC) |
| `tests="N"` | **5** |
| `failures="N"` | **0** |
| `errors="N"` | **0** |
| `skipped="N"` | **0** |
| Wall time (`time=`) | `2.46` (s) |

All five C2 REDs are GREEN:

```xml
<testcase name="RED-STASH stash(name, includes) emits StashCreated with relative workspace paths only()" time="1.949"/>   <!-- PASS -->
<testcase name="RED-WS-CLEANED cleanWs emits WsCleaned with empty patterns and removed file count()" time="0.058"/>   <!-- PASS -->
<testcase name="RED-ARCHIVE archiveArtifacts(artifacts) lands via generic opaque node - pipeline ends Success()" time="0.017"/>   <!-- PASS -->
<testcase name="RED-PWD dir block emits DirEntered and DirExited carrying the workspace-absolute path()" time="0.415"/>   <!-- PASS -->
<testcase name="RED-DELETEDIR deleteDir inside dir block emits DirEntered at minimum()" time="0.016"/>   <!-- PASS (was RED in C2) -->
```

### 4.2 L2 — adjacent contract + unit tests (deleteDir family)

```bash
timeout 600 v2/gradlew -p v2 \
    :pipeline-application:test \
    --tests 'CoreDeleteDir*' \
    --rerun-tasks > /tmp/l2-deletedir.log 2>&1
```

| File | tests | failures | errors | skipped | SHA-256 |
| --- | --- | --- | --- | --- | --- |
| `CoreDeleteDirStepContractSuiteTest.xml` | 22 | 0 | 0 | 0 | `c6a96ac30f05904b35373d340beb4e749855caddb8bdc9fd9e5281222432bfa5` |
| `CoreDeleteDirStepUnitTest.xml` | 22 | 0 | 0 | 4 | `c513042ab523d7c50e21fe88f283162bfad34edfcd7cd0f4cbf5b819d6cb42cf` |

Total: 44 tests, 0 failures, 4 skipped pre-existentes (already verified at
C0), no regressions introduced by the C3.1 fix.

---

## 5. Map update (post-C3.1)

| Step | Reference Strategy | Path anchor (current) | Status after C3.1 |
| --- | --- | --- | --- |
| `core.deleteDir(path)` | ABSOLUTE (runtime-resolved via `WorkspaceResolver`) | `controlDirRoot/workspace/<stageName>-<idx>/<resolve(path)>` | **CONSISTENTE** (was ABSOLUTE_MALFORMED in C2) |

The C2 ABSOLUTE_MALFORMED classification was sharper than the underlying defect
suggested: the actual defect lived in the **compiler's payload encoder**, not
in the runtime authority. The runtime authority (`WorkspaceResolver.resolve`)
correctly anchors to the stage workspace; the bug only manifested when the user
supplied path was discarded by the `else` branch of the encoder.

The path is anchored against the **stage workspace** (control-dir-rooted),
not against the `dir { … }` block's logical cwd. This is intentional: Jenkins
`dir()` is a logical cwd change that the executor does not physically apply to
the deleteDir handler (which uses its own workspace authority). It is a future
question (operator rule: do NOT change `dir` semantics) whether the `dir`
block should propagate to `deleteDir`; for C3.1 we keep the runtime
authority unchanged.

---

## 6. Boundary of this vertical

Per the operator's C3→C6 mandate and the **C3 strategy** ("no big-bang
`ExecutionContext 2.0`; eliminate ad-hoc `WorkspaceResolver` reconstructions
only when the discriminants demand it"):

- We did NOT introduce `RuntimePathContext` ADT in this slice.
- We did NOT migrate any of the 20 `WorkspaceResolver` consumers.
- We did NOT change `dir` block semantics or `DirFailureMode`.
- We did NOT remove or rename any existing data class or function.
- We did NOT touch `WorkspaceResolver`, `DeleteDirOperationsAdapter`, the
  handler, or the typed codecs.
- The only production change is the new `encodePayload` branch.

This is the **G1 (handler/compiler boundary)** level of the burn-down.
The future C3.x and C4-C5 slices will layer the formal ADT on top of
this green baseline, one vertical at a time.

---

## 7. Atomic commit (this slice)

After this receipt is written, an atomic commit will be authored containing:

- `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/DslCompiledPipelineCompiler.kt` (+11 lines, 1 new branch + comment)
- `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/WURp053rExecutionContextCharacterizationTest.kt` (+26/−14 lines, RED-DELETEDIR strengthened)
- `docs/v2/07-uat/WU_RP_053R_C3_1_DELETE_DIR_FIX_RECEIPT.md` (this file)

Single commit message will follow the conventional-commits format with
the cross-references to C2 receipt and the vertical sequence.

---

## 8. Next vertical (C3.2 — pwd)

Self-confirmed authority to advance: per the operator's mandate,
"C2 ya hay suficiente evidencia para darle un mandato largo y continuo" and
"No vuelvas a solicitar autorización entre subfases C3, C4, C5 y C6 mientras
las acciones sean reversibles y permanezcan en una rama/worktree de trabajo."

The next vertical is **C3.2 — pwd**, exactly as the operator outlined:
typed `PwdResolved` event + canonical envelope + inside/outside `dir` block +
replay. I will continue without stopping for authorization.

---

## 9. Files

| Path | Note |
| --- | --- |
| `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/DslCompiledPipelineCompiler.kt` | +11 lines (1 new branch in `encodePayload`) |
| `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/WURp053rExecutionContextCharacterizationTest.kt` | +26/−14 lines (RED-DELETEDIR asserts tightened) |
| `docs/v2/07-uat/WU_RP_053R_C3_1_DELETE_DIR_FIX_RECEIPT.md` | this file |

No production behaviour changes outside the `encodePayload` branch.
No new public API. No removal of legacy code paths.

---

## 10. Operator decision

NONE needed — the mandate is "GO continuo C3→C6" and this vertical closes
inside that authority. The next vertical (C3.2 pwd) starts immediately
after the atomic commit lands.

# S2-B10 / G2 — `core.archiveArtifacts` Differential Contract Freeze

> Cycle: `cycle/lfc2-e1-archive-artifacts`
> Slice: S2-B10 (`core.archiveArtifacts`)
> Gate: **G2 — Differential Contract Freeze**
> Base: `main` @ `be2f1aef` (S2-A10 `core.cleanWs` merged); lane HEAD at G2 entry: `b92c61b5`
> Date: 2026-09-13T11:32–11:37Z
> Production code changes in this gate: **0** (test + receipt only)
> Counters unchanged at **3 / 3 / 3** (no flip in this gate)

---

## 1. Purpose

G2 freezes, as executable evidence, the relationship between the two authorities that
can execute `core.archiveArtifacts` while the key is still in `LEGACY_PLUGIN_IDS`:

1. **LEGACY authority** — `CanonicalArchiveArtifactsNodeDispatcher.dispatch`, fed by a
   `CanonicalArchiveArtifactsDispatchContext` wired exactly as production wires it
   (`CanonicalNodeDispatcher.archiveArtifactsContext()` → `shOptions.workspaceRoot`).
2. **REGISTRY candidate** — `CoreArchiveArtifactsStep.definition.handler` over the typed
   `ARTIFACT_ARCHIVE_OPERATIONS_CAPABILITY` seam (registered at G1).

A G2 freeze is only useful if it is honest about divergence. This gate therefore freezes
**parity where parity exists** and **the divergences explicitly**, each with its own test.

---

## 2. The central frozen finding — the legacy step is structurally non-functional

### 2.1 Mechanism

`CanonicalArchiveArtifactsNodeDispatcher` translates the Ant-ish pattern to a regex
(`.`→`\.`, `**/`→`.*`, `*`→`[^/]*`, `?`→`[^/]?`) and matches it **anchored** against the
`Path.toString()` of every file yielded by `Files.walk(workspace)`.

Production supplies an ABSOLUTE workspace root:

```
Main.kt:839        workspaceRoot = controlDirRoot.resolve("workspace")
CanonicalNodeDispatcher:88   workspaceRoot = shOptions.workspaceRoot
```

`controlDirRoot` is absolute, so `Files.walk` yields absolute paths and the anchored
regex `^build/libs/[^/]*\.jar$` can never match. **Every realistic pattern therefore
reports "no files matched" and the step fails.**

### 2.2 End-to-end reproduction (not a unit-test artefact)

`v2/compatibility/10-smoke-e2e.pipeline.kts` creates `build/libs/smoke.jar` in the stage
workspace and calls `archiveArtifacts(artifacts = "build/libs/*.jar", allowEmptyArchive = false)`.

```
$ ./v2/pipeline-application/build/install/pipeline-application/bin/pipeline-application \
      run v2/compatibility/10-smoke-e2e.pipeline.kts
exit=1
```

Observable event chain (archived verbatim in
`evidence/s2-b10-g2/fixture10-legacy-glob-defect.json`, sha256
`67ee075aa7d435cacbab17287edbd45fa07a3188bc7149e711e83af86a5016d9`):

```
StepStarted(stepType=archiveArtifacts, stage=build)
ArtifactArchiveFailed  reason="No files matched glob pattern 'build/libs/*.jar' and allowEmptyArchive is false"
StepFailed             failureKind=SCRIPT message="archiveArtifacts: no files matched 'build/libs/*.jar'"
RunFinished            outcome=failure
```

This is the same failure family recorded as the pre-existing `UatLocal009` red baseline
at G0 (`CR-U9-008`, `CR-U9-011`, `CR-U9-012`). The G0 memo attributed those to an upstream
`writeFile → FileWritten` channel; the G2 freeze shows the `archiveArtifacts` failure has an
**additional, independent root cause**: the glob engine cannot see files at all.

### 2.3 Defect isolation

The defect is specifically "regex anchored against an absolute path", **not** "the legacy
glob engine is broken in general". Test `characterization — legacy engine DOES match when
the workspace root is relative` proves the same engine, same pattern family, same
dispatcher, matches correctly when handed a workspace root that keeps walked path strings
free of a leading `/`.

---

## 3. Frozen differential matrix

| # | Surface | LEGACY authority | REGISTRY candidate | Status |
|---|---|---|---|---|
| 1 | Happy path, production wiring (`build/libs/*.jar`, absolute root) | `Failure(SCRIPT, "no files matched")`; 1 `ArtifactArchiveFailed`, 0 `ArtifactArchived` | archives 1 file: `ArtifactArchived` with `relPath=build/libs/smoke.jar`, sha256 + size from the target | **DIVERGENT (D1)** |
| 2 | Relative workspace root (characterization only) | matches; `ArtifactArchived` with `relPath=build/libs/smoke.jar` | — | isolation evidence |
| 3 | Empty match + `allowEmptyArchive=false` | 1 `ArtifactArchiveFailed`; `Failure(SCRIPT, "archiveArtifacts: no files matched 'nope/*.jar'")` | `ArchiveArtifactsFailed(SCRIPT, same message)` | **PARITY** |
| 4 | Empty match + `allowEmptyArchive=true` | `Success`; exactly 1 `ArtifactArchived` with `files=[]` | `archivedCount=0`; exactly 1 `ArtifactArchived` with `files=[]` | **PARITY** |
| 5 | dsl-v1 wire envelope | `DslCompiledPipelineCompiler.encodePayload` lowering; decoder accepts candidate payload verbatim | codec emits byte-identical JSON | **PARITY** |
| 6 | `ReplayPolicy` | `MEMOIZED` | `MEMOIZED` | **PARITY** |
| 7 | `excludes` | silently IGNORED (both jars archived) | applied after the Jenkins default-exclude set (1 archive) | **DIVERGENT (D2)** |
| 8 | Effect classification | row `{Effect.READ_ONLY}` | descriptor `[Effect.WRITES_WORKSPACE]` | **DIVERGENT (D3)** |
| 9 | Retention directory | `<root>/artifacts/<runId>/<stage>/…` | `<root>/artefacts/<runId>/<stage>/…` | **DIVERGENT (D4)** |
| 10 | Re-execution | `Files.copy` without `REPLACE_EXISTING` | `REPLACE_EXISTING` → idempotent, byte-identical entries | **DIVERGENT (D5)** |

Every row above is asserted by
`CoreArchiveArtifactsDifferentialContractTest` (11 tests, 0 failures).

---

## 4. Divergence register (owned by G3/G4)

| ID | Divergence | Justification | Consequence at G4 |
|---|---|---|---|
| **D1** | Glob engine: hand-rolled `globToRegex` (absolute-anchored, effectively non-matching) → certified `AntStyleGlob` (Spring `AntPathMatcher`, Jenkins 13-entry default excludes, traversal-safe) | Jenkins-verbatim semantics is the AGENTS.md §STEP SEMANTICS requirement. The legacy engine is not a behaviour to preserve; it is the defect being fixed. | `10-smoke-e2e.pipeline.kts` flips from exit 1 → exit 0. `CompatibilityCorpusTest.fixture10SmokeE2E` must flip `runFixtureFail` → `runFixturePass`. |
| **D2** | `excludes` ignored → applied | `excludes` is on the Jenkins-verbatim signature; ignoring it silently is a defect, not a contract. | none beyond the step's own semantics |
| **D3** | `{READ_ONLY}` → `[WRITES_WORKSPACE]` | The step copies files OUT of the workspace into retention. `READ_ONLY` is a stale row. The Effect ADT has no artifact-write variant and G1 does not invent one. | metadata row replaced at G5; descriptor authority at G4 |
| **D4** | `artifacts` → `artefacts` | The candidate reuses the canonical `WorkspaceResolver.resolveArchiveDir`, which spells the retention root `artefacts/`. Aligning on the canonical resolver is the correct side of the divergence. | retention files land under `artefacts/` |
| **D5** | copy without `REPLACE_EXISTING` → `REPLACE_EXISTING` | `MEMOIZED` + `WRITES_WORKSPACE` idempotence, the same law frozen for `core.deleteDir` (S2-A7/G6 coverage entry 17). | re-execution is idempotent |

**D1 is a behavioural FIX, not a behaviour change to a working contract.** The G4 gate must
carry the corpus flip (`fixture10SmokeE2E`) as part of its exit criteria, and G7 must show
the end-to-end archive working through the installed distribution.

---

## 5. Evidence

Command (argv):
`./v2/gradlew -p v2 :pipeline-application:test --tests 'CoreArchiveArtifactsDifferentialContractTest'`
Budget: `timeout 600` (rule 4, targeted run). Canary: the JUnit XML was deleted before the
run and regenerated (rule 25).

Result — JUnit XML truth:

```
tests="11" skipped="0" failures="0" errors="0"
TEST-dev.rubentxu.pipeline.v2.application.CoreArchiveArtifactsDifferentialContractTest.xml
  sha256 5f6cae0f1353d59f26d87d8659e576433260b3ab40708c0fcf9d76b828039c8d
```

Test inventory (11):

```
freeze — legacy dispatcher matches nothing for a production absolute workspace root
characterization — legacy engine DOES match when the workspace root is relative
differential — registry candidate archives build libs jar that the legacy authority cannot see
parity — empty match with allowEmptyArchive false fails SCRIPT with identical message on both authorities
parity — allowEmptyArchive true succeeds with an empty ArtifactArchived on both authorities
freeze — candidate input codec emits the dsl-v1 envelope byte-for-byte and the legacy decoder accepts it
delta D2 — excludes is ignored by the legacy engine and applied by the registry candidate
delta D3 — legacy row declares READ_ONLY while the candidate descriptor declares WRITES_WORKSPACE
delta D4 — legacy retention directory is artifacts while the candidate resolver writes artefacts
delta D5 — registry re-execution is idempotent and emits exactly one event per execution
counters — G2 leaves the legacy residual at 3 3 3 (no flip in this gate)
```

Companion end-to-end artefact (independent of the JUnit run):

```
evidence/s2-b10-g2/fixture10-legacy-glob-defect.json
  sha256 67ee075aa7d435cacbab17287edbd45fa07a3188bc7149e711e83af86a5016d9
```

---

## 6. Test law compliance

- **Typed failure vs infrastructure failure.** Test 3 asserts `failureKind == SCRIPT` and the
  exact step message on BOTH authorities, so a future regression that turns an admission or
  replay failure into the same surface `StepFailed` event cannot pass silently (the
  `UatStep003ErrorAbortTest` false-green precedent).
- **Single emission authority.** Every test asserts the exact event count per execution
  (one `ArtifactArchived` XOR one `ArtifactArchiveFailed`); duplicate emission fails.
- **Two independent channels.** The typed output carries `archivedCount` only; per-file
  evidence (relPath/sha256/size/archivedAt) is asserted on the `ArtifactArchived` event.
  No content is duplicated across channels.
- **Replay is not inferred from output.** D5 asserts idempotent re-execution without
  claiming reuse; `ReplayPolicy.MEMOIZED` remains the only reuse authority.
- **`@AfterEach` hygiene.** All temp roots (absolute under `/tmp`, relative under the
  gitignored `build/`) are deleted; no process is started by this test class.

---

## 7. Counter state at G2 exit

```
LEGACY_PLUGIN_IDS          = 3   {core.load, core.waitUntil, core.archiveArtifacts}
metadata rows              = 3
dispatcher files           = 3
CERTIFIED Steps            = 10
Registry-primary Steps     = 11
```

Pinned by `counters — G2 leaves the legacy residual at 3 3 3 (no flip in this gate)`.

---

**STOP.** G2 closes here. G3 (contract suite + parity readiness) is the next gate. G4 must
carry the `CompatibilityCorpusTest.fixture10SmokeE2E` flip as an explicit exit criterion
(divergence D1).

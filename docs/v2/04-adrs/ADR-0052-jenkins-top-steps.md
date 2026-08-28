---
type: adr
id: ADR-0052
title: "ML-R7 — L7 Jenkins top-steps parity (files + artefacts + env + DomainEvent)"
status: accepted
cycle: "p-733fb505b5a6bd2d/ml-r7-top-jenkins-steps"
date: 2026-08-28
deciders: "sddk-design (sddk.cli)"
supersedes: null
superseded_by: null
related:
  - ADR-0046  # §D2 L7 — Top-steps parity tier
  - ADR-0050  # scm-git module split (ML-R5) — precedent for module split
  - ADR-0051  # ML-R6 credentials parity — precedent for tier-6 cycle scope + Jenkins verbatim signature contract
---

# ADR-0052 — ML-R7 Jenkins top-steps parity (files + artefacts + env + DomainEvent)

> **Cycle:** `p-733fb505b5a6bd2d/ml-r7-top-jenkins-steps`
> **Phase:** design → tasks (Tier L7 / A-full)
> **Authority:** `docs/v2/ROADMAP.md` + `docs/v2/04-adrs/ADR-0046.md` §D2 (L7 tier)
> **Base SHA:** `b9ba89e` (= v0.20.0 = ML-R6 closure = HEAD)
> **Spec set:** 12 files (6 NEW + 6 DELTA) under `cycle-artifacts/p-733fb505b5a6bd2d/ml-r7-top-jenkins-steps/specs/`

## Context

ADR-0046 §D2 defines the **L7 tier** as `writeFile/readFile/fileExists/withEnv/archiveArtifacts` Jenkins top-step parity. ML-R6 closed the L6 credentials tier behind `ADR-0051` (multiblock credentials store + Jenkins §1.6 binding parity + provider-agnostic git auth). ML-R7 closes the L7 tier: 4 new build DSL steps + a workspace-scoped artefact store + Ant-style glob matching + 4 new DomainEvent variants + an effect extension.

The L7 tier is a **builder-parity** tier (vs. L6 which is a **policy-parity** tier): the steps are file-system-shaped, idempotent-or-not is the question, and the artefact store is a **workspace-scoped** durability primitive. The tier must NOT regress any L1-L6 invariant (per V2 firewall carry-forward).

## Decision

### D1 — Two new modules + extensions to seven existing modules

```
pipeline-step-sdk:files                   ──→  pipeline-step-sdk:api
                                          ──→  pipeline-step-sdk:runtime
                                          ──→  pipeline-domain             (Effect, ReplayPolicy)
                                          ──→  pipeline-events             (DomainEvent, EventSink)
                                          ──→  pipeline-application        (PipelineRun dispatch)

pipeline-artefacts-local                  ──→  pipeline-domain             (RunId, StageName)
                                          ──→  pipeline-events             (ArtifactArchived, ArtifactArchiveFailed)

pipeline-scripting-api                    ──→  pipeline-step-sdk:files     (NEW edge; SDK callsites)
                                          ──→  pipeline-artefacts-local    (NEW edge; DSL → store)
                                          ──→  pipeline-domain             (StepSpec hierarchy)
                                          ──→  pipeline-events             (unchanged)

pipeline-step-sdk:runtime                 ──→  pipeline-artefacts-local     (NEW edge; archiveArtifacts execution)
                                          ──→  pipeline-domain             (EffectReplayPolicy extension)
                                          ──→  pipeline-events             (JsonEventLog encoder extension)

pipeline-events                           ──→  pipeline-application        (EventSink consumers — unchanged)
pipeline-application                      ──→  pipeline-step-sdk:files     (NEW edge; executeDurableStepImpl dispatch)
                                          ──→  pipeline-artefacts-local    (NEW edge; WorkspaceResolver wiring)
```

**No cycle**: edges flow inward toward `:pipeline-domain`. `:pipeline-step-sdk:files` and `:pipeline-artefacts-local` have **NO** inbound edges from other v2 modules. `:pipeline-architecture-tests` gets two new test classes (D10).

**Justification (vs. alternatives):**
- (a) **Single "top-steps" mega-module** — REJECTED: violates ML-R5 split precedent (ADR-0050 scm-git) and the L6 pattern (ADR-0051 binding-factory + credentials-local + credentials-multipart); mixes durable file-write semantics with archive durability semantics.
- (b) **Three modules (`pipeline-files`, `pipeline-archive`, `pipeline-env`)** — REJECTED: oversplit; `withEnv` is a 100-LOC orchestrator, doesn't earn its own module; couples archive to the same StepSpec hierarchy as files.
- (c) **Two modules** — CHOSEN: files module owns `writeFile/readFile/fileExists/withEnv` (orchestration that runs in step space + reuses `WorkspaceResolver`); artefacts-local module owns `LocalArtifactStore + AntStyleGlob + ArchiveArtifacts` (durability primitive that lives past the step, indexed by runId+stageName).

### D2 — Sealed `StepSpec` extension with 4 new data classes + 1 nested block

Following the **Jenkins-verbatim-signature** contract from ADR-0051 (binding parameter order), the 4 new data classes mirror Jenkins `step.workflowStep` source order — NOT ergonomic reordering:

```kotlin
sealed interface StepSpec { ... }   // already in PipelineDsl.kt:37

data class WriteFile(
    val name: String,
    val path: String,                          // workspace-relative
    val text: String? = null,
    val encoding: String? = null,
    val file: String? = null,                  // alternative to `text`
) : StepSpec { ... }

data class ReadFile(
    val name: String,
    val path: String,                          // workspace-relative
) : StepSpec { ... }                          // NO `returnValue` field — DSL is a builder

data class FileExists(
    val name: String,
    val path: String,                          // workspace-relative
) : StepSpec { ... }                          // emits FileRead event with readText=null

data class WithEnv(
    val name: String,
    val overrides: Map<String, String>,
    val steps: List<StepSpec>,                 // Jenkins §1.7 nested-block signature
) : StepSpec { ... }                          // NOT (envOverrides, children) — Jenkins verbatim

data class ArchiveArtifacts(
    val name: String,
    val artifacts: String,                     // Ant-style glob (e.g., "target/**/*.jar")
    val allowEmptyArchive: Boolean = false,    // default false per Jenkins
    val fingerprint: Boolean = false,          // default false per Jenkins
    val onlyIfSuccessful: Boolean = false,     // default false per Jenkins
) : StepSpec { ... }
```

**Why no `returnValue` on `ReadFile`/`FileExists`**: Jenkins itself returns the value via the script-binding, but our DSL is a **builder** (`StageScope.steps: mutableListOf<StepSpec>()`). Returning a runtime value from a step-builder breaks the sealed-type purity. The pattern is the same as `sh(returnStdout: Boolean)` — capture happens in the executor, surfaced via a DomainEvent (FileRead) and re-exposed via an `output.txt` style journal entry. Consumers that need a runtime value should use `sh(returnStdout=true, script='cat "${path}"')`. **Documented limitation; deferred to ML-R8 with a script-binding API change.**

**Why `WithEnv(overrides, steps)` not `(envOverrides, children)`**: Jenkins §1.7 nested-block signature is `withEnv(List<String> env, Closure body)`. We map `env → overrides: Map<String,String>` (since we don't have untyped `String` lists in our sealed type) and `body → steps: List<StepSpec>` (because the DSL has no closure type). Documented in the KDoc on `WithEnv`.

### D3 — DSL desugars follow the `withCredentials` precedent

`PipelineDsl.withCredentials(bindings: List, block)` (PipelineDsl.kt:495) is the canonical nested-block façade. The 4 new builders follow the same shape:

```kotlin
fun writeFile(path: String, text: String? = null) {
    steps.add(StepSpec.WriteFile(name="writeFile:${path}", path=path, text=text))
}

fun writeFile(file: String) {  // overload: by-file instead of by-text
    steps.add(StepSpec.WriteFile(name="writeFile:${file}", path=file, text=null, file=file))
}

fun readFile(path: String) {   // no returnValue — builder only
    steps.add(StepSpec.ReadFile(name="readFile:${path}", path=path))
}

fun fileExists(path: String) {
    steps.add(StepSpec.FileExists(name="fileExists:${path}", path=path))
}

fun withEnv(overrides: Map<String, String>, block: StageScope.() -> Unit) {
    val inner = StageScope(stageName); inner.block()
    steps.add(StepSpec.WithEnv(
        name = "withEnv:${overrides.keys.joinToString(",")}",
        overrides = overrides,
        steps = inner.steps.toList(),
    ))
}

fun archiveArtifacts(artifacts: String, allowEmptyArchive: Boolean = false) {
    steps.add(StepSpec.ArchiveArtifacts(
        name = "archiveArtifacts:${artifacts}",
        artifacts = artifacts,
        allowEmptyArchive = allowEmptyArchive,
    ))
}
```

**No `withEnv(overrides: List<String>)` overload** — Jenkins's `List<String>` form (`"PATH+X=/usr/local/bin"`) is folded into the map form by the KDoc example, NOT a separate overload. The `EnvModel.apply()` extension (D5) implements the PATH+X prepend semantics atomically.

### D4 — Four new `DomainEvent` variants + `JsonEventLog` encoder/decoder extension

```kotlin
// DomainEvent.kt (23 → 27 variants)
data class FileWritten(
    override val eventId: String, override val runId: String, override val sequence: Long,
    override val occurredAt: Instant, val stageIndex: Int, val stepIndex: Int,
    val stepName: String, val path: String, val byteCount: Int, val sha256: String,
) : DomainEvent { override val kind: String = "FileWritten" }

data class FileRead(
    override val eventId: String, override val runId: String, override val sequence: Long,
    override val occurredAt: Instant, val stageIndex: Int, val stepIndex: Int,
    val stepName: String, val path: String, val exists: Boolean, val readText: String? = null,
) : DomainEvent { override val kind: String = "FileRead" }

data class ArtifactArchived(
    override val eventId: String, override val runId: String, override val sequence: Long,
    override val occurredAt: Instant, val stageIndex: Int, val stepIndex: Int,
    val stepName: String, val archivePath: String, val archiveSha256: String,
    val matchedCount: Int, val totalBytes: Long,
) : DomainEvent { override val kind: String = "ArtifactArchived" }

data class ArtifactArchiveFailed(
    override val eventId: String, override val runId: String, override val sequence: Long,
    override val occurredAt: Instant, val stageIndex: Int, val stepIndex: Int,
    val stepName: String, val pattern: String, val reason: String,
) : DomainEvent { override val kind: String = "ArtifactArchiveFailed" }
```

`JsonEventLog` (hand-rolled encoder/decoder; `when(event)` exhaustive match) gets 4 new branches on **both** sides. The encoder emits `kind: "FileWritten"` etc.; the decoder maps the string back to the typed data class. **Anti-log invariant INV-CR-CR1** carries forward: NO secret material in any field — `readText` is captured ONLY for files created in the same step (writeFile→readFile pipeline); reading from outside the run is recorded with `readText=null` to prevent secret exfiltration.

### D5 — `Effect.WRITES_WORKSPACE` extension + `EnvModel.PATH+X` prepend + replay matrix update

The two enums `dev.rubentxu.pipeline.v2.domain.durable.Effect` and `dev.rubentxu.pipeline.v2.sdk.Effect` (currently **DUPLICATED** with identical values) both gain:

```kotlin
enum class Effect { READ_ONLY, EXECUTES_SUBPROCESS, ABORTS_PIPELINE, WRITES_WORKSPACE }
```

**Effect deduplication is OUT OF SCOPE for ML-R7**: the duplication is pre-existing tech-debt; ML-R7 widens both copies atomically. Documented as a follow-up in ML-R7.1.

`writeFile` + `archiveArtifacts` carry `WRITES_WORKSPACE`; `readFile` + `fileExists` carry `READ_ONLY` (no journaled state changes); `withEnv` carries `EXECUTES_SUBPROCESS` (env mutation propagates to subprocesses inside the block).

`EffectReplayPolicy` decision matrix gets one new row: `WRITES_WORKSPACE` behaves identically to `EXECUTES_SUBPROCESS` for replay (always `RERUN` unless journaled SUCCEEDED → SKIP). The matrix comment in `EffectReplayPolicy.kt:14-26` is extended with this row.

**EnvModel extension** (`v2/pipeline-step-sdk/runtime/.../durable/EnvModel.kt`): the current implementation only special-cases JAVA_HOME/M2_HOME. The ML-R7 extension adds a **PATH prepend** semantics: any override key matching `^PATH\\+=(.*)` is interpreted as "prepend `\\1` to PATH" (split on `:`, deduplicate, keep order). This matches Jenkins `withEnv(["PATH+ANSIBLE=/opt/ansible/bin"])`. The implementation uses an in-place mutation under the `EnvModel.apply()` lock — already exists for JAVA_HOME/M2_HOME; just extends the regex matcher.

```kotlin
// EnvModel.kt (extension)
private val PATH_PLUS_REGEX = Regex("""^PATH\+=([\s\S]+)$""")
fun apply(overrides: Map<String, String>, env: MutableMap<String, String>) {
    overrides.forEach { (k, v) ->
        if (k.startsWith("PATH+=")) {
            val prepend = PATH_PLUS_REGEX.matchEntire(k)!!.groupValues[1]
            val current = env["PATH"] ?: ""
            val parts = (prepend.split(":") + current.split(":")).distinct()
            env["PATH"] = parts.joinToString(":")
        } else {
            env[k] = v
        }
    }
}
```

### D6 — `LocalArtifactStore` layout = `<controlDirRoot>/artefacts/<runId>/<stageName>/`

```kotlin
// pipeline-artefacts-local/.../LocalArtifactStore.kt
class LocalArtifactStore(
    private val controlDirRoot: Path,
) : AutoCloseable {
    fun stageDir(runId: RunId, stageName: StageName): Path =
        controlDirRoot.resolve("artefacts").resolve(runId.value).resolve(stageName.value)

    fun archive(runId: RunId, stageName: StageName, pattern: String): ArchiveResult {
        val workspace = workspaceResolver.resolve(stageName, stageIndex)  // existing method
        val matches = AntStyleGlob(pattern).match(workspace)              // D7
        if (matches.isEmpty() && !allowEmptyArchive) {
            throw EmptyArchiveException(pattern)
        }
        val archivePath = stageDir(runId, stageName).resolve("${Instant.now()}-${UUID.randomUUID()}.tar")
        TarWriter(archivePath).use { tw -> matches.forEach { tw.add(it) } }
        return ArchiveResult(archivePath, sha256, matches.size, totalBytes)
    }

    override fun close() { /* idempotent */ }
}
```

**Why `controlDirRoot/artefacts/<runId>/<stageName>/` not a single blob**: matches the existing `controlDirRoot/journal/` and `controlDirRoot/workspace/` layout (ML-R4/ML-R5 precedent); per-stage separation prevents cross-stage filename collisions; per-runId separation enables post-mortem retrieval (`./gradlew retrieveArtefacts --run <id>`).

### D7 — `AntStyleGlob` semantics = Spring `AntPathMatcher` extended for `**` middle-of-segment

We adopt the Jenkins `AntPattern` semantics verbatim:
- `*` matches one path segment
- `**` matches zero or more segments
- `?` matches one char
- `[abc]` matches one char from set
- `{a,b}` matches one segment from set

Implementation uses Spring `org.springframework.util.AntPathMatcher` (already on classpath via pipeline-application transitive). The wrapper exposes:
```kotlin
class AntStyleGlob(private val pattern: String) {
    fun match(root: Path): List<Path> { /* walk + filter + sort by path */ }
    fun toString(): String = pattern  // for canary-gate fingerprinting
}
```

**Why Spring's**: 200-LOC battle-tested implementation already on classpath; matches Jenkins's published semantics; carries forward the same anti-pattern (no recursive symlink resolution) as Spring's.

### D8 — `PipelineRun.executeDurableStepImpl` dispatch + `JsonEventLog` decoder + 5 `when(step)` sites

**5 `when(step)` sites** (verified at lines 778, 1107, 1143, 1215+ in `PipelineRun.kt` + ksp-generated dispatch in `stepTypeMetadata`):
1. `stepTypeMetadata(step)` (PipelineRun.kt:1107) — `when(step) → Triple<stepType, effects, replayPolicy>`.
2. `stepToParams(step)` (PipelineRun.kt:1143) — `when(step) → Map<String,JsonElement>` for OperationInput.
3. `executeDurableStepImpl(step, ...)` (PipelineRun.kt:1215+) — `when(step) → invoke SDK function`.
4. `classifyForEffectReplay(step)` (PipelineRun.kt:778) — `when(step) → Set<Effect>`.
5. `getReconcilerL1CompatStep(step)` — `when(step) → v1 Reconciler step`.

Each site gets 5 new branches (WriteFile, ReadFile, FileExists, WithEnv, ArchiveArtifacts). Total: **25 new branches across 5 sites** (NOT 5 as the proposal estimated).

The `JsonEventLog` decoder (currently hand-rolled encoder/decoder) gets **4 new branches** (D4). Total decoder surface: 27.

**Why 5 sites, not 1**: `stepTypeMetadata` is the type signature; `stepToParams` is the fingerprint; `executeDurableStepImpl` is the execution; `classifyForEffectReplay` is the journal lookup; `getReconcilerL1CompatStep` is the L1 compat shim. These are 4 different concerns × 5 new step kinds + 4 events in the decoder. **LOC budget = 25 + 4 = 29 branches ≈ 600 LOC** (25 × ~22 LOC + 4 × ~10 LOC).

### D9 — Atomic write + cross-filesystem fallback for `writeFile` + `archiveArtifacts`

The `writeFile` executor must be **atomic** (crash in the middle ≠ partial file):
```kotlin
// FileExecutor.kt (in pipeline-step-sdk:files)
fun writeAtomic(target: Path, bytes: ByteArray) {
    val parent = target.parent
    val tmp = Files.createTempFile(parent, ".${target.fileName}.", ".tmp")
    Files.write(tmp, bytes)
    try {
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE)
    } catch (e: AtomicMoveNotSupportedException) {
        // Cross-filesystem fallback: copy + delete (NOT atomic across fs, but best-effort)
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
    }
}
```

**Why both**: ATOMIC_MOVE fails on some non-POSIX filesystems (Docker volume mounts, certain network filesystems); the fallback is best-effort but DOES avoid leaving the tmp file behind.

`archiveArtifacts` uses the same atomic-write pattern (tmp tar → atomic rename into `stageDir/`). The `tar` format is plain `tar` (no compression) to keep the writer 50-LOC and the reader `tar -tf` compatible.

### D10 — Architecture test renames: F-ARCH-L6-001..003 → F-ARCH-L7-001..003 + 2 NEW (L7-004 + L7-005)

The existing ML-R6 IDs `F-ARCH-L6-001`, `F-ARCH-L6-002`, `F-ARCH-L6-003` are taken (FArchL6DeclaredKindTest, FArchL6JenkinsParityReflectionTest, FArchL6ArgvCleanlinessTest). ML-R7 does NOT renumber the ML-R6 IDs (would break the L6 arch-test durability). Instead:

| New ID | Title | File | What it greps |
|--------|-------|------|---------------|
| **F-ARCH-L7-001** | "Jenkins §1.7 verbatim step signatures" | `FArchL7JenkinsVerbatimStepTest.kt` | Reflection on WriteFile/ReadFile/FileExists/WithEnv/ArchiveArtifacts constructor parameter names vs. `JENKINS_FAMILIARITY_CATALOG.md` rows added in ML-R7 |
| **F-ARCH-L7-002** | "writeFile/withEnv path = workspace-relative (never absolute)" | `FArchL7WorkspaceRelativeTest.kt` | grep `pipeline-step-sdk/files/` + `pipeline-application/` for `path.startsWith("/")` near `writeFile`/`withEnv` callsites → zero matches |
| **F-ARCH-L7-003** | "AntStyleGlob has no `**/` recursion beyond `**` token" | `FArchL7AntStyleGlobShapeTest.kt` | Reflection on `AntStyleGlob.match()` — asserts pattern is parsed via Spring's `AntPathMatcher`, not a custom regex |
| **F-ARCH-L7-004** | "LocalArtifactStore writes under controlDirRoot/artefacts/, not elsewhere" | `FArchL7ArtefactLocationTest.kt` | grep `pipeline-artefacts-local/` for `controlDirRoot.resolve(...).resolve(...)` chains → ALL `artefacts-local` writes go under `controlDirRoot/artefacts/` |
| **F-ARCH-L7-005** | "DomainEvent sealed hierarchy is exhaustive (23 + 4 = 27)" | `FArchL7DomainEventExhaustivityTest.kt` | `DomainEvent::class.sealedSubclasses.map { it.simpleName }` == 27-element set including 4 NEW variants |

**Justification (vs. alternatives):**
- (a) Reuse L6 IDs by appending ML-R7 entries to existing F-ARCH-L6-XXX files — REJECTED: each arch test is a single concern; mixing L6 credential kinds with L7 step kinds in one file obscures the test.
- (b) Skip arch tests for ML-R7 — REJECTED: the carry-forward guarantee from ADR-0046 §D2 is "tier parity is durable via arch tests"; ML-R7 must add its own durability tests.
- (c) **Chosen**: 5 new L7 IDs in 5 new files; L6 IDs untouched.

### D11 — UAT-LOCAL-009: 12 top-step scenarios + 3 new compatibility fixtures + `UatLocal005CorpusUntouchedTest` size bump

`UatLocal009TopStepsTest` (NEW, ~1,400 LOC) covers 12 Jenkins-realistic scenarios — one per spec section + a few cross-cutting:
1. writeFile+readFile round-trip with sha256 verification.
2. fileExists returns true after writeFile, false before.
3. writeFile atomic-write crash test (kill mid-write → no partial file).
4. writeFile cross-filesystem fallback (mock ATOMIC_MOVE failure).
5. withEnv PATH+= prepend semantics (verify `PATH` after step).
6. withEnv JAVA_HOME override (carries forward existing L6 behaviour).
7. withEnv nested writeFile sees the override.
8. archiveArtifacts tarball sha256 matches the matched files' sha256.
9. archiveArtifacts with no matches + `allowEmptyArchive=false` → fails.
10. archiveArtifacts with no matches + `allowEmptyArchive=true` → empty tarball.
11. archiveArtifacts AntStyleGlob pattern coverage (`**/*.jar`, `target/*/lib/*.jar`).
12. Cross-step: writeFile → archiveArtifacts picks up the file.

3 NEW compatibility fixtures go under `v2/compatibility/`:
- `07-writeFile-readFile.pipeline.kts` (writes `output.txt`, reads it back).
- `08-withEnv-pipeline.kts` (`withEnv(["JAVA_HOME=/opt/jdk21"])` → `sh("echo $JAVA_HOME")`).
- `09-archive-artefacts.pipeline.kts` (`writeFile("target/x.jar", ...)` → `archiveArtifacts("target/*.jar")`).

`UatLocal005CorpusUntouchedTest.assertEquals(6, corpusFiles.size)` becomes `assertEquals(9, ...)` with the 3 NEW entries. **Pre-existing 6 fixtures are NOT touched** (corpus-untouched invariant carry-forward) — only the count is updated.

## Decision Summary

| # | Decision | Choice | Why |
|---|----------|--------|-----|
| D1 | Module split | 2 NEW modules + 7 EXT | Mirrors ML-R5/ML-R6 split precedent |
| D2 | StepSpec extension | 4 data classes + 1 nested block | Jenkins-verbatim signature contract |
| D3 | DSL desugars | Follow `withCredentials` precedent | Consistency with ML-R6 façade pattern |
| D4 | DomainEvent extension | 4 new variants + encoder/decoder | Anti-log invariant carry-forward |
| D5 | Effect extension | Add `WRITES_WORKSPACE`; EnvModel PATH+X | Required for replay matrix + Jenkins semantics |
| D6 | ArtefactStore layout | `<controlDirRoot>/artefacts/<runId>/<stageName>/` | Matches existing journal/workspace layout |
| D7 | Glob matcher | Spring `AntPathMatcher` wrapper | Battle-tested, already on classpath |
| D8 | PipelineRun dispatch | 5 sites × 5 kinds + 4 event branches | 29 new branches ≈ 600 LOC |
| D9 | Atomic write | ATOMIC_MOVE + cross-fs fallback | Durability + portability |
| D10 | Arch tests | 5 NEW L7 IDs in 5 NEW files | Durability per ADR-0046 §D2 |
| D11 | UAT + corpus | UAT-LOCAL-009 (12 scenarios) + 3 NEW fixtures | Tier-coverage parity with ML-R5/ML-R6 |

## Trade-offs

- **No `ReadFile.returnValue` field** (D2) — Documented limitation. Consumers must use `sh(returnStdout)` for runtime values. **Rejected `output.txt` workaround** because it leaks the read into the journal (CR-CR1 invariant). **Mitigation**: ML-R8 will introduce a script-binding API change to return values from step-builder DSL.
- **Effect enum stays duplicated** (D5) — Out-of-scope for ML-R7. Documented as ML-R7.1 follow-up.
- **5 `when(step)` sites** (D8) — Not collapsible into 1 because each site has a different concern (type metadata / fingerprint / execution / journal classification / L1 compat). **Mitigation**: each branch is small (~22 LOC); the 29-branch total is the unavoidable cost of the sealed-type design.

## Consequences

- **V1 untouched**: L7-tier parity is additive in v2 only. V1 pipelines continue to work via the L1 reconciler compat shim (no breaking changes).
- **L1-L6 invariants carry forward**: INV-CR-CR1 (anti-log), INV-L5-CR-001..006 (credential invariants), INV-L6-CR-001..013 (L6 credential invariants), INV-L4-*-001..003 (journal invariants).
- **New invariants (INV-L7-FS-001..008)** introduced in specs (8 total) — documented in spec §Invariants; enforced by F-ARCH-L7-001..005 arch tests.
- **Effect enum widening** is a binary-compatible change (adding a variant to an enum is source-compatible IF callers don't `when` exhaustively; sealed-class consumers DO need `when` updates — those are the 5 sites).
- **`JsonEventLog` decoder widening** is source-incompatible for anyone hand-rolling a custom encoder; internal use only (no external consumers).

## Rollback

`git revert` the merge commit + drop `:pipeline-step-sdk:files` and `:pipeline-artefacts-local` from `v2/settings.gradle.kts` + revert the 4 DomainEvent variants + revert the `WRITES_WORKSPACE` Effect variant + remove the 3 NEW compatibility fixtures (UatLocal005CorpusUntouchedTest stays at 6). New v2-format artefacts written by ML-R7 become unreadable by pre-ML-R7 builds (CHANGELOG warning); the existing `v2/compatibility/baseline.json` is regenerated as `baseline.v7.json` on first ML-R7 run.

## Open Questions

**None.** All design-time questions resolved in D1-D11. The 5 spec discrepancies surfaced during codebase verification (Effect duplication, WorkspaceResolver signature, EnvModel PATH+X gap, 5 dispatch sites, F-ARCH-L6 ID collisions) are RECONCILED in D5, D5, D5, D8, D10 respectively — they appear as inline footnotes in `design.md`, not as spec edits.

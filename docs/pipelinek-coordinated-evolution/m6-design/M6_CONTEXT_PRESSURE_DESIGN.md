# CRIC-M6 — CONTEXT/PRESSURE design

**Status:** design, ready for implementation.
**Worktree:** `pk-cric-m6-impl` (branch `impl/cric-m6-context-pressure`) at `3ac37658` (`release/cric-m3-v0.51.0-rc1`).
**Anchor:** the Block 6 plan ("Aportar contexto indexable de run/stage/step/operation y lecturas eficientes con presupuestos explícitos") and the audit at `M6_CONTEXT_PRESSURE_AUDIT.md`.
**Precedent:** `m2-design/M2_INSPECT_RECOVER_CANCEL_DESIGN.md` — same composition discipline, applied to the next block of CRIC guarantees.

This document is the design. It is the input to the M6 implementation in
`pk-cric-m6-impl`. The audit is at `M6_CONTEXT_PRESSURE_AUDIT.md`; this
design consumes §F.1, §F.2, §F.3 from that audit and names the API shape.

## 0. Scope and naming

The Block 6 plan names one scenario and one shape. The scenario is "Fabric needs
indexable context for run/stage/step/operation, with bounded reads that do not
exert backpressure on the writer". The shape is "additive methods on
`RuntimeIntrospectionPort` (or a new sibling port), composing the existing
journal + output read paths, projecting to closed ADTs, with explicit
time/result budgets and a resume cursor".

The audit's §F names three gaps:

```
F.1  Typed enumeration of stages / steps / operations   (additive method on RuntimeIntrospectionPort)
F.2  Per-operation terminality typed query              (additive method on RuntimeIntrospectionPort)
F.3  Bounded scan + resume cursor                       (options on F.1; closed ADTs for cursor)
```

Plus the negative scope from the audit's §F.4 / §F.5:

```
F.4  No Governor / fairness scheduler / query engine in PK
F.5  No new lease / scheduler / transport in PK
```

The design answers, in order:

1. What API surface the three additive methods expose (§A).
2. The closed ADT shapes the methods return (§B).
3. The composition rules over the existing journal + output read paths (§C).
4. The bounded-scan + resume-cursor semantics (§D).
5. How the design cross-walks to the existing UAT-PK / AAT / FABRIC evidence (§E).
6. The implementation phasing (§F).

## A. API surface

### A.1 Additive methods on `RuntimeIntrospectionPort`

The design adds **two new methods** to the existing
`RuntimeIntrospectionPort` in `:pipeline-runtime`. The audit's §F.1
recommendation (option 1: "additive on `RuntimeIntrospectionPort`") is
honoured.

```kotlin
fun interface RuntimeIntrospectionPort {
    fun inspect(runId: String): RuntimeIntrospectionResult
    fun runStructure(runId: String, options: StructureRunOptions = StructureRunOptions.Default): RunStructureResult
    fun inspectOperation(operationId: String, attempt: AttemptId): OperationInspectionResult

    companion object { /* existing */ }
}
```

Rationale for additive-on-existing-port (vs. a new `RunStructurePort`):

1. The existing `inspect` already composes `OperationJournal.listForRun`; the
   two new methods are thin compositions over the same primitive. A new port
   family would re-introduce the same constructor.
2. The M2 segregation rule (one verb per segregated port) is preserved
   because the new methods extend the same `RuntimeIntrospectionPort` — they do
   NOT introduce a new port. The port remains a fun interface with three
   method shapes.
3. The `DEFAULT_INSPECT_TIMEOUT_MS` budget applies to the new methods by
   construction; the M2 composition table is extended, not duplicated.

### A.2 Why `runStructure` is the verb (not `listStructure`)

The audit's §F.1 names "run structure" as the typed enumeration. The verb
`runStructure` reflects that the result is the FULL structure of a run, not a
list of just one type. The method name is consistent with `inspect` (the
existing verb on the same port): a noun-first verb reads as "produce a
<thing> about this <subject>".

### A.3 Why `inspectOperation` is the verb (not `getOperation`)

The audit's §F.2 names "per-operation terminality typed query". The verb
`inspectOperation` is consistent with `inspect` (existing) and the new
`runStructure`; the shape is "produce a typed view of this operation".
`getOperation` would suggest a raw key→value lookup, which is what the
INTERNAL `OperationJournal.get(opId, attempt)` does — the public method
projects to the typed `OperationInspection` ADT.

## B. Closed ADT shapes

### B.1 `RunStructure` and its envelope

```kotlin
sealed interface RunStructure {
    data class Stages(val stages: List<StageSummary>) : RunStructure
    data object Empty : RunStructure
    data class Unavailable(val refusal: IntrospectionRefusal) : RunStructure
}

sealed interface RunStructureResult {
    data class Structure(val structure: RunStructure) : RunStructureResult
    data class Refused(val refusal: IntrospectionRefusal) : RunStructureResult
}

data class StageSummary(
    val stageOrdinal: Int,
    val stageIndex: String,
    val startedAtMs: Long,
    val finishedAtMs: Long?,
    val steps: List<StepSummary>,
) {
    init {
        require(stageOrdinal >= 0) { "stageOrdinal must be >= 0, got $stageOrdinal" }
        require(stageIndex.isNotBlank()) { "stageIndex must be non-blank" }
        require(startedAtMs >= 0) { "startedAtMs must be >= 0, got $startedAtMs" }
        require(finishedAtMs == null || finishedAtMs >= startedAtMs) {
            "finishedAtMs must be null or >= startedAtMs, got $finishedAtMs vs $startedAtMs"
        }
    }
}

data class StepSummary(
    val stepOrdinal: Int,
    val stepKey: String,
    val startedAtMs: Long,
    val finishedAtMs: Long?,
    val state: StepState,
    val operations: List<OperationSummary>,
) {
    init {
        require(stepOrdinal >= 0) { "stepOrdinal must be >= 0, got $stepOrdinal" }
        require(stepKey.isNotBlank()) { "stepKey must be non-blank" }
        require(startedAtMs >= 0) { "startedAtMs must be >= 0, got $startedAtMs" }
        require(finishedAtMs == null || finishedAtMs >= startedAtMs) {
            "finishedAtMs must be null or >= startedAtMs"
        }
    }
}

data class OperationSummary(
    val operationId: String,
    val attempt: Int,
    val status: OperationStatus,
    val startedAtMs: Long?,
    val endedAtMs: Long?,
    val fingerprint: String?,
    val streams: OperationOutputStreams,
) {
    init {
        require(operationId.isNotBlank()) { "operationId must be non-blank" }
        require(attempt >= 1) { "attempt must be >= 1, got $attempt" }
        require(endedAtMs == null || startedAtMs == null || endedAtMs >= startedAtMs) {
            "endedAtMs must be null or >= startedAtMs"
        }
        require(fingerprint == null || fingerprint.length == 64) {
            "fingerprint must be null or 64 chars (SHA-256), got ${fingerprint?.length}"
        }
    }
}

sealed interface StepState {
    data object Pending : StepState
    data object Running : StepState
    data object Succeeded : StepState
    data class Failed(val failureKind: String) : StepState
    data object Cancelled : StepState
    data object Skipped : StepState
}

data class OperationOutputStreams(
    val stdoutStream: OutputStreamId?,
    val stderrStream: OutputStreamId?,
)
```

`OperationStatus` is the journal's existing enum, projected verbatim (the
audit explicitly says the F.2 method's answer carries the journal's status
enum). `OutputStreamId` is the published value class from `:pipeline-output`.

### B.2 `OperationInspection` and its envelope

```kotlin
sealed interface OperationInspectionResult {
    data class Inspection(val inspection: OperationInspection) : OperationInspectionResult
    data class Refused(val refusal: IntrospectionRefusal) : OperationInspectionResult
}

data class OperationInspection(
    val operationId: String,
    val attempt: Int,
    val runId: String,
    val stageOrdinal: Int,
    val stepOrdinal: Int,
    val status: OperationStatus,
    val startedAtMs: Long?,
    val endedAtMs: Long?,
    val deadlineMs: Long?,
    val fingerprint: String?,
    val streams: OperationOutputStreams,
) {
    init {
        require(operationId.isNotBlank()) { "operationId must be non-blank" }
        require(attempt >= 1) { "attempt must be >= 1, got $attempt" }
        require(stageOrdinal >= 0) { "stageOrdinal must be >= 0" }
        require(stepOrdinal >= 0) { "stepOrdinal must be >= 0" }
        require(endedAtMs == null || startedAtMs == null || endedAtMs >= startedAtMs) {
            "endedAtMs must be null or >= startedAtMs"
        }
        require(fingerprint == null || fingerprint.length == 64) {
            "fingerprint must be null or 64 chars, got ${fingerprint?.length}"
        }
    }
}
```

The `IntrospectionRefusal` ADT is the existing M2 one — no new variants
needed. `OperationInspectionResult.Refused` reuses the same closed ADT to
avoid a parallel refusal type.

### B.3 `StructureRunOptions` and `StructureCursor`

```kotlin
data class StructureRunOptions(
    val pageSize: Int = DEFAULT_STRUCTURE_PAGE_SIZE,
    val cursor: StructureCursor? = null,
    val includeTerminalStages: Boolean = true,
) {
    init {
        require(pageSize in 1..MAX_STRUCTURE_PAGE_SIZE) {
            "pageSize must be in [1, $MAX_STRUCTURE_PAGE_SIZE], got $pageSize"
        }
    }

    companion object {
        const val DEFAULT_STRUCTURE_PAGE_SIZE: Int = 64
        const val MAX_STRUCTURE_PAGE_SIZE: Int = 4096
        val Default: StructureRunOptions = StructureRunOptions()
    }
}

data class StructureCursor(
    val stageOrdinal: Int,
    val stepOrdinal: Int,
    val operationOrdinal: Int,
) {
    init {
        require(stageOrdinal >= 0) { "stageOrdinal must be >= 0" }
        require(stepOrdinal >= 0) { "stepOrdinal must be >= 0" }
        require(operationOrdinal >= 0) { "operationOrdinal must be >= 0" }
    }
}
```

The cursor is opaque to consumers (a value type, but consumers must treat it
as a token). A consumer that wants the next page passes the cursor from the
last result back as `options.cursor`; the result includes a fresh cursor for
the next call.

`StructureRunOptions` is closed at construction time (`init` validates
invariants). There is NO `Either`/`Result` re-use; the result envelope is the
sealed `RunStructureResult` pair.

## C. Composition rules

The new methods compose only existing read primitives:

| New method | Composed primitives |
|---|---|
| `runStructure` | `OperationJournal.listForRun` (internal) → project to `RunStructure`; `OperationJournal.get(opId, attempt)` (internal) for per-op enrichment; `OutputFrameIndex.streamsOfRun` (published) for `OperationOutputStreams.stdoutStream/stderrStream` |
| `inspectOperation` | `OperationJournal.get(opId, attempt)` (internal) → project to `OperationInspection`; `OutputFrameIndex.streamsOfRun` for streams (the runId is derived from the journal row) |

The new methods MUST NOT:

- Mutate any store (no `lease.acquire`, no `journal.append`, no
  `pin`, no `prune`).
- Block on external IO (the `DEFAULT_INSPECT_TIMEOUT_MS` budget applies).
- Re-implement the journal scan. The M2 adapter already does
  `journal.listForRun` once for `inspect`; the new methods may do it again,
  but they MUST NOT introduce a parallel scan or cache invalidation logic.

## D. Bounded-scan + resume-cursor semantics

The audit's §F.3 names "bounded scan + resume cursor" as a requirement.
The semantics are:

1. **Bounded.** Each call returns at most `pageSize` operations. The bound is
   per-call, not per-run; a 100k-operation run paginates across multiple
   calls, never returns all 100k in one.
2. **Deterministic order.** The order is the journal's `listForRun` order
   (execution order); pagination walks the operations in that order, grouped
   by stage then step.
3. **Resume cursor.** The result includes a `StructureCursor` if the result
   is truncated (more pages exist). The next call passes it as
   `options.cursor`; the call returns the next page.
4. **No backpressure on the writer.** A reader that pages slowly does NOT
   block the writer. The journal's append path does not consult
   `runStructure`'s state. (Verified by the M6-D contract test that
   concurrently calls `runStructure` while a writer appends.)
5. **Cursor identity is per-run.** The cursor encodes `(stageOrdinal,
   stepOrdinal, operationOrdinal)` — three ordinals, all per-run. A cursor
   from run A is not valid for run B (a consumer that re-uses it gets a
   `RunStructureResult.Refused(IntrospectionRefusal.UnknownRun)` or
   truncated result depending on the type-mismatch case).

`MAX_STRUCTURE_PAGE_SIZE = 4096` is the cap; `init` enforces it. The cap is
the design's answer to "what's the largest single call we'll serve?" — a
consumer that wants more pages.

## E. Cross-walk to UAT / AAT / FABRIC evidence

| Fabric UAT case | PK port that satisfies it | Test that pinna |
|---|---|---|
| UAT-CTX-001 (run-detail view: N stages, M steps, K operations) | `RuntimeIntrospectionPort.runStructure` | `RuntimeIntrospectionPortStructureAdapterTest` (M6) |
| UAT-CTX-002 (per-operation terminality typed query) | `RuntimeIntrospectionPort.inspectOperation` | `RuntimeIntrospectionPortOperationAdapterTest` (M6) |
| UAT-CTX-003 (bounded scan + resume cursor for a very large run's structure) | `StructureRunOptions` + `StructureCursor` on `runStructure` | `RuntimeIntrospectionPortStructurePaginationTest` (M6) |
| UAT-PK-M6-001 | same as UAT-CTX-001 | `RuntimeIntrospectionPortStructureAdapterTest` |
| UAT-PK-M6-002 | same as UAT-CTX-002 | `RuntimeIntrospectionPortOperationAdapterTest` |
| UAT-PK-M6-003 | same as UAT-CTX-003 | `RuntimeIntrospectionPortStructurePaginationTest` |

The M6 audit's §E.2 names `UAT-PK-M6-001..003`; this design maps each to a
test class. The cross-walk also covers §E.5's "absence of outcome changes"
scenario: I.5 (PASS-by-design + tested) — the new methods do not change
`inspect`'s answers; they are additive, not modified.

## F. Implementation phasing

The M6 implementation ships in three commits on `impl/cric-m6-context-pressure`:

1. **M6.1 (single commit):** add the new types + the two new methods to
   `RuntimeIntrospectionPort` + the new sealed ADTs. The methods throw
   `NotImplementedError` (intentional, so the build stays green; the adapter
   commit wires them up).
2. **M6.2 (single commit):** wire the two new methods in
   `RuntimeIntrospectionPortStoreAdapter` over the existing journal +
   `OutputFrameIndex` read paths. The adapter tests (`StructureAdapterTest`,
   `OperationAdapterTest`, `StructurePaginationTest`) live in this commit.
3. **M6.3 (single commit):** register the new capabilities
   (`runtime.structure.v1` and `runtime.operation.v1`) in the `Capabilities`
   companion; update `INTERFACE_CONTRACT.md` §6 + the
   "Capacidades publicadas (CRIC-M6)" table; refresh
   `CONTRACT_SHA256.txt`; bump `0.51.0 → 0.53.0` (CRIC-M6 train open).
4. **M6.4 (single commit):** update the example-external-consumer to
   compile against the new ports (`RuntimePortsContractTest` extension);
   the `RuntimePortsContractTest` already has the structure; this commit
   adds the `runStructure` and `inspectOperation` capability checks.
5. **M6.5 (single commit):** `release-receipt` for the M6 candidate
   (`v0.53.0-rc1`), then `PK_CONTRACT_HANDOFF` to Fabric, then a final
   "chore(release): bump 0.52.0 -> 0.53.0 (CRIC-M6 train open)" — mirroring
   the M2/M3 release shape.

The candidate is `v0.53.0-rc1`. The train skips `0.52.0` because the 0.52.0-rc1
work in the main branch is the integration of M2 + M3, not a separate train;
M6 is the next train after M3 in the CRIC chain.

---

**Design author:** PipelineK runtime author (authority for `:pipeline-runtime`).
**Design date:** 2026-10-11.
**Audit consumed:** `M6_CONTEXT_PRESSURE_AUDIT.md` (audit `83e1c28d`,
1142 lines, sections A-G, decision §F "BLOCK 6 PK-CANDIDATE NEEDED").
**Status:** ready for implementation in `pk-cric-m6-impl`.

# S2-A6 / G3T — `core.pwd.tmp` Deterministic Tmp Semantics (Receipt, post-correction)

Status: **IMPLEMENTED_UNCERTIFIED** — production code registered, fitness matrix green,
scope firewall observed. Awaiting review before commit/push. **NO** authority flip,
**NO** legacy removal, **NO** public DSL change, **NO** `LEGACY_PLUGIN_IDS` mutation.

Authority: docs/v2/07-uat/S2_A6_CORE_PWD_G0_CHARACTERIZATION_RECEIPT.md,
docs/v2/07-uat/S2_A6_CORE_PWD_G3_MIGRATION_READINESS.md, AGENTS.md STEP_CONSTITUTION /
EXTERNAL STEP AUTHORING GUIDE.

This receipt supersedes the pre-correction version (handler reached 3 capabilities
including `DURABLE_OPERATION_IDENTITY_CAPABILITY`, and the handler embedded
`Files.createDirectories` + `EventSink` + sha256 directly). The post-correction
design collapses those into a single typed `TemporaryWorkspaceOperations` port,
exactly mirroring the `core.sh → ShellOperations` pattern (LB-02 / G3-A4.2).

---

## 1. Slice in one paragraph

G3T introduces the registry candidate for `core.pwd.tmp` (a new StepKey, sibling of
`core.pwd`) whose tmp workspace path is **derived deterministically from the canonical
OpId.format string**. The path is `workspaceRoot/tmp-pwd-<sha256(opId.format())>`,
so the same OpId always lands on the same directory and a different OpId lands on a
different directory. No `System.currentTimeMillis()`, no `UUID.randomUUID()` for the
path identity, no random suffix — verified by the fitness matrix.

The handler is **pure policy**: it declares exactly one capability
`TEMPORARY_WORKSPACE_OPERATIONS_CAPABILITY` and reaches a single typed port
`TemporaryWorkspaceOperations`. The adapter
`TemporaryWorkspaceOperationsAdapter` is the only place that holds a filesystem
reference, an `EventSink` reference, and a `MessageDigest`/`sha256` derivation. The
handler does not see any of those. This mirrors `core.sh → ShellOperations →
ShOperationsAdapter`. `DURABLE_OPERATION_IDENTITY_CAPABILITY` is NOT part of the
contract — the handler does not need the durable OpId, the adapter does.

## 2. What changed (production)

### 2.1 Capability surface — `Capabilities.kt`

```text
// Removed (pre-correction only):
//   data class DurableOperationIdentity(val value: String)
//   val DURABLE_OPERATION_IDENTITY_CAPABILITY = StepCapability("runtime.durable-operation-identity")

// Added (post-correction):
interface TemporaryWorkspaceOperations {
    fun resolveOrCreate(): TempWorkspaceResult
}

data class TempWorkspaceResult(val path: String)

val TEMPORARY_WORKSPACE_OPERATIONS_CAPABILITY: StepCapability =
    StepCapability("workspace.temporary-operations")
```

The `DurableOperationIdentity` capability was REMOVED — the handler does not consume
durable identity. The adapter (constructed by the bridge, never seen by the handler)
binds the canonical OpId internally.

### 2.2 Bridge wiring — `CanonicalRuntimeCapabilityAccess.kt`

```text
// Pre-correction (REMOVED):
// builder[DURABLE_OPERATION_IDENTITY_CAPABILITY] = DurableOperationIdentity(value = context.opId.format())

// Post-correction (ADDED):
val tmpOps: TemporaryWorkspaceOperations = TemporaryWorkspaceOperationsAdapter(
    runIdString = context.runId,
    opId = context.opId,
    workspaceRoot = context.shOptions.workspaceRoot,
    eventSink = context.eventSink,
)
builder[TEMPORARY_WORKSPACE_OPERATIONS_CAPABILITY] = tmpOps
```

The adapter is constructed once per `CanonicalRuntimeContext` (per Step invocation)
and bound to the canonical OpId the coordinator already owns. The handler never
constructs an adapter, never sees an OpId.

### 2.3 Adapter — `application/durable/TemporaryWorkspaceOperationsAdapter.kt`

The single canonical implementation of `TemporaryWorkspaceOperations`. It is the
ONLY place in `core.pwd.tmp`'s path that:

- holds an `EventSink` reference,
- calls `Files.createDirectories`,
- reads `OpId.format()` / constructs `sha256(opId.format())`,
- constructs and emits a `PwdResolved` event.

```kotlin
class TemporaryWorkspaceOperationsAdapter(
    private val runIdString: String,
    private val opId: OpId,
    private val workspaceRoot: Path,
    private val eventSink: EventSink,
) : TemporaryWorkspaceOperations {
    override fun resolveOrCreate(): TempWorkspaceResult {
        val token = sha256Hex(opId.format())                       // 64 lowercase hex
        val tmpPath = workspaceRoot.resolve("tmp-pwd-$token")
            .toAbsolutePath()
        Files.createDirectories(tmpPath)                              // idempotent
        val absolutePath = tmpPath.toString()
        eventSink.append(
            PwdResolved(
                eventId = UUID.randomUUID().toString(),              // event id only, family-consistent
                runId = runIdString,
                sequence = 0L,                                        // sink assigns canonical
                occurredAt = Instant.now(),                           // event timestamp only
                path = absolutePath,
                workspaceRoot = workspaceRoot.toAbsolutePath().toString(),
                sha256 = sha256Hex(absolutePath),
            ),
        )
        return TempWorkspaceResult(path = absolutePath)
    }
}
```

Symmetric with `core.sh → ShOperationsAdapter → ShExecution.invokeShell` (LB-02).

### 2.4 Step contract — `CorePwdTmpStep.kt`

| Aspect | Value | Rationale |
|---|---|---|
| `KEY` | `core.pwd.tmp` (dot taxonomy) | New StepKey, sibling of `core.pwd`. |
| Input | `PwdTmpInput = data object` (Unit) | No input params. |
| Output | `PwdTmpOutput(path: String) = PwdOutput` (typealias) | D2 APPROVED — same typed shape as `core.pwd`. |
| Input codec envelope | `{}` | Same envelope as `core.isUnix`. |
| Output codec envelope | `{"kind":"pwd.tmp","path":"<abs>"}` | Family-tagged. |
| `descriptor.effects` | `{WRITES_WORKSPACE}` | The tmp directory is physically created. |
| `descriptor.replayPolicy` | `MEMOIZED` | D4 frozen: fresh/rerun idempotent mkdir; resume/reuse cached. |
| `requiredCapabilities` | **`{TEMPORARY_WORKSPACE_OPERATIONS_CAPABILITY}`** (SINGLE) | Post-correction: capability-only handler. |
| Handler body | `val ops = ctx.capabilities.get(TEMPORARY_WORKSPACE_OPERATIONS_CAPABILITY); val result = ops.resolveOrCreate(); PwdTmpOutput(result.path)` | Pure policy. No `Files`, no `EventSink`, no `OpId`, no `sha256`. |

### 2.5 Handler purity guard (test #2 in the fitness matrix)

The test class `CorePwdTmpStepUnitTest` mechanically asserts that
`CorePwdTmpStep.kt`'s source has:

- ZERO imports of `java.nio.file.Files`, `EventSink`, `PwdResolved`, `OpId`, `MessageDigest`.
- ZERO executable references to `Files.createDirectories`, `sha256`, `UUID.randomUUID`, `Instant.now`, `DURABLE_OPERATION_IDENTITY_CAPABILITY`.
  (Comments and docstrings are ignored by `lineSequence().filter { !startsWith("*") && !startsWith("//") }`.)

### 2.6 Registration — `CoreStepRegistryFactory.kt`

```text
CorePwdTmpStep.registerInto(this)
```

Same `registerInto` seam as `CorePwdStep`. The StepKey `core.pwd.tmp` is **not** in
`LEGACY_PLUGIN_IDS`, so `StructuralFamilyResolver.classify("core.pwd.tmp", registry)`
returns `Registry` from the moment of registration.

### 2.7 Bridge test updated — `CanonicalRuntimeCapabilityAccessTest.kt`

The pre-correction CDE.3-d1 freeze pinned the bridge to `{EVENT_SINK_CAPABILITY}`.
Since then G1+ accreted multiple capabilities, and the test had drifted out of sync
(it was failing pre-existing in `8c7343ed`). G3T updates the test to pin the actual
canonical set:

```text
EVENT_SINK_CAPABILITY
SHELL_OPERATIONS_CAPABILITY            (LB-02 / A4)
WORKSPACE_OPERATIONS_CAPABILITY        (S2-A3 / G1)
STAGE_IDENTITY_CAPABILITY              (S2-A4 / G1)
PLATFORM_IDENTITY_CAPABILITY           (S2-A5 / G1)
WORKSPACE_IDENTITY_CAPABILITY          (S2-A6 / G1)
TEMPORARY_WORKSPACE_OPERATIONS_CAPABILITY (S2-A6 / G3T post-correction)  <-- NEW
```

The test was already failing on the base SHA — updating it to the canonical set is
required work for G3T because adding a capability requires updating both the bridge
and its frozen test in lockstep. Fresh run: `4 / 0 / 0`.

## 3. What did NOT change (scope firewall)

- **`LEGACY_PLUGIN_IDS`** — still `{core.milestone, core.deleteDir, core.cleanWs, core.load, core.pwd, core.waitUntil, core.archiveArtifacts}` (7 keys). `core.pwd` stays. `core.pwd.tmp` not added.
- **`CanonicalCoreStepCommand.*`** — legacy sealed subtypes unchanged.
- **`CanonicalCoreStepDecoder`** — decoder branches unchanged. `StepSpec.Pwd(tmp=true)` still decodes to legacy `Pwd(tmp=true)`; `core.pwd.tmp` is reachable ONLY through the generic registry path (not yet wired — that's G3R).
- **`PipelineDsl.pwd()` / `ScriptedStepFacade.pwd()`** — public DSL surface untouched. `pwd(tmp=true)` still returns `runtimeConfig.userDir()` (PATH A) and is still implemented via the legacy dispatcher.
- **Compiler / PSI lowering** — no new sealed `StepSpec` subtype for `core.pwd.tmp`.
- **`core.pwd` legacy source** — unchanged. `CorePwdStep` remains the registry candidate for `pwd(false)`, but `core.pwd` is still routed to the legacy dispatcher because of LEGACY_PLUGIN_IDS membership.
- **Counters** — `7 / 7 / 7` legacy / `8` registry (8 = 7 + the new `core.pwd.tmp`).
- **`DURABLE_OPERATION_IDENTITY_CAPABILITY`** — REMOVED (production code). Test references it only in a guard assertion that it must NOT appear in `CorePwdTmpStep` executable code.
- **Authority flip / legacy removal** — **NOT done**. Out of G3T scope; G4/G5 slices later.

## 4. Fitness matrix — `CorePwdTmpStepUnitTest`

Source: `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/CorePwdTmpStepUnitTest.kt`
Counts: **24 tests / 0 failures / 0 errors / 0 skipped** (fresh run, canary XML confirmed).
SHA-256: `0f11ea7727f1a7bf1e98a567e89d0c5353fbd022b6eea39a42caf60ec2876fc6`

| # | Test | Law enforced |
|---|---|---|
| 1 | identity | KEY = `core.pwd.tmp`; dot taxonomy; descriptor.stepId/name consistent. |
| 2 | handler purity — zero runtime-infrastructure imports/executable refs | **Architectural guard**: `CorePwdTmpStep.kt` has NO `Files`/`EventSink`/`OpId`/`MessageDigest`/`UUID`/`Instant`/`sha256`/`Files.createDirectories` in executable code. Docstrings are ignored. |
| 3 | structural family — `core.pwd.tmp` = `Registry` | `LEGACY_PLUGIN_IDS !∋ "core.pwd.tmp"`; resolver returns `Registry`. |
| 4 | structural family — `core.pwd` = `LegacyCore` (post-G1 invariant) | `LEGACY_PLUGIN_IDS ∋ "core.pwd"`; resolver returns `LegacyCore`. |
| 5 | counters — legacy 7 unchanged | `LEGACY_PLUGIN_IDS.size == 7`; `core.pwd` in; `core.pwd.tmp` not in. |
| 6 | descriptor — `WRITES_WORKSPACE` + `MEMOIZED` | D2/D4 frozen at G2. |
| 7 | required capabilities = exactly `TEMPORARY_WORKSPACE_OPERATIONS` | Post-correction: SINGLE capability, no `DURABLE_OPERATION_IDENTITY`, no `EVENT_SINK`, no `WORKSPACE_IDENTITY` in the contract. |
| 8 | input codec round-trip | Empty-object unit envelope. |
| 9 | output codec round-trip | `{"kind":"pwd.tmp","path":...}` decodes to `PwdTmpOutput`. |
| 10 | output codec rejects foreign envelope kind | `IllegalArgumentException` for `kind="pwd"`. |
| 11 | handler — executes once when single capability is available (stub port) | Stub `TemporaryWorkspaceOperations` returns a fixed path; handler forwards; stub.calls == 1. |
| 12 | handler — missing capability rejects admission | `ExecutionPreparation.Rejected`; handler invocation 0. |
| 13 | handler — unrelated capability does not satisfy the contract | `{EVENT_SINK, SHELL_OPS, WORKSPACE_IDENTITY, PLATFORM_IDENTITY}` does NOT admit `core.pwd.tmp`. Guards against handler reading EventSink directly instead of going through the port. |
| 14 | handler — real-seam full admission: typed output + exactly one event | Production-shape `CanonicalRuntimeContext` ⇒ `RegistryExecutionBoundary.coexecute` ⇒ `StepOutcome.Success`; decoded `PwdTmpOutput` matches expected; one `PwdResolved` with `sha256(path)`. |
| 15 | adapter — D5 same OpId ⇒ same path | Two `resolveOrCreate` invocations with the same `OpId` produce identical `path`; two `PwdResolved` events. |
| 16 | adapter — D6 different stepIndex ⇒ different path | Distinct `stepIndex` ⇒ distinct paths. |
| 17 | adapter — D7 different bodyPath ⇒ different path | `bodyPath = [BlockSegment(0, core.echo)]` ≠ `[BlockSegment(0, core.sh)]` ⇒ distinct OpId.format() ⇒ distinct path. |
| 18 | adapter — D8 different branchIndex ⇒ different path | `branchIndex = 0` ≠ `1` ⇒ distinct path. |
| 19 | adapter — D9 path bounded by workspaceRoot | All paths start with `workspace.toString()`. |
| 20 | adapter — D10 no timestamp/random suffix | Path matches `^tmp-pwd-[0-9a-f]{64}$` exactly. |
| 21 | adapter — D11 idempotent `Files.createDirectories` | Same OpId × 2 ⇒ same path; second `mkdir` is a no-op. |
| 22 | adapter — D12 exactly one `PwdResolved` per fresh execution | `eventsFor(runId).filterIsInstance<PwdResolved>().size == 1`. |
| 23 | duplicate registration fails closed | `IllegalArgumentException` containing `core.pwd.tmp`. |
| 24 | DSL firewall — `StepSpec.Pwd(tmp=true)` still produced by legacy DSL | Legacy sealed subtype intact; `core.pwd.tmp` is registry-only. |

## 5. Spot-checks (L2 family evidence)

Fresh run, canary XMLs confirmed:

| Suite | tests | failures | errors | skipped |
|---|---|---|---|---|
| `CorePwdTmpStepUnitTest` (NEW) | 24 | 0 | 0 | 0 |
| `CorePwdStepUnitTest` (unchanged) | 22 | 0 | 0 | 0 |
| `CoreIsUnixStepUnitTest` (unchanged) | 18 | 0 | 0 | 0 |
| `CoreIsUnixStepContractSuiteTest` (unchanged) | 22 | 0 | 0 | 0 |
| `CanonicalCoreStepCommandRegistryTest` (unchanged) | 9 | 0 | 0 | 0 |
| `CanonicalRuntimeCapabilityAccessTest` (UPDATED to canonical set) | 4 | 0 | 0 | 0 |
| `CoreIsUnixRegistryPrimaryFitnessTest` (unchanged) | 8 | 0 | 0 | 1 |

Total: **107 tests / 0 failures / 0 errors** (1 pre-existing skip).

## 6. Base-vs-head evidence (regression defense, Rule 16)

Nine candidate failures observed in the broader module run (`:pipeline-application:test`):

| Test | Base SHA `8c7343ed` | G3T v2 | Verdict |
|---|---|---|---|
| `A4_REGISTRY_PRIMARY_Core_Sh_Proof_Test > family classifier routes core sh through Registry` | FAIL (1/11) | FAIL (1/11) | **pre-existing** |
| `CoreLegacyStepMetadataResolverTest > resolves sleep metadata` | FAIL (2/4) | FAIL (2/4) | **pre-existing** |
| `CoreLegacyStepMetadataResolverTest > ordinary steps declare no recovery` | FAIL | FAIL | **pre-existing** |
| `CoreSleepRegistryPrimaryFitnessTest > production registry contains exactly the registered core steps` | FAIL (1/7) | FAIL (1/7) | **pre-existing** |
| `RegistryStepMetadataResolverTest > a remaining legacy core key delegates to the legacy core authority` | FAIL (1/5) | FAIL (1/5) | **pre-existing** |
| `CompatibilityCorpusTest > fixture14CredentialsBindings` | (S2-A5/G8 documents) | (same) | **pre-existing** |
| `UatCompat001CorpusSmokeRunTest` | (cascade of fixture14) | (same) | **pre-existing** |
| `UatLocal005CheckoutGitTest > SC-007 poll detects changed SHA` | **FAIL** | **FAIL** | **pre-existing** (SCM-Git timing-sensitive, worktree-confirmed) |
| `CanonicalRuntimeCapabilityAccessTest > bridge exposes exactly the declared event sink capability` | **FAIL** | **FIXED** (set pinned to canonical 7 capabilities) | **G3T corrects** |

Method: `git stash push -u -m "g3t-wip-v2"` → run failing tests on base `8c7343ed`
(identical failures confirmed for `A4_PROOF`, `CoreLegacyStepMetadata`,
`CoreSleepFitness`, `RegistryStepMetadata`, `SC-007`, `CanonicalRuntimeCapabilityAccessTest`)
→ `git stash pop`.

**No new regressions introduced by G3T.** SC-007 is timing-sensitive (AGENTS.md rule 11
explicitly forbids `maxParallelForks` for SCM/timing UATs); observed flake on the
broader module run is unrelated.

## 7. Counter snapshot (post-G3T)

```text
LEGACY_PLUGIN_IDS.size  = 7     (frozen at S2-A5/G8)
Registry candidates     = 8     (S2-A6/G3T adds core.pwd.tmp as NEW entry)
core.pwd = LegacyCore    (unchanged)
core.pwd.tmp = Registry  (NEW, G3T)
Bridge exposes          = 7 capabilities (was 6 pre-G3T, added TEMPORARY_WORKSPACE_OPERATIONS)
StepKey flips done       = 3  (core.error, core.emit.event, core.isUnix; core.pwd.tmp is NEW not a flip)
Total = 7 legacy + 8 registry = 15 StepKeys of interest.
```

## 8. Determinism laws (D5–D12) — formal

These laws live in the **adapter** (post-correction), not in the handler:

```text
Let OpId = (runId, stageIndex, stepIndex, branchIndex?, bodyPath[]).
Let f(op) = sha256(op.format()).
Let h(op, w) = w.resolve("tmp-pwd-" + f(op)).

D5  ∀op,w. adapter(op,w).resolveOrCreate() is well-defined and h(op,w) is a directory.
D6  ∀op1,op2. op1.stepIndex != op2.stepIndex => h(op1,w) != h(op2,w).
D7  ∀op1,op2. op1.bodyPath != op2.bodyPath => h(op1,w) != h(op2,w).
D8  ∀op1,op2. op1.branchIndex != op2.branchIndex => h(op1,w) != h(op2,w).
D9  ∀op,w. h(op,w).startsWith(w).
D10 ∀op,w. h(op,w) matches `tmp-pwd-[0-9a-f]{64}` and contains no
        currentTimeMillis / UUID.randomUUID token (verified structurally).
D11 ∀op,w. adapter(op,w).resolveOrCreate() invoked twice => same h(op,w) (no duplicate dir).
D12 ∀op,w. adapter(op,w).resolveOrCreate() emits exactly one PwdResolved event with
        path = h(op,w), workspaceRoot = w, sha256 = sha256(h(op,w)).
```

Laws D5–D12 are mechanically asserted by tests #15–#22 of the fitness matrix
(adapter-level tests, all in the same `CorePwdTmpStepUnitTest` class).

## 9. Scope firewall — explicit NOT touched

```text
- LEGACY_PLUGIN_IDS                          : NOT touched (7 keys, unchanged)
- CanonicalCoreStepCommand sealed subtypes  : NOT touched
- CanonicalCoreStepDecoder branches         : NOT touched
- PipelineDsl.pwd / ScriptedStepFacade.pwd  : NOT touched (still PATH A eager)
- Scripting compiler / PSI lowering         : NOT touched (no new StepSpec subtype)
- CanonicalCoreStepMetadata for core.pwd.tmp: NOT touched (intentional hard-miss)
- CanonicalNodeDispatcher                    : NOT touched
- CorePwdStep / CorePwdStepUnitTest         : NOT touched
- CoreIsUnixStep family                     : NOT touched
- Authority flip (REGISTRY_PRIMARY for core.pwd): NOT done (out of G3T scope; G4 later)
- Legacy removal (LEGACY_REMOVED)            : NOT done (out of G3T scope; G5 later)
- Counter mutation (LEGACY_PLUGIN_IDS size) : NOT done
- Public DSL surface (pwd(tmp=true))        : NOT touched (PATH A still in effect)
```

## 10. Target state (post-G3T)

```text
core.pwd.tmp:
  REGISTERED              = true   (CoreStepRegistryFactory)
  StructuralFamily        = Registry
  TMP_SEMANTICS_READY     = true   (deterministic path identity)
  Effects                 = {WRITES_WORKSPACE}
  ReplayPolicy            = MEMOIZED
  requiredCapabilities    = {TEMPORARY_WORKSPACE_OPERATIONS} (single, post-correction)
  Handler                 = pure policy (capability-only, no IO)
  Adapter                 = TemporaryWorkspaceOperationsAdapter (encapsulates sha256 + Files + PwdResolved)
  Capability access       = declared (no CanonicalRuntimeContext leak)
  Observability           = 1 PwdResolved event per fresh execution (emitted by adapter)
  Fitness rows            = 24/24 (fresh, canary verified)
  Base vs head regression = 0 (8 pre-existing failures pre-exist in 8c7343ed)

core.pwd:
  AUTHORITY_FLIP_READY    = false  (still blocked on PWD_RUNTIME_RETURN_RECONNECTION -> G3R)
  LEGACY_PLUGIN_IDS       = present (unchanged)
  Counter                 = 7 (unchanged)

Legacy counters:
  LEGACY_PLUGIN_IDS.size  = 7  (frozen at S2-A5/G8)
  Registry candidates     = 8  (NEW core.pwd.tmp)
```

## 11. Architectural diff vs pre-correction (why this slice was redone)

Pre-correction handler (REMOVED):

```kotlin
val workspace: WorkspaceIdentity = ctx.capabilities.get(WORKSPACE_IDENTITY_CAPABILITY)
val operation: DurableOperationIdentity = ctx.capabilities.get(DURABLE_OPERATION_IDENTITY_CAPABILITY)
val sink: EventSink = ctx.capabilities.get(EVENT_SINK_CAPABILITY)
val token = sha256(operation.value)
val tmpPath = workspace.workspaceRoot.resolve("tmp-pwd-$token")
Files.createDirectories(tmpPath)
val absolutePath = tmpPath.toAbsolutePath().toString()
sink.append(PwdResolved(eventId = UUID.randomUUID().toString(), ..., path = absolutePath, ...))
PwdTmpOutput(path = absolutePath)
```

Three capabilities in the contract; the handler embedded filesystem, event emission,
and crypto derivation. Violated AGENTS.md rule 9 (handler adapts to typed seams).

Post-correction handler (CURRENT):

```kotlin
val ops: TemporaryWorkspaceOperations =
    ctx.capabilities.get(TEMPORARY_WORKSPACE_OPERATIONS_CAPABILITY)
val result: TempWorkspaceResult = ops.resolveOrCreate()
PwdTmpOutput(path = result.path)
```

One capability, one typed port call, typed result forward. Symmetric with
`core.sh → ShellOperations → ShOperationsAdapter` (LB-02 certified pattern).

## 12. Next slice (G3R — LFC-2R runtime-return consumer)

Per the user's mandate, G3T ends here. The follow-up G3R will:

1. Extend `ScriptedStepFacade.pwd(callSite, tmp)` (or equivalent) so that the public
   DSL `pwd(tmp=true)` lowers to a `RegistryStepSpec` for `core.pwd.tmp` (instead of
   the legacy `StepSpec.Pwd(tmp=true)`).
2. Drive the candidate from a real `.pipeline.kts` consumer (LFC-2R second consumer
   — first was `pwd(false)` G3 → already MIGRATION_READY).
3. Re-run the family classifiers, capability admission, and the `core.pwd.tmp`
   handler end-to-end through the canonical durable spine.
4. ONLY after G3R passes → `core.pwd AUTHORITY_FLIP_READY = true` → G4 (REGISTRY_PRIMARY) → G5 (LEGACY_UNREACHABLE) → G6 (LEGACY_REMOVED) → G7/G8 (acceptance + certification).

`LEGACY_PLUGIN_IDS` mutation, legacy decoder branch removal, and authority flip
are all **explicitly forbidden in G3T** and remain forbidden until the dedicated
G4/G5 slices.

## 13. STOP — review boundary

This slice ends here. No commit, no push, no fast-forward. Awaiting explicit GO
before the next git operation.

Receipt evidence:
- Production diff:
  - `Capabilities.kt` — added `TemporaryWorkspaceOperations` + `TempWorkspaceResult` + `TEMPORARY_WORKSPACE_OPERATIONS_CAPABILITY`; **removed** `DurableOperationIdentity` + `DURABLE_OPERATION_IDENTITY_CAPABILITY`.
  - `CanonicalRuntimeCapabilityAccess.kt` — added `TEMPORARY_WORKSPACE_OPERATIONS_CAPABILITY` wiring with `TemporaryWorkspaceOperationsAdapter`; **removed** `DURABLE_OPERATION_IDENTITY_CAPABILITY` wiring.
  - `CoreStepRegistryFactory.kt` — registers `CorePwdTmpStep` (unchanged from pre-correction).
  - NEW: `application/durable/TemporaryWorkspaceOperationsAdapter.kt` (101 lines).
  - NEW: `CorePwdTmpStep.kt` (rewritten, pure policy).
- Test diff:
  - NEW: `CorePwdTmpStepUnitTest.kt` (538 lines, 24 tests).
  - UPDATED: `CanonicalRuntimeCapabilityAccessTest.kt` (1 test rewritten to pin canonical 7-capability set; was failing on base SHA, now green).
- Fitness: `CorePwdTmpStepUnitTest` **24 / 0 / 0** at SHA-256 `0f11ea7727f1a7bf1e98a567e89d0c5353fbd022b6eea39a42caf60ec2876fc6`.
- Family: **107 / 0 / 0** across CorePwdTmp + CorePwd + CoreIsUnix + Contract + Registry + Bridge.
- Base-vs-head regression: **0** new failures (8 pre-existing failures confirmed in `8c7343ed` via worktree method).
- Counters: legacy 7 / 7 / 7 (unchanged); registry now 8 (core.pwd.tmp is the only addition); bridge exposes 7 capabilities (was 6 pre-G3T).

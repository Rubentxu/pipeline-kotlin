# S2-A4 / G3+G4 — core.emit.event Migration Readiness & REGISTRY_PRIMARY Flip

- **G3 commit**: `32465435` (pre-flip evidence) · **G4 commit**: `45a47a7a` (flip)
- **G3 base**: `294c67e1` (G2) · **G4 base**: G3 tree
- Combined receipt; evidence is clearly split PRE-flip (G3) and POST-flip (G4).

---

## G3 — Migration Readiness (pre-flip, no authority change)

`CoreEmitEventMigrationReadinessFitnessTest` — **8 tests / 0 failures** (fresh XML), later
ARCHIVED at G4 with `@Disabled` (pattern: `CoreErrorMigrationReadinessFitnessTest`), never
inverted.

Proven pre-flip:

| Check | Result |
|---|---|
| CoreEmitEventStep registered | YES (production factory) |
| core.emit.event ∈ LEGACY_PLUGIN_IDS | YES |
| StructuralFamily | LegacyCore |
| G2 UNKNOWN_DIFFERENTIALS | 0 (carried) |
| EVENT_SINK_CAPABILITY via real `CanonicalRuntimeCapabilityAccess` | available |
| STAGE_IDENTITY_CAPABILITY via real bridge | available |
| Candidate admission | fail-closed |

**Structural control independence (the flip-safety property):**
`CanonicalStructuralPreparation → StructuralOverlayProjection` produces the CatchErrorEntered /
CatchErrorTriggered push/pop descriptors from the RAW envelope — it never constructs
`CanonicalCoreStepCommand.EmitEvent` and never touches `CanonicalEmitEventNodeDispatcher`.

```text
structural control semantics  ≠  legacy execution semantics
```

So `catchError` cannot accidentally depend on the legacy decoder for its scope stack.

**Real-seam candidate execution** (production routing untouched):
`RegistryExecutionPreparation → capability admission → RegistryExecutionBoundary → handler`
- CatchErrorEntered → Success / 0 events
- StageMarkedUnstable → Unstable / 1 event (StageIdentity fallback through the real bridge)
- unknown kind → SCHEMA / 0 events

---

## G4 — REGISTRY_PRIMARY + LEGACY_UNREACHABLE

**Surgical flip**: only `"core.emit.event"` removed from `LEGACY_PLUGIN_IDS` (9 → 8). Legacy
forms physically PRESENT until G5: `EmitEvent` subtype, `EMIT_EVENT_PLUGIN_ID` decoder branch,
`CanonicalEmitEventNodeDispatcher.kt` + `CanonicalNodeDispatcher` emitEvent branch, metadata row.

### Exit state (authorized final state of this tranche)

```text
core.emit.event:
  REGISTERED         = true
  REGISTRY_PRIMARY   = true
  LEGACY_UNREACHABLE = true
  LEGACY_REMOVED     = false   (G5)
  CONTRACT_SUITE     = false   (G6)
  CERTIFIED          = false   (G8)

StructuralFamily   = Registry
LEGACY_PLUGIN_IDS  = 8   (milestone, deleteDir, cleanWs, load, pwd, isUnix, waitUntil, archiveArtifacts)
metadata           = 9
dispatchers        = 9
```

### Post-flip fitness

`CoreEmitEventRegistryPrimaryFitnessTest` — **10 tests / 0 failures** (fresh XML):
registered=true; key ∉ LEGACY_PLUGIN_IDS (runtime + source-level parse); family == Registry;
registry resolves the canonical definition; descriptor == frozen legacy row (READ_ONLY +
MEMOIZED); composite `RegistryStepMetadataResolver` reads the descriptor as effective metadata
authority; legacy forms physically present; capabilities unchanged (EVENT_SINK +
STAGE_IDENTITY); **RegistryExecutionBoundary contains NO core.emit.event-specific branch** (no
privileged core path); StructuralOverlayProjection still projects catchError push/pop from the
raw envelope post-flip.

### Decisive catchError UAT (real route, production-like fixture)

`EmitEventCatchErrorRegistryUatTest` — **3 tests / 0 failures** (fresh XML):

```text
DSL/compiler marker envelopes
 → CanonicalStructuralPreparation
 → StructuralOverlayProjection push/pop        (pre-decode, key-based)
 → core.emit.event through the REGISTRY        (post-flip)
 → handler Success (markers) / Unstable (warn)
```

- **fresh**: scope push/pop correct; tolerated inner failure → continuation; NO spurious event
  from the silent markers (ERR-S-008); sibling executes; journaled ops all SUCCEEDED.
- **replay**: a memoized `CatchErrorEntered` (seeded SUCCEEDED under its fingerprint, exactly as
  a prior fresh run would write) still pushes its scope pre-reconcile (C6); MEMOIZED reuse does
  not corrupt the context stack; no double publication of marker events.
- **StageMarkedUnstable via the real coordinator seam**: payload `stageName` wins when present;
  fallback to `StageIdentity.name` when absent; event cardinality exactly 1;
  `RunOutcome.Unstable`.

### Regression evidence (all fresh XML canaries)

| Suite | Result |
|---|---|
| CoreEmitEventStepUnitTest (updated to post-flip authority) | 18/0 |
| CoreEmitEventDifferentialContractTest (no-cutover test → post-flip test) | 14/0 |
| CanonicalCoreStepCommandRegistryTest (8-key set) | green |
| CoreError / CoreSleep / CoreWriteFile RegistryPrimaryFitness | green (counters updated) |
| S3 WriteFile / Sleep / Error LegacyRemoved arch fitness | green (transitional 8/9/9 snapshot) |
| UatLocal012ErrorHandlingTest (catchError/warnError/unstable) | 8/0 |
| CanonicalCoordinatorScopeStackTest (overlay stack) | 5/0 |
| EchoDurableSpineTest (registry durable spine) | 2/0 |
| CompatibilityCorpusTest.fixture17 | 1/0 |

### Rule-16 discipline

`CanonicalDurableRunCoordinatorTest` remains at **12 failures — the frozen baseline set
(diff empty vs `0f53e487` worktree)**. The `catchError` test fails at BASE with the
byte-identical reason (`No canonical core metadata registered for plugin 'core.sh'` — that
test constructs a legacy-only coordinator). Its real-production counterparts
(`UatLocal012ErrorHandlingTest`, `CanonicalCoordinatorScopeStackTest`, and the new UAT) are
GREEN post-flip. Nothing fixed "de paso"; the set was not widened.

---

## Next

G5 (LEGACY_REMOVED: delete EmitEvent subtype + decoder branch + dispatcher file/branch +
metadata row, counters → 8/8/8) — **awaiting explicit authorization. STOP here.**

# WU-LPR-104 — core.readFile / core.fileExists Blocker Closure Receipt

Date: 2026-09-18 · Base: `f37dde8b` (WU-LPR-060) · Status: **DONE / CERTIFIED**

## Why

The LPR certification checkpoint identified `readFile` / `fileExists` as the last
cheap blockers in the v1 profile: the DSL funs existed (`StepSpec.ReadFile` /
`StepSpec.FileExists`) but the canonical compiler lowering produced `core.readFile` /
`core.fileExists` step nodes with NO registered runtime definitions — every run was
fail-closed exit 2 ("non-canonical plugins").

## What was built (registry golden path, no new seams)

1. **`WorkspaceOperations` seam extended** (`pipeline-application`):
   `readFile(file, encoding): FileReadResult` and `fileExists(file): FileExistsResult`.
   `WorkspaceOperationsAdapter` binds the certified substrates
   (`FileReadExecutor` / `FileExistsExecutor`) and is the **single emitter** of both
   observability events. `runId` now flows into the adapter for event identity.
2. **`CoreReadFileStep` / `CoreFileExistsStep`** (`core.readFile`, `core.fileExists`):
   typed Input/Output codecs with canonical envelopes
   `{"kind":"readFile","file","encoding"}` / `{"kind":"fileExists","file"}`,
   `Effect.READ_ONLY` + `ReplayPolicy.MEMOIZED` descriptors,
   capability-routed handlers (`WORKSPACE_OPERATIONS_CAPABILITY` only), registered
   through `CoreStepRegistryFactory.registry()`. Zero coordinator/dispatcher changes.
3. **Compiler**: explicit `encodePayload` branches for ReadFile/FileExists (previously
   fell to `declarativeValue` fallback that no codec could decode).
4. **New event `FileExistsChecked`** (DomainEvent family → 45 variants): path + exists
   flag only. Encoder/decoder wired in `JsonEventLog`; exhaustive `when` branches added
   to `InMemoryEventStore`, `SqliteEventStore`, `SequenceAssigner`, `EnvelopeProjector`.
5. **Semantics**: statement semantics (DSL funs return Unit today). Jenkins predicate
   semantics preserved: `fileExists` on a missing file is a legitimate success
   (`exists=false` in the event), NOT a failure. `readFile` on a missing/guarded file
   is a typed step failure from the substrate.

## Invariant compliance

- **INV-L6-EVT-001**: file content NEVER enters the event channel. `FileRead` carries
  path+sha256+size only; `FileExistsChecked` carries path+exists only. Asserted by the
  corpus test (fixture content string is asserted absent from the full event stream).
- **Single emitter**: handlers never touch fs or events; adapter is the only emitter.
- **Capability discipline**: handler reaches only `WORKSPACE_OPERATIONS_CAPABILITY`.
- **Zero production semantic changes elsewhere**: no dispatcher cases, no legacy
  catalogue entries, no coordinator routing changes.

## Evidence (fresh runs, this SHA)

- E2E probe, installed distribution, `v2/compatibility/23-readfile.pipeline.kts`:
  exit 0; `FileRead{path=…lpr104-readme.txt, sha256=bdfb8fc69dbf…, size=13}`;
  `FileExistsChecked{exists=true}` for present file, `FileExistsChecked{exists=false}`
  for missing file; `RunFinished outcome=success`; no content in event stream.
- `CompatibilityCorpusTest`: **23/23** (`tests="23" failures="0" errors="0"`, fresh XML),
  includes new `fixture23ReadFile` with typed observability assertions.
- `:pipeline-application` durable package: **306 tests, 0 failures**.
- `FArchL7DomainEventExhaustivityTest` updated to 45 variants: green.
- `DomainEventRoundTripTest` variant count updated to 45: green.
- Ledger regenerated: **16 steps, 15 SUPPORTED_CERTIFIED, 1 EXPERIMENTAL**.
  032 admission table amended (16 rows).

## Pre-existing failures (NOT regressions, base-SHA reproduced)

- `EventHistoryContractTest` (`pipeline-events`) — flaky sequence-assignment failures
  ([0,0,0,0,5,6,0]-style expectations), reproduced identical at clean base `f37dde8b`
  (git stash run: 2 failures with the same class and messages). SQLite sequence
  persistence defect, out of WU-LPR-104 scope. This suite is not part of the durable
  612-test baseline.

## Counters (post-WU)

- Certified Steps: 15 · Legacy executable: 0 · Registry-primary: 15 (+1 EXPERIMENTAL)

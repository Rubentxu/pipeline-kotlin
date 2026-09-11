# S2-A4 / G0 — `core.emit.event` baseline audit

> Cycle: `cycle/lfc2-e1-s2-legacy-catalog-burn-down`
> Slice: S2-A4 (`core.emit.event`)
> Gate: **G0 — baseline / pre-existing state**
> Date: 2026-09-11T15:25Z · Base SHA: `0f53e487` (post S2-A3 close, receipt-family backfill)
> Production changes in this gate: **NONE** (characterization only).

## 1. Legacy source-of-truth inventory (all present at base)

1. **Catalogue entry**: `CanonicalCoreStepDecoder.LEGACY_PLUGIN_IDS` contains
   `"core.emit.event"` (residual count 9).
2. **Metadata row**: `CanonicalCoreStepMetadata["core.emit.event"] =
   StepMetadata(setOf(Effect.READ_ONLY), ReplayPolicy.MEMOIZED)`.
3. **Decoder**: `CanonicalCoreStepCommand.EmitEvent(kind, payload)` data class
   (payload: `Map<String, String?>`, entries minus `kind`, values via
   `jsonPrimitive.contentOrNull` → **nullable** map) + `EMIT_EVENT_PLUGIN_ID`
   decode branch. Note: unlike writeFile/milestone, the decode branch has NO
   `kind == "emitEvent"` self-check and NO field validation — decoding always
   succeeds for any dsl-v1 payload with a `kind` string.
4. **Dispatcher**: `CanonicalEmitEventNodeDispatcher` (124 lines) with
   `ALLOWED_EMIT_EVENT_KINDS = {CatchErrorEntered, CatchErrorTriggered,
   StageMarkedUnstable, FileWritten}` (ADR-0054 §D6 whitelist; LFC1-008 D1).
   Context: `CanonicalEmitEventDispatchContext(runId, stageName, eventSink)`.
5. **DSL/compiler**: no user-facing `emitEvent(...)` DSL function exists;
   `PipelineDsl.kt:1555` explicitly forbids `unstable` in favour of the
   pre-compiler rewrite. The compiler's `emitStep(...)` lowers internal
   control-flow projections to `core.emit.event` nodes:
   - catchError rewrite: entry marker `CatchErrorEntered(buildResult, stageResult, enteredAt, message?)`
     + exit marker `CatchErrorTriggered(buildResult, stageResult, message, emitted=true)`.
   - warnError/unstable rewrite: `StageMarkedUnstable(message)`.
6. **Existing test seams**: `CanonicalDurableRunCoordinatorTest` drives the
   catchError continuation scenario through the legacy path (asserts
   `RunOutcome.Unstable`, exactly 1 published `CatchErrorTriggered`, sibling
   execution). `CanonicalCoreStepCommandRegistryTest` pins metadata
   (READ_ONLY + MEMOIZED). **No compatibility fixture exists** for emit.event
   (fixtures 01–18 have none); coverage is compiler-rewrite-driven.

## 2. Canonical legacy envelope (fingerprint authority until G5)

```json
{"kind":"<eventKind>","<field>":"<value>",...}
```

Emitted by `DslCompiledPipelineCompiler.emitEventPayload`: `kind` first, then
user/compiler fields in map order; **null values are encoded as JSON `null`**
(`JsonNull`), not dropped. No schemaVersion beyond the standard
`VersionedStepPayload("dsl-v1", ...)` wrapper.

## 3. Behaviour characterization matrix (legacy dispatcher, verbatim)

```text
kind                   event emitted   outcome     required fields            failure surface
---------------------------------------------------------------------------------------------------
CatchErrorEntered      no              Success     none (marker; coordinator  —
                                                   validates buildResult and
                                                   pushes the scope frame)
CatchErrorTriggered    no              Success     none (scope-only exit      —
                                                   marker; the REAL
                                                   CatchErrorTriggered event
                                                   is published by the
                                                   coordinator's fold-walk,
                                                   EM-5/EM-6 D5, ERR-S-008)
StageMarkedUnstable    yes             Unstable    message (mandatory);       missing message ->
                                                   stageName defaults to      UNTYPED
                                                   ctx.stageName              IllegalStateException
                                                   current stage              from error()
FileWritten            yes             Success     path, sha256, size         missing/bad fields ->
                                                   (mandatory);               UNTYPED
                                                   atomicallyMoved            IllegalStateException
                                                   defaults false             from error()/null
unknown kind           no              Failure     —                          TYPED:
                                                                              PipelineFailure(
                                                                              FailureKind.SCHEMA,
                                                                              "…not in the canonical
                                                                              whitelist…")
```

### Fresh / replay durable behaviour (derived, to be re-proven in G3)

- Metadata is READ_ONLY + MEMOIZED. Under `DefaultEffectReplayPolicy`:
  fresh → RERUN; existing SUCCEEDED entry → **SKIP (ReuseCompleted)** —
  the READ_ONLY reuse path, like `core.sleep`. On reuse the handler must not
  run and, critically, **no duplicate `StageMarkedUnstable`/`FileWritten`
  event may be appended** (event emission is the observable effect).
- Divergence: changed `kind`/fields → different fingerprint → typed
  divergence failure.
- Caveat for G3: `FileWritten`/`StageMarkedUnstable` events ARE external
  observations appended to the sink; classifying the whole step READ_ONLY is
  the legacy decision and stays byte-compatible, but its event-on-reuse
  implication MUST be explicitly re-proven (reuse ⇒ no second append).

## 4. Open design questions for G1 (recorded, NOT decided here)

### Q1 — untyped `error(...)` escape on valid-kind incomplete payloads

`StageMarkedUnstable` without `message` and `FileWritten` with
missing/non-numeric `size` reach Kotlin `error(...)`/`?.toLongOrNull() ?:
error(...)` inside the dispatcher. These escape as untyped
`IllegalStateException`, which the run loop maps to
`RunOutcome.Failure(INFRASTRUCTURE)` — NOT a typed `SCHEMA` failure.
G1 design must decide: keep byte-compatible untyped surface, or validate in
the input codec (typed `SCHEMA` rejection before effects). Note the decode
branch is currently total, so codec-side validation is a semantic change
that requires an explicit compatibility decision.

### Q2 — public contract vs internal control-flow markers

Compiler call-site analysis shows:

- `CatchErrorEntered` / `CatchErrorTriggered`: **internal coordinator
  protocol markers only** — the compiler injects them for catchError
  rewriting; no DSL surface emits them; the dispatcher emits NO DomainEvent
  for either. They are part of the closed canonical envelope contract, but
  they are NOT user events.
- `StageMarkedUnstable`: internal-but-user-visible projection (the
  warnError/unstable rewrite), emits a real event, returns Unstable.
- `FileWritten`: whitelist member with NO compiler call site — reachable only
  if an external/plugin actor constructs the node directly. De facto dead as
  a user contract unless plugin SDK surface uses it.

Implication for G1: `CoreEmitEventStep`'s typed input should model the
whitelist as a sealed ADT of kinds (fail-closed unknown → typed SCHEMA), but
the public/user contract question (hide the two markers? keep `FileWritten`?)
belongs to a design note, not to this G0. No behavioural change is proposed
for S2-A4; any contract narrowing is a separate openspec change.

### Q3 — capability hypothesis (for G1, not implemented here)

`EVENT_SINK_CAPABILITY` already exists and is the seam `CoreEchoStep` uses
(`CanonicalRuntimeCapabilityAccess` binds it to the runtime eventSink).
Reusing it for `CoreEmitEventStep` adds zero new capability machinery; the
handler would get `EventSink` via `ctx.capabilities.get(EVENT_SINK_CAPABILITY)`.
One nuance to carry into design: the legacy context also injects
`stageName` (for the `StageMarkedUnstable` fallback). The capability object
or the typed input must carry the stage identity — but stage identity is
already part of `StepHandlerContext`, so likely no capability change is
needed at all. Verify at G1.

## 5. G0 exit state

```text
core.emit.event:
  REGISTERED         = false
  REGISTRY_PRIMARY   = false
  LEGACY_UNREACHABLE = false
  LEGACY_REMOVED     = false
  CERTIFIED          = false
```

Counters unchanged: LEGACY_PLUGIN_IDS = 9, metadata rows = 9, dispatchers = 9.

## 6. Evidence

- Source read (this gate, no edits): `CanonicalEmitEventNodeDispatcher.kt`
  (full), `CanonicalCoreStepDecoder.kt` EmitEvent branch,
  `CanonicalCoreStepMetadata.kt` row, `DslCompiledPipelineCompiler.kt`
  `emitStep`/`emitEventPayload` + catchError/warnError rewrite call sites,
  `CanonicalDurableRunCoordinatorTest` catchError scenario,
  `CanonicalRuntimeCapabilityAccess` (EVENT_SINK_CAPABILITY binding),
  `CoreEchoStep` capability usage.
- Compatibility fixture inventory 01–18: no emit.event fixture (gap noted
  for G3; a fixture may be added as test-only material).

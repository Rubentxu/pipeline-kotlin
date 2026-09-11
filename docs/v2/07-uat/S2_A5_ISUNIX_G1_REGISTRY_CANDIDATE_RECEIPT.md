# S2-A5 / G1 — REGISTRY CANDIDATE: `core.isUnix`

**Gate:** G1 — registry seam proof (candidate registration only). **STOP after this gate.**
**Base:** `b5986872` (G0). Production delta: 3 files, +34 lines, plus 2 new files (candidate + tests). Zero legacy mutation.

## Gate state after G1

```text
core.isUnix:
  REGISTERED         = true     (CoreStepRegistryFactory.registry() → CoreIsUnixStep.registerInto)
  REGISTRY_PRIMARY   = false    (StructuralFamilyResolver: LegacyCore — legacy membership wins)
  LEGACY_UNREACHABLE = false
  LEGACY_REMOVED     = false
  CONTRACT_SUITE     = false
  CERTIFIED          = false

StructuralFamily = LegacyCore
legacy counters  = 8 / 8 / 8   (LEGACY_PLUGIN_IDS == metadata rows == dispatcher classes)
CanonicalIsUnixNodeDispatcher / CanonicalCoreStepCommand.IsUnix / legacy decoder: PRESENT, UNTOUCHED
PipelineDsl.isUnix() / runtimeConfig.osName(): UNCHANGED
```

## The three explicit authorities

```text
PATH_A:  PipelineDsl.isUnix / runtimeConfig.osName()
         public / script-visible value, computed at construction time
         current fake/controller-side result (whitelist A, "" -> true placeholder)
         UNCHANGED in this gate

PATH_B:  CanonicalIsUnixNodeDispatcher
         durable legacy observation (System.getProperty + substring whitelist,
         UnixDetected event, no typed output)
         UNCHANGED in this gate — still the production authority

PATH_C:  CoreIsUnixStep (registry candidate)
         typed runtime result, classifier-compatible with PATH_B (C == B verbatim)
         PlatformIdentity observation + Step-owned classification policy
         NOT the production authority while "core.isUnix" ∈ LEGACY_PLUGIN_IDS
```

## Capability design (the G0 counterexample earned this)

```kotlin
data class PlatformIdentity(val osName: String)
val PLATFORM_IDENTITY_CAPABILITY = StepCapability("runtime.platform-identity")
```

Separation of concerns (frozen for this slice):

```text
PlatformIdentity  owns environmental OBSERVATION  (os.name read lives ONLY in
                  CanonicalRuntimeCapabilityAccess — the single System.getProperty site)
CoreIsUnixStep    owns the isUnix classification POLICY (classify(osName))
```

The handler never touches `System.getProperty`; pinned by
`handler never imports process or system-property authority` (synthetic observation
`SunOS` on a Linux host classifies `false` — a leaking handler would classify `true`).

## Contract

```text
IsUnixInput   = data object       (legacy durable payload is the empty object "{}"; codec preserves it)
IsUnixOutput  = data class (isUnix: Boolean) : TypedStepOutput, outcome = Success
descriptor    = ({READ_ONLY}, MEMOIZED)  — byte-equivalent to the legacy metadata row
requiredCapabilities = { PLATFORM_IDENTITY_CAPABILITY, EVENT_SINK_CAPABILITY }
```

**TYPED_RUNTIME_OUTPUT = CANDIDATE_ARCHITECTURAL_DELTA = NOT YET APPROVED.**
Legacy durable path emits NO typed output (Success + UnixDetected only). The candidate
exposes the classified Boolean because the product baseline requires `isUnix` to be a
runtime-valued Step (future reconnection of the DSL). Whether this output is persisted
as `OperationOutput`, how it participates in replay, and how it feeds the DSL are **G2
decisions**. Within one invocation the typed value and the `UnixDetected` payload are
derived from the SAME `classify()` call so they can never disagree.

## Classification policy: PATH_B verbatim (C == B)

`osName.lowercase()` contains any of `linux | mac | darwin | freebsd`. Tested rows:
`Linux/linux/Mac OS X/Darwin/FreeBSD → true`; `""/Windows 11/SunOS/AIX/OpenBSD → false`.
PATH_A is deliberately NOT copied; the canonical-policy choice (A vs B) is the G2
differential with a clean `C == B` baseline.

## Honest-DSL debt (visible, deferred)

`PipelineDsl.isUnix()` decides the value at pipeline CONSTRUCTION time (controller side)
while `core.isUnix` decides at EXECUTION time (target side). With split workers this can
contradict (controller Linux, worker Windows). The architectural direction —
`isUnix() → execution target → runtime PlatformIdentity → typed Boolean result` — is
NOT resolved in G1; it MUST be resolved or explicitly bounded before G8 records
CERTIFIED. Mechanism and (if needed) extra gate: to be designed.

## Evidence

- `CoreIsUnixStepUnitTest` (14/0, fresh XML canary):
  - classifier matrix PATH_B verbatim (positive + negative rows)
  - input codec preserves `"{}"`; output codec round-trip + foreign-envelope rejection
  - descriptor equality, exact capability set, duplicate-registration fail-closed
  - observation-from-capability pin (SunOS synthetic)
  - real seam: `RegistryExecutionPreparation` admission fail-closed on missing
    PLATFORM_IDENTITY **and** missing EVENT_SINK (handler invocation = 0, UnixDetected = 0),
    full-capability execution through `RegistryExecutionBoundary` → Success,
    typed output, exactly 1 UnixDetected with matching sha256
- Registration-only invariants: family = LegacyCore; counters 8/8/8; legacy dispatcher present.
- L3 regression: CoreSleep/CoreEmitEvent unit + fitness suites all green after the shared
  factory change; `CoreSleepRegistryPrimaryFitnessTest` inventory expectation updated to
  include `core.isUnix` (the ONLY test edited besides the new suite).
- Pre-existing red baseline (NOT widened, base-SHA stash-run evidence at `b5986872`):
  `A4_REGISTRY_PRIMARY_Core_Sh_Proof_Test` (classifier), `CoreLegacyStepMetadataResolverTest`
  (2x, stale `core.sleep` legacy-metadata lookups), `RegistryStepMetadataResolverTest`
  (stale `core.sleep` legacy delegation), `CompatibilityCorpusTest.fixture14CredentialsBindings`,
  plus UAT Local005/007 flakes — all fail identically WITHOUT this slice's changes.

## Status

**G1 COMPLETE — candidate registered, admission fail-closed proven, real seam green.**
**STOP.** No G2. The G2 agenda is now: (1) canonical platform-semantics decision (A vs B
vs hybrid), (2) TYPED_RUNTIME_OUTPUT approval (persistence/replay/DSL feed), (3) the
construction-vs-execution honest-DSL contradiction.

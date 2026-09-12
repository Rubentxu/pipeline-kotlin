# STEP_CONTRACT_HARNESS_DESIGN.md

Status: ACCEPTED (G6 test-infrastructure refactor, LFC-2E1 cert-harness cycle)
Scope: test source set only (`pipeline-application` test fixtures). ZERO production
semantics changes, zero counter/legacy mutations.

## 1. Problem

Every G6 StepContractSuite duplicated ~600 lines of identical generic rows
(identity, contract completeness, codec laws, registry resolution, capability
admission, coordinator success/failure, fresh durable, replay, observability,
real registry path). One implementation per Step means every generic law exists
N times; a doc/test drift (e.g. the deleteDir coverage-matrix row 17 vs
`EffectReplayPolicy` semantics mismatch found in G6 review) becomes possible in
every copy.

## 2. Solution

`StepContractCertification` (test fixture,
`pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/stepcontract/StepContractCertification.kt`):
a typed DSL that implements each GENERIC law ONCE. Suites pin Step-specific
expectations declaratively and keep one explicit `@Test` per coverage-matrix row
(so JUnit XML retains the row names and the assertion power stays visible).

```kotlin
private val suite = certifyStep(CoreDeleteDirStep.definition, DeleteDirInput(path = ".")) {
    envelope("""{"kind":"deleteDir","path":"."}""")               // byte-identical dsl-v1 envelope (REQUIRED)
    output(DeleteDirOutput(path = "/tmp/ws/...", deletedCount = 7, sha256 = "abc123")) // codec sample (REQUIRED)
    expectEffects(setOf(Effect.WRITES_WORKSPACE))                 // descriptor pin (REQUIRED)
    expectReplay(ReplayPolicy.MEMOIZED, ReplayDecision.RERUN)     // replay law pin (REQUIRED)
    capability(DELETE_DIR_OPERATIONS_CAPABILITY)                  // EXACT required set (repeat)
    rejectInput(EncodedStepValue("""{"kind":"echo"}"""), because = "foreign kind")     // decode rejection probe (repeat)
    rejectOutput(EncodedStepValue("""{...}"""), because = "non-deleteDir kind")        // decode rejection probe (repeat)
}
```

The builder fails fast (`require`) if a REQUIRED pin is missing or the pinned
envelope does not match `inputCodec.encode(canonicalInput)` — the canonical
envelope row can no longer drift from the codec.

## 3. Mapping table: old row → harness builder / row method

| Matrix row | Old suite (manual) | Harness |
|---|---|---|
| 1. identity | 10-line hand-rolled KEY/dup checks | `suite.row01_identity()` |
| 2. contract completeness | per-field assertEquals | pins (`expectEffects`/`expectReplay`/`capability`) + `row02_contractCompleteness()` |
| 3. input codec round-trip | manual encode/decode assertEquals | `row03_inputCodecRoundTrip()` |
| 3b. input legacy-defaulting (Step-specific) | bespoke | stays bespoke (uses `suite.contract.inputCodec`) |
| 4. input codec rejection | per-probe runCatching | `rejectInput(...)` pins + `row04_inputCodecRejection(probe)` |
| 5. output codec round-trip (+ durable string round-trip) | manual | `row05_outputCodecRoundTrip()` |
| 6. output codec rejection | per-probe runCatching | `rejectOutput(...)` pins + `row06_outputCodecRejection(probe)` |
| 7. canonical envelope byte-identical | manual assertEquals vs literal | `envelope(...)` pin (fail-fast) + `row07_canonicalEnvelope()` |
| 8. production registry resolution | manual | `row08_registryResolution()` |
| 9. factory freshness | manual | `row09_registryFactoryFreshness()` |
| 10. capability declaration | manual exact-set assert | `capability(...)` pins + `row10_capabilityDeclaration()` |
| 11. capability admission → Ready | manual `RegistryExecutionPreparation.prepare` | `row11_capabilityAdmission()` |
| 12. missing capability rejects | manual per capability | `row12_missingCapability(cap)` (call once per declared cap) |
| 12b. conditional exposure (Step-specific) | bespoke CanonicalRuntimeContext probe | stays bespoke (helper: `suite.runtimeContext(controlDirRoot = null, ...)`) |
| 14. success via canonical coordinator | manual harness + journal rows | `row14_successViaCoordinator()` |
| 15. typed failure (handler throws) | manual throwing registry + coordinator | `row15_typedFailure()` |
| 16. fresh durable | manual | `row16_freshDurable()` |
| 17. replay matrix | hand-written per-policy version | `row17_replayMatrix()` — ONE implementation, observes StepStarted counts: SKIP → handler not re-run; RERUN → handler re-runs, same op row; ABORT → fails closed |
| 17b. replay decision unit pin | manual `DefaultEffectReplayPolicy().decide` | `row17b_replayDecisionUnit()` (asserts pinned decision == policy) |
| 18. observability pair | manual | `row18_observability()` |
| 19. Step-specific event payload (e.g. `DirDeleted`) | bespoke | stays bespoke (helper: `suite.freshContext(...)` + `ctx.eventsOf<E>(runId)`) |
| 20. real registry path | manual prepare + coexecute | `row20_realRegistryPath { typed -> ... }` (typed-output lambda for Step assertions) |

Suites needing an extra bespoke durable run use:

```kotlin
val ctx = suite.freshContext(eventStore)          // production registry + coordinator + journal
ctx.run(suite.pipeline(suite.canonicalNode()), runId)
ctx.eventsOf<DirDeleted>(runId); ctx.journalRows(runId)
```

## 4. Migration recipe for future Steps (G6)

1. Replace the file body with one `certifyStep(<Step>.definition, <canonicalInput>) { ... }`
   value; pin `envelope`, `output`, `expectEffects`, `expectReplay`, one `capability`
   per declared capability, and one `rejectInput`/`rejectOutput` per old rejection probe.
2. Keep the same `@Test` method names (JUnit XML row identity); generic rows become
   one-line delegations per the mapping table.
3. Copy any Step-specific rows (legacy defaulting, conditional exposure, event payload,
   idempotence payloads) verbatim, swapping the private harness for `suite.freshContext`.
4. Call `row12_missingCapability(cap)` once per declared capability (multi-capability
   Steps like `core.pwd` expand this row into N delegations).
5. Validate: L0 compile → run the class with the XML canary (delete the old
   `TEST-*.xml` first) → diff old/new XML `testcase name` sets (must be identical) →
   counts `tests=N failures=0 errors=0`.

**Line estimate**: a new Step's G6 suite becomes **~110-160 lines** (DSL pins
~15, one-line generic rows ~13-21 depending on capability count, plus any
Step-specific rows) versus the current ~600-700; a no-frills atomic Step lands
near the pilot's 297 lines minus bespoke rows, i.e. typically **< 150 lines**.

## 5. Pilot evidence (core.deleteDir)

| Metric | Before | After |
|---|---|---|
| Suite source lines | 665 | 297 |
| JUnit tests | 22 | 22 |
| Failures / errors | 0 / 0 | 0 / 0 |
| XML testcase names | (baseline) | **identical** (diff empty) |
| XML freshness | canary: XML deleted before run, regenerated (`--rerun-tasks` run, 42/42 executed) | canary: XML deleted before run, regenerated, `timestamp="2026-09-12T22:01:14.618Z"` |

Zero-fabrication preserved: every generic row drives the real seam
(`RegistryExecutionPreparation` → capability admission → handler → durable
journal → events); the harness contains no fake returns and asserts
XML-visible JUnit outcomes only.

## 6. Non-goals

- Migrating the sibling suites (echo/sleep/writeFile/emitEvent/error/isUnix/pwd/
  waitUntil/milestone/sh/uppercase) — mechanical follow-up using the recipe in §4;
  each is an independent commit.
- `testFixtures` module extraction — deferred until a second module needs the
  harness (all current suites live in `pipeline-application`'s test source set).
- Any change to production code, counters, or `LEGACY_PLUGIN_IDS`.

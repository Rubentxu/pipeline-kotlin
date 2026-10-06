# S6 BLOCK A — Receipt: eliminating the compiler-layer violations

**Cycle:** `p-733fb505b5a6bd2d/rp7-sem-s6-plugin-sdk` · **Branch:** `s6-plugin-sdk`
**Scope:** A1 (KSP must not decide semantics by StepKey) · A2 (dead LSP metadata) · A3 (Reactor vs ADR-0104)

---

## A1 — the compiler layer no longer infers Step semantics from a name

### What was there

Three name-shaped authorities, not one:

| Where | Discriminator | Decided |
|---|---|---|
| `StepDescriptorGenerator.kt:62-71` | `when (name)` over `"echo"`/`"sh"`/`"error"`/`"sleep"` | `ExecutionLocation`, `Effect`, `ReplayPolicy` |
| `KnownJenkinsSurfaces.MAP` | `name -> JenkinsSurfaceMeta` | the Jenkins surface triple |
| `StepDescriptorGenerator.kt:146-152` | `startsWith("<name>\|")` on the surface string | location, replay policy, `failureKindBridge` |

Both `when`s carried a justification, and `KnownJenkinsSurfaces` carried the same
one more elaborately: *"KSP 2.3.11 cannot reliably extract enum/array values from
Kotlin 2.4.10 annotation arguments in this configuration"*, with a promised future
— *"when KSP supports enum/array extraction, migrate and delete this map"*.

### The justification was false, and that is the finding

It was cheap to inherit the explanation and remove the code. So the claim was
measured before anything was deleted. A probe inside the processor printed the
runtime type of each annotation argument:

```text
[PROBE] arg=execution rawClass=com.google.devtools.ksp.impl.symbol.kotlin.KSClassDeclarationEnumEntryImpl
        raw=ExecutionLocation.CONTROLLER
[PROBE] arg=replay    rawClass=...KSClassDeclarationEnumEntryImpl raw=ReplayPolicy.MEMOIZED
```

The declared values were present all along. They needed exactly one more case in
the reader (`KSClassDeclaration`, whose `simpleName` is the constant). **The
`when` was not a necessary workaround around a toolchain limit; it was an
avoidable authority handed a plausible excuse.** Its own KDoc said it would be
deleted when the limitation was lifted — and the limitation never existed.

Had the probe not been run, the deletion would have been recorded as "removed a
semantic switch", and the false claim about KSP would have survived in the
receipt as the reason.

### What replaced it

`StepDescriptorGenerator` now reads `id`, `name`, `execution`, `effects`, `replay`
from `@Step` and `step`, `plugin`, `compatibility` from `@JenkinsSurface`. There is
no `else` branch and no default: an unreadable declaration routes to `logger.error`,
which **fails the build**. A genuinely unreadable declaration is a build error, not
a licence to guess from a name and emit a descriptor whose semantics nobody declared.

`KnownJenkinsSurfaces.kt` and its self-test are deleted.

### The mandatory mutation (A1)

A new Step family was added with metadata that contradicts any name-based guess, and
the processor was **not** touched:

```kotlin
@JenkinsSurface(step = "mutate", plugin = "workflow-basic-steps", compatibility = CompatibilityLevel.BEHAVIORAL)
@Step(
    id = "core.mutationprobe",
    name = "mutationprobe",
    execution = ExecutionLocation.AGENT,
    effects = [Effect.WRITES_WORKSPACE, Effect.READ_ONLY],
    replay = ReplayPolicy.RERUN,
)
```

Generated descriptor, from declaration alone:

```text
stepId            = "core.mutationprobe"
executionLocation = ExecutionLocation.AGENT
effects           = listOf(Effect.WRITES_WORKSPACE, Effect.READ_ONLY)
replayPolicy      = ReplayPolicy.RERUN
jenkinsSurface    = "mutate|workflow-basic-steps|F2"
```

All four correct. A name-based `when` would have produced `WORKER` / `READ_ONLY` /
`MEMOIZED` / `""` — every field wrong. The probe Step was then reverted.

## A2 — the dead LSP metadata is gone, not fixed

### Consumer characterization (the four questions, answered)

| Question | Answer |
|---|---|
| Production consumers of `LspMetadataLoader` | **0** — the symbol appears only on its own declaration line |
| Tests proving real consumption | **0** — `LspMetadataJsonSerializationTest` round-tripped `LspMetadata` against itself and never against an emitted artifact |
| Artifact / public contract | `LspMetadata`, `LspMetadataLoader` and `FailureKindBridge` were all in the enforced dump `pipeline-step-sdk/api/api/api.api` |
| Documentation promising the surface | **3 places**: `JENKINS_FAMILIARITY.md:69`, `PRD_V2.md` FR-PLG-003, `ADR-0022` |

`FailureKindBridge` also had **0** consumers — it existed only to populate the
`failureKindBridge` field of the file nobody read.

So the filename bug (`core.echo.json.json`, from a `fileName` that already ended in
`.json` while KSP appends `extensionName` again) was **not** fixed. Fixing it would
have been repairing infrastructure with no consumer. Deleted instead:

- `LspMetadata.kt`
- `LspMetadataLoader.kt`
- `FailureKindBridge.kt`
- `LspMetadataJsonSerializationTest.kt`
- the metadata emission inside `StepDescriptorGenerator`

The same double-extension defect existed on the *other* output too:
`GeneratedStepDescriptors.kt.kt`. That is gone by construction — `fileName` no longer
carries an extension, so KSP appends exactly one.

### The governance question that decided the path

The dump is enforced by `apiCheck`, so deletion is a binary break. It is **not**
governed by the maturity/exception ledger, because `publishedContractModules` in
`v2/build.gradle.kts` lists only `pipeline-domain`, `pipeline-scripting-api`,
`pipeline-events`, `pipeline-output` — `pipeline-step-sdk:api` is not published at
all (no `maven-publish`). Registering an exception naming it would have failed the
fitness that rejects entries for unpublished modules. The removal is therefore
recorded by regenerating the dump in this same commit, which is the normal BCV flow
for a module outside the published set.

The two documentation claims were corrected rather than left standing:
`JENKINS_FAMILIARITY.md` no longer advertises the JSON path, and FR-PLG-003 no longer
claims LSP metadata. `ADR-0022` mentions it only as historical scope of a past
milestone and is left untouched. A stale example in `RecoveredValueSpineFitnessTest`'s
KDoc that named `LspMetadata` was corrected so it does not cite a deleted class.

## The fitness, and the near-miss that almost shipped it

`StepDescriptorGeneratorNoNameSemanticsFitnessTest` bans the shape: a `when` whose
discriminator is a Step identity, a table keyed by a Step name literal, a surface
prefix comparison, or a comparison against a Step name literal.

It is a source scan, and that is stated honestly in the test's KDoc: for the four
pre-existing Steps, "read the declaration" and "hardcode the answer" produce
byte-identical descriptors, so no runtime observation can separate them. The scan
makes the ban permanent; the behavioural mutation above is what makes it necessary.

**The first version of this test was vacuous and was caught before it shipped.**
Its two path filters were chained, which is `AND`: a source had to begin with
`pipeline-step-sdk/processor/src/main/` *and* `pipeline-step-sdk/api/src/main/`.
Nothing could match, so the test was green by construction. It was found by running
the non-vacuity mutation — reintroducing the `when` — and observing the test still
pass. The two filters are now an explicit `roots.any { ... }`.

That is the third time in this block that a plausible-looking search or assertion
returned "nothing" and I nearly recorded an absence as fact (the first two were
single-level globs hiding nested modules). The saving move was the same each time:
make the thing print what it can see instead of concluding from a count.

### Non-vacuity

| State | Result |
|---|---|
| `when (name)` + name-keyed table reintroduced | **FAILED**, naming the file, the Step-identity branch and both table keys |
| Clean generator | **PASSED**, 2 tests, 0 failures |

Comments and KDoc are stripped before scanning. That is load-bearing, not hygiene:
the processor's KDoc explains the removed `when` by name, and without stripping, the
honest explanation would keep the law red forever — so someone would eventually
delete the explanation instead of the code.

## A3 — Reactor reconciled with ADR-0104

§2 of the canonical package lists `reactors: List<ReactorManifestEntry>`. S5.5 is
`DEFERRED` by ADR-0104, which named S6 as where a plugin-contributed reactive
construct belongs.

`ReactorManifestEntry` is **not** created. The contribution families this release
supports are **Step, Directive, Event, Capability** — the four with a real producer
today. A manifest slot with no consumer is the same defect ADR-0104 refused to let
S5.5 commit, reached by a different road.

---

## What this block does not claim

- It does not make the generated `StepDescriptor` list authoritative. Its consumer
  count was zero before this block and is still zero; what changed is that it is now
  *correctly derived* rather than name-inferred. Turning it into a real authority is
  S6 BLOCK F, and only if a consumer appears.
- It does not touch `PluginManifest`, admission, registry freezing or the external
  vertical. Those are BLOCKS B, C, D and G.
- `@Step` and `@JenkinsSurface` remain as the declaration surface. Removing them
  would be a larger break of a different surface and is not justified by this block.

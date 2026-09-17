# LFC-2E3-P / P1 — BINARY PLUGIN SPI COMPATIBILITY

| Field | Value |
| --- | --- |
| Cycle | LFC-2E3-P — PLATFORM HARDENING |
| Slice | P1 — freeze the plugin SPIs and prove old-JAR/new-host compatibility |
| Status | GREEN — 5/5 ABI fitness; guard verified to BITE on the real injection |
| Parent cycle | LFC-2E3-TESTING-REPORTS (E3-A frozen at `8214a058`) |
| Production core changes | ZERO |
| SDK changes | ZERO (SPIs untouched; only frozen + guarded) |
| New invariant | `old plugin ABI regressions = 0` |

## 1. Why this slice exists

During E3-T4 the capability seam was first implemented by adding a **defaulted method** to the
existing `StepDefinitionContributor`. It compiled, and every in-process suite passed. The plugin
test classpath then threw:

```text
java.lang.AbstractMethodError: Receiver class example.uppercase.UppercaseContributor
does not define or inherit an implementation of the resolved method
'abstract java.util.Map capabilities()'
```

Kotlin emits interface members with defaults as **abstract plus a `DefaultImpls` holder**, so a
default value is NOT binary compatibility. Every already-built plugin JAR would have broken at
runtime while every source-level test stayed green. That asymmetry is what P1 closes.

## 2. What landed

```text
AGENTS.md § PLUGIN ABI COMPATIBILITY (MANDATORY)          the law + enforcement
AGENTS.md § Permanent invariants                          + old plugin ABI regressions = 0
AGENTS.md § WORKTREE SAFETY                               precondition before every first write

examples/abi-fixture-plugin/                              a plugin frozen in time
  AbiFixtureContributor : StepDefinitionContributor        ONLY the original SPI shape
  abi.fixture.echo                                         trivial typed Step, no capabilities
  META-INF/services/…StepDefinitionContributor             ServiceLoader registration

v2/build.gradle.kts                                       :buildAbiFixturePlugin (Lane R, deliberate)
v2/pipeline-application/src/test/resources/abi/
  abi-fixture-plugin-1.0.0.jar                            THE frozen artifact (20 KB)
  plugin-spi-abi.txt                                      golden ABI manifest
v2/pipeline-application/src/test/.../PluginBinaryCompatibilityFitnessTest.kt   5 rows
```

## 3. Guard 1 — golden ABI manifest

`plugin-spi-abi.txt` freezes `interface` + sorted `member(params): return` lines for every public
SPI a plugin implements or is handed:

```text
StepDefinitionContributor      definitions(): Iterable          getId(): String
StepCapabilityContributor      capabilities(): Map              getId(): String
StepDefinition                 getContract(): StepContract      getHandler(): StepHandler
StepCodec                      decode-j8uo1iw(String): Object   encode-9TXO0do(Object): String   schema(): String
StepHandler                    execute(Object,StepHandlerContext,Continuation): Object
StepRegistry                   contains-bQvmloc(String): boolean  definition-bQvmloc(String): StepDefinition
                               keys(): Set                         register(StepDefinition): void
```

Signatures include **erased parameter and return types**, and therefore the Kotlin value-class
mangling suffixes (`encode-9TXO0do`, `contains-bQvmloc`). That mangling IS part of the ABI: changing
a value class's underlying representation changes the JVM method name, which is exactly the kind of
change a prebuilt plugin cannot survive.

Regeneration is deliberate and never silent:

```bash
./gradlew -p v2 :pipeline-application:test \
    --tests '*PluginBinaryCompatibilityFitnessTest*' -Ppipeline.abi.writeGolden=true
# writes the manifest AND FAILS, so the change must be reviewed
```

A note on the mechanism: a Gradle `-D` does **not** reach the forked test JVM, so the flag is
forwarded explicitly as a system property from a project property. (Discovered by the first
regeneration attempt not writing anything.)

## 4. Guard 2 — the preserved plugin JAR

`abi-fixture-plugin-1.0.0.jar` implements **only** the original SPI shape and no capabilities — the
shape of every plugin built before the capability SPI existed. It is loaded in an isolated
`URLClassLoader` (deliberately NOT on the test classpath, which would pollute every other suite's
registry counts) and must:

```text
load      the class really comes from the JAR (codeSource asserted)
register  discovered by the SAME production ExternalStepPluginDiscovery adapter
admit     RegistryExecutionPreparation -> Ready with the canonical bridge alone
EXECUTE   a pipeline run through CanonicalDurableRunCoordinator -> RunOutcome.Success
```

The execution assertion references the frozen plugin **only by `PluginStepId` and encoded JSON** —
zero fixture types at compile time — so it exercises the same erased path production uses, not a
convenience shortcut.

Its digest is pinned (`62daf4d6c8980cd2be11b4ae188437eb10c751519fee59d5cbbe37b3f1d5acf4`) so the
artifact cannot be silently swapped for a freshly compiled one — which would defeat the entire
guard.

### Why the JAR is committed rather than rebuilt

A plugin recompiled against the current SDK always *matches* the current SDK, so it can never
observe this regression class. Only a physically preserved artifact can. This is a deliberate,
documented exception to the repository's usual "no committed jars" stance, and the two are not in
conflict:

- Lane R forbids committed **SDK** jars, because a stale SDK must fail the build rather than
  silently satisfy it;
- an **ABI fixture** jar must be frozen, because a live one would silently satisfy the test.

`tasks.test` has **zero** dependency on `:buildAbiFixturePlugin`, asserted in this receipt.

## 5. The guard was verified to BITE

A guard that cannot fail is worthless, so the exact regression was injected and the guard observed:

```text
INJECTED: fun capabilities(): Map<StepCapability, Any> = emptyMap()
          added to the existing StepDefinitionContributor
```

```text
> EXIT=1 (non-zero = guard BITES)
PluginBinaryCompatibilityFitnessTest > the SPI plugins implement keeps exactly its original member set FAILED
PluginBinaryCompatibilityFitnessTest > public plugin SPI member sets are frozen … FAILED
```

Diagnostics produced:

```text
Public plugin SPI ABI drift detected. Changing an existing SPI breaks every already-built plugin
JAR (source compatibility is NOT ABI compatibility). Add a NEW additive SPI/interface instead.

StepDefinitionContributor must keep exactly {id, definitions}. Capability contribution lives in
the separate StepCapabilityContributor SPI.
  ==> expected: <[getId, definitions]> but was: <[getId, capabilities, definitions]>
```

The injection was then reverted and `git diff` confirmed the SPI restored **exactly** (zero diff),
with the suite back to green.

### Honest boundary of each guard

| Guard | Catches | Does NOT catch |
| --- | --- | --- |
| Golden manifest | ANY member change on a frozen SPI: add, remove, rename, re-type, mangling shift | runtime behaviour of an SPI not in the frozen list |
| Preserved JAR | load/register/admit/execute breakage | a new member the host happens not to call yet |

They are complementary, and neither is claimed to be complete on its own. The manifest is the
primary gate because it fires on the *intent* before any runtime is involved.

## 6. Test evidence

`PluginBinaryCompatibilityFitnessTest` — 5 tests, 0 failures, 0 errors:

| Row | Assertion |
| --- | --- |
| `public plugin SPI member sets are frozen` | live reflection == golden manifest |
| `the SPI plugins implement keeps exactly its original member set` | `StepDefinitionContributor` == `{getId, definitions}`; capability SPI is not a subtype |
| `the preserved plugin JAR is byte-identical to the frozen artifact` | SHA-256 pin |
| `old plugin JAR loads registers and is admitted by the current host` | codeSource is the JAR; old shape (no capability SPI); discovered; registered; admitted Ready |
| `old plugin JAR EXECUTES end-to-end through the canonical durable spine` | `RunOutcome.Success` via `CanonicalDurableRunCoordinator` |

Full plugin-suite regression after this slice: **213 tests, 0 failures, 0 errors** (208 from E3-A
plus these 5). The fixture JAR is not on the test classpath, so no registry-count assertion moved.

## 7. Counter rollup (P1)

| Indicator | Before P1 | After P1 |
| --- | --- | --- |
| `old plugin ABI regressions` | not measured | **0** (mechanically guarded) |
| Frozen public plugin SPIs | 0 | 6 |
| Preserved ABI fixture JARs | 0 | 1 (digest-pinned) |
| Plugin-suite tests | 208 | 213 |
| Production core changes | — | 0 |
| SDK changes | — | 0 |

## 8. Files added / changed

```text
AGENTS.md                                                              3 sections
examples/abi-fixture-plugin/{build,settings}.gradle.kts                new
examples/abi-fixture-plugin/src/main/kotlin/abi/fixture/AbiFixtureContributor.kt   new
examples/abi-fixture-plugin/src/main/resources/META-INF/services/…     new
v2/build.gradle.kts                                                    :buildAbiFixturePlugin
v2/pipeline-application/build.gradle.kts                               forward the golden flag
v2/pipeline-application/src/test/resources/abi/abi-fixture-plugin-1.0.0.jar        frozen artifact
v2/pipeline-application/src/test/resources/abi/plugin-spi-abi.txt      golden manifest
v2/pipeline-application/src/test/.../PluginBinaryCompatibilityFitnessTest.kt        new, 5 rows
```

## 9. Next slice

**P2 — Typed Step-Output Value Piping.** RED first: demonstrate end-to-end that `core.junit`'s typed
output cannot currently feed another Step. The mechanism must be generic (not designed around
`TestReport`), with durable producer/output identity, canonical runtime resolution, replay reuse of
committed output, fail-closed type mismatch, no ambient lookup, no `Map<String, Any>` escape hatch,
and no Step-specific coordinator routing.

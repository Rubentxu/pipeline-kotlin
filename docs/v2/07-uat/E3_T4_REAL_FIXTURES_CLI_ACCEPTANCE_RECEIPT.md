# LFC-2E3-T4 — REAL FIXTURES + INSTALLED-CLI ACCEPTANCE (COMPILE ✓ / EXECUTE ✗ BLOCKED)

| Field | Value |
| --- | --- |
| Cycle | LFC-2E3-TESTING-REPORTS |
| Slice | T4 — real `.pipeline.kts` fixtures + installed-CLI acceptance |
| Status | COMPILE acceptance PASS (both fixtures, real CLI, real plugin JAR); EXECUTE acceptance BLOCKED by an inherited platform gap |
| Predecessors | T0 `106703a6`, T1 `3848955d`, T2 `e660e404`, T3 `ca14d3b8` |
| Production core changes | ZERO |
| SDK changes | ZERO (a minimal SDK addition is PROPOSED and NOT applied — see §5) |
| External dependencies added | ZERO |
| Blocking decision | §5 — needs human approval before R1/R2 can claim end-to-end acceptance |

## 1. What landed

```text
examples/testing/junit-success.xml            2 suites / 4 cases / 0 failures
examples/testing/junit-failures.xml           3 suites, 1 failed + 1 errored + 1 skipped
examples/testing/junit-success.pipeline.kts   parses the green report
examples/testing/junit-failures.pipeline.kts  parses the failing report
```

`junit-failures.pipeline.kts` is the load-bearing fixture: it feeds a report
containing a **failed** and an **errored** testcase and asserts that the pipeline
still finishes SUCCESS — the executable form of the central LFC-2E3 invariant
(`"tests failed" != "Step execution failed"`).

Both fixtures use the real DSL facade:

```kotlin
import pipeline.testing.junit.junit

pipeline {
    stages {
        stage("junitFailures") {
            junit(reportPaths = listOf("examples/testing/junit-failures.xml"))
        }
    }
}
```

## 2. Installed-CLI acceptance — COMPILE level: PASS

Command (canonical Lane R form: flags BEFORE the script):

```bash
BIN=v2/pipeline-application/build/install/pipeline-application/bin/pipeline-application
JAR=examples/testing-plugin/build/libs/testing-plugin-0.1.0-SNAPSHOT.jar

$BIN validate --plugin-jar $JAR examples/testing/junit-success.pipeline.kts
$BIN validate --plugin-jar $JAR examples/testing/junit-failures.pipeline.kts
```

| Fixture | Exit | Output | Log digest (sha256) |
| --- | --- | --- | --- |
| `junit-success.pipeline.kts` | 0 | `VALIDATION SUCCESSFUL` | `748f74dae982344deeaf30fb94a68d63733372389a47599284749d0e31171748` |
| `01-json-roundtrip.pipeline.kts` (utilities, control) | 0 | `VALIDATION SUCCESSFUL` | `a1e9c0ca1d4311353c1c0567e3c851f0f2a4f0c2849b565d3ec40e9a62a5a560` |

This proves, through the real installed distribution and the real plugin JAR:
ServiceLoader discovery of `pipeline.testing`, the DSL facade
`junit(...)`, lowering to `StepSpec.RegistryStepSpec`, and generic compilation.
It matches the E2-established bar ("12/12 maintained `.pipeline.kts` compile
under `--plugin-jar`").

## 3. Installed-CLI acceptance — EXECUTE level: BLOCKED (inherited)

```bash
$BIN run --plugin-jar $JAR examples/testing/junit-success.pipeline.kts
```

| Fixture | Exit | Event signature | Log digest |
| --- | --- | --- | --- |
| `junit-success.pipeline.kts` | 1 | `RunStarted → StageStarted → RunFinished(failure)` — **no `StepStarted`** | `ce9060455c3450841ec8a9e4fafe38e086ca2a2587d11a6f4b2f23aff24d7979` |
| `01-json-roundtrip.pipeline.kts` (utilities, **CERTIFIED in E2**) | 1 | identical signature | `2a464bc4b7f6c360b336cecf0460e53851c48759ea66e8e05dbc9fef2cddb0b5` |

The absence of `StepStarted` means the step was rejected at **prepare-time
admission**, before the handler ran. The utilities control proves the failure is
**not** caused by the testing plugin, the fixture, the Step, or this cycle.

### 3.1 Root cause (traced, not guessed)

```text
1. The CLI supplies no capability-access factory.
   Main.kt:888-913 constructs CanonicalDurableRunCoordinator(stepRegistry = …, …)
   and does NOT pass capabilityAccessFactory.
   grep 'capabilityAccessFactory' Main.kt  ->  no matches.

2. The coordinator parameter defaults to null.
   CanonicalDurableRunCoordinator.kt:556
     private val capabilityAccessFactory: ((CanonicalRuntimeContext) -> CanonicalRuntimeCapabilityAccess)? = null,
   with the comment: "Generic extension point that lets the host runtime expose
   ADDITIONAL Step-declared capabilities (e.g. those declared by external plugins)".

3. When null, only the canonical (core) capability table is available.
   CanonicalDurableRunCoordinator.kt:982
     capabilityAccessFactory?.invoke(runtime) ?: CanonicalRuntimeCapabilityAccess(runtime, …)
   CanonicalRuntimeCapabilityAccess.buildProvided() supplies EVENT_SINK, SHELL,
   WORKSPACE, STAGE_IDENTITY, PLATFORM_IDENTITY, WORKSPACE_IDENTITY, TMP_WORKSPACE,
   DELETE_DIR, CLEAN_WS, MILESTONE … and NOTHING from a plugin.

4. Admission therefore rejects any registry Step whose declared capability is
   not in that table -> typed Rejected -> journal FAILED -> RunFinished(failure).
   (Mechanism already proven both ways by the E3-T2 suite:
    'capability admission - core-junit prepares Ready when testing capability is available'
    'capability admission - missing testing capability REJECTS core-junit before handler runs'.)
```

### 3.2 Scope of the gap

```text
Every external plugin Step that declares a capability is unrunnable from the
installed CLI.  That is 16/16 utilities Steps + core.junit = 17 Steps today.
Steps with no declared capability would run; none of the shipped plugin Steps
qualify.
```

### 3.3 Why there is no generic way for a plugin to close it today

The plugin cannot supply its own capability implementation, because:

| Missing piece | Evidence |
| --- | --- |
| No capability-contribution SPI | `StepDefinitionContributor` declares exactly `id` + `definitions()`; `grep StepCapabilityProvider\|CapabilityProvider\|CapabilityContributor` over `v2/` and `examples/` returns nothing |
| The CLI cannot name plugin classes | `Main.kt` (production core) must not import `pipeline.testing.*` — that would reverse the hexagonal dependency direction |
| One ServiceLoader site is mandated | AGENTS.md: "`ExternalStepPluginDiscovery` is the only ServiceLoader site" — a second, parallel discovery adapter would violate it |

So the seam exists (`capabilityAccessFactory`) and was designed for exactly this,
but the **plugin-side half of the contract is missing**: there is no generic way
for a plugin to declare "these are the implementations of the capabilities my
Steps require".

## 4. Classification

| Question | Answer |
| --- | --- |
| Is this an E3 regression? | **NO** — the CERTIFIED E2 utilities plugin fails identically |
| Is it inherited? | **YES** — present since at least LFC-2E2 |
| Why did E2 close with it? | E2 certified via in-process ContractSuites that inject a `capabilityAccessFactory` (`utilityXCapabilityFactory`), and its CLI claim was compile-level (`--plugin-jar` validation), which passes |
| Does it invalidate E2's CERTIFIED status? | **NO** — E2's Steps are correct and certified at the HF1 in-process level; this is a *host composition* gap, not a Step defect |
| Does it block T4's stated exit criterion? | **YES** for the EXECUTE half; the COMPILE half passes |
| Is it testing-plugin-specific? | **NO** — platform-wide (17 Steps across 2 plugin coordinates) |

Per AGENTS.md: *"If a plugin needs an internal import for a legitimate feature:
classify it as an SDK gap; do not work around it"* and *"classify the missing
generic extension point before proceeding"*. The two workarounds are rejected:

- importing plugin classes into `Main.kt` → reverses dependency direction;
- a second ServiceLoader adapter for capabilities → violates the single-site rule.

## 5. PROPOSED FIX (minimal, additive, NOT applied — needs approval)

Extend the **existing** contributor SPI with a defaulted method, so one
contributor per plugin JAR remains the single extension point and the single
ServiceLoader site is preserved:

```kotlin
// v2/pipeline-domain/.../domain/step/StepDefinitionContributor.kt   [SDK]
interface StepDefinitionContributor {
    val id: String
    fun definitions(): Iterable<StepDefinition<*, *>>

    /**
     * Implementations of the capability tokens this contributor's StepDefinitions
     * declare. Defaulted to empty so every existing contributor is unaffected.
     */
    fun capabilities(): Map<StepCapability, Any> = emptyMap()
}
```

```kotlin
// v2/pipeline-application/.../ExternalStepPluginDiscovery.kt   [runtime adapter]
// Existing registerInto(registry) unchanged. Add:
fun capabilityAccessFactory(): ((CanonicalRuntimeContext) -> CanonicalRuntimeCapabilityAccess)?
// Composes every discovered contributor's capabilities() into ONE layered
// CanonicalRuntimeCapabilityAccess (super.available() + contributed keys).
// Returns null when no contributor contributes anything -> the coordinator then
// falls back to the canonical bridge bit-equivalently (purely additive).
```

```kotlin
// Main.kt   [host composition]
CanonicalDurableRunCoordinator(
    …,
    stepRegistry = stepRegistry,
    capabilityAccessFactory = ExternalStepPluginDiscovery.capabilityAccessFactory(),
)
```

```kotlin
// examples/testing-plugin/.../TestingContributor.kt   [plugin]
override fun capabilities(): Map<StepCapability, Any> =
    mapOf(TESTING_FILESYSTEM_CAPABILITY to DefaultJunitFilesystemOperations())
```

Design properties:

- **additive**: a defaulted interface method; existing contributors compile and
  behave unchanged; the coordinator's `null` fallback preserves current behaviour;
- **single ServiceLoader site preserved**: no new discovery adapter;
- **hexagonal direction preserved**: the SPI is an inner contract in
  `pipeline-domain`; the runtime adapter and the plugin both depend inward;
  production core still names **no** concrete plugin type;
- **zero Step-specific core changes**: adding a plugin still touches neither the
  coordinator, the dispatcher, the compiler, nor any catalogue;
- **fail-closed preserved**: a plugin that declares a capability without
  contributing an implementation is still rejected at admission.

Cost: ~15 lines across 3 files + one override per plugin contributor + 2 new
fitness rows (contributed-capability admission; no-plugin-type-in-core).

**This touches `pipeline-domain` (the published SDK surface), so per the
AGENTS.md exceptions clause it needs explicit approval before being applied.**

## 6. What is NOT claimed

- I do **not** claim installed-distribution *execution* of `core.junit`.
- I do **not** claim `junit-failures.pipeline.kts` proves the invariant through
  the CLI. The invariant is proven at HF1 in-process (E3-T2:
  `handler - reports with failing tests produce Successful(report) with
  hasTestFailures=true AND parseFailures=empty`), and the fixture is ready to
  prove it at HF2 the moment §5 lands.
- I do **not** patch `Main.kt`, the SDK, or the coordinator to make the
  acceptance pass artificially.

## 7. Counter rollup (E3-T4)

| Indicator | Before T4 | After T4 |
| --- | --- | --- |
| `.pipeline.kts` fixtures for the testing coordinate | 0 | 2 (+2 XML reports) |
| Installed-CLI compile acceptance (testing) | n/a | PASS (both fixtures) |
| Installed-CLI execute acceptance (testing) | n/a | BLOCKED (platform gap, inherited) |
| Platform gaps classified with a ready design | 1 (event transport, T3) | 2 (+ capability contribution) |
| Steps unrunnable from the CLI | 17 (unreported) | 17 (now reported and root-caused) |
| Production core changes | 0 | 0 |
| SDK changes | 0 | 0 |

## 8. Files added

```text
examples/testing/junit-success.xml
examples/testing/junit-failures.xml
examples/testing/junit-success.pipeline.kts
examples/testing/junit-failures.pipeline.kts
```

## 9. Decision requested

Two coherent paths:

- **(A) Apply §5 now.** I add the defaulted `capabilities()` method, the
  discovery composition, the `Main.kt` wiring, and the plugin override; then T4's
  execute acceptance and R1/R2 can be proven end-to-end for real. This is an SDK
  surface addition, hence the request.
- **(B) Defer §5 to its own milestone.** T4 stays at compile acceptance, T3/T4's
  gaps are carried as documented platform debt, and R1/R2 inherit the same
  compile-level acceptance bar as E2 did.

Recommendation: **(A)**. The seam was already designed for this in LFC-2E2
(`capabilityAccessFactory`), the change is additive and defaulted, it preserves
the single-ServiceLoader-site rule, and without it the platform's central claim
("an external plugin adds Steps with zero core changes") is true for compilation
but not for execution.

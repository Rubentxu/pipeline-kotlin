# LFC-2E3-T4 — REAL FIXTURES + INSTALLED-CLI ACCEPTANCE (COMPILE ✓ / EXECUTE ✓)

| Field | Value |
| --- | --- |
| Cycle | LFC-2E3-TESTING-REPORTS |
| Slice | T4 — real `.pipeline.kts` fixtures + installed-CLI acceptance + capability-contribution seam |
| Status | **COMPILE PASS and EXECUTE PASS** through the real installed distribution and the real plugin JAR |
| Predecessors | T0 `106703a6`, T1 `3848955d`, T2 `e660e404`, T3 `ca14d3b8` |
| Plugin coordinate | `pipeline.testing@0.1.0-SNAPSHOT` |
| Production core changes | Main.kt host composition only (2 discovery sites + 2 call sites); NO step-specific change |
| SDK changes | +1 new SPI (`StepCapabilityContributor`); existing SPIs untouched (binary compatible) |
| External dependencies added | ZERO |

## 1. What landed

**Fixtures**

```text
examples/testing/junit-success.xml            2 suites / 4 cases / 0 failures
examples/testing/junit-failures.xml           3 suites, 1 failed + 1 errored + 1 skipped
examples/testing/junit-success.pipeline.kts   parses the green report
examples/testing/junit-failures.pipeline.kts  parses the failing report
```

`junit-failures.pipeline.kts` is the load-bearing fixture: it feeds a report containing a
**failed** and an **errored** testcase and asserts the pipeline still finishes SUCCESS — the
executable form of the central LFC-2E3 invariant.

**Seam that made execution possible**

```text
v2/pipeline-domain/.../domain/step/StepCapabilityContributor.kt     NEW public SPI
v2/pipeline-application/.../ExternalStepPluginDiscovery.kt          collectContributedCapabilities()
                                                                    + capabilityAccessFactory(map)
v2/pipeline-application/.../Main.kt                                 collect inside the plugin
                                                                    classloader window; pass the
                                                                    composed factory into the run path
examples/testing-plugin/.../TestingCapabilityContributor.kt         NEW plugin-side contribution
examples/utilities-plugin/.../UtilitiesCapabilityContributor.kt     NEW plugin-side contribution
+ both plugins' META-INF/services/<SPI> registrations
```

## 2. Installed-CLI acceptance — COMPILE: PASS

```bash
BIN=v2/pipeline-application/build/install/pipeline-application/bin/pipeline-application
JAR=examples/testing-plugin/build/libs/testing-plugin-0.1.0-SNAPSHOT.jar

$BIN validate --plugin-jar $JAR examples/testing/junit-success.pipeline.kts    # exit 0, VALIDATION SUCCESSFUL
$BIN validate --plugin-jar $JAR examples/testing/junit-failures.pipeline.kts   # exit 0, VALIDATION SUCCESSFUL
```

Proves ServiceLoader discovery of `pipeline.testing`, the `junit(...)` DSL facade, lowering to
`StepSpec.RegistryStepSpec`, and generic compilation.

## 3. Installed-CLI acceptance — EXECUTE: PASS

| Fixture | Exit | Steps started | Step failures | Outcome | Log sha256 |
| --- | --- | --- | --- | --- | --- |
| `junit-success.pipeline.kts` | 0 | 1 | 0 | **SUCCESS** | `d4e08713f22c0d90882ed35bca178e915eb223e249c811dea8c8494d5be2f084` |
| `junit-failures.pipeline.kts` | 0 | 1 | 0 | **SUCCESS** | `b4d45725f22891679417ee843578c2d4f57b6e8d1d4655fce4227670a2188eeb` |
| `01-json-roundtrip.pipeline.kts` (utilities control) | 0 | 3 | 0 | **SUCCESS** | `073f8d9140698849980ef6ea755bb24339f7f7340caae797f207da87703f6b85` |

Full event sequence for the success fixture:

```text
CompilationStarted → CompilationFinished → RunStarted → StageStarted
  → StepStarted → StepFinished → StageFinished → RunFinished(SUCCESS)
```

### 3.1 The invariant, now proven end-to-end

`junit-failures.pipeline.kts` parses a report with **1 failed + 1 errored testcase** and the run
finishes **SUCCESS with 0 step failures**. `"tests failed" != "Step execution failed"` is no
longer only an in-process claim; it holds through the real CLI.

A separate forced-fresh run (`--rerun --db … --control-root …`) executed **3/3** utilities Steps,
confirming genuine fresh execution rather than journal reuse.

## 4. The gap that was found and closed

### 4.1 Root cause

`Main.kt` constructed the coordinator without `capabilityAccessFactory`, so the prepare-time
capability set was the canonical core table only. Every plugin Step that declared a capability was
therefore **rejected at admission and never executed**. The coordinator's own parameter comment
already named the intended fix:

```kotlin
// CanonicalDurableRunCoordinator.kt:551-556
// Generic extension point that lets the host runtime expose ADDITIONAL Step-declared
// capabilities (e.g. those declared by external plugins) …
private val capabilityAccessFactory: ((CanonicalRuntimeContext) -> CanonicalRuntimeCapabilityAccess)? = null,
```

The plugin-side half was missing entirely: `StepDefinitionContributor` declared only
`id` + `definitions()`, and no capability-contribution SPI existed.

**Inherited, not an E3 regression.** The CERTIFIED E2 `pipeline.utilities.json` control failed with
an identical signature before the fix (exit 1, `StageStarted → RunFinished(failure)`, no
`StepStarted`). 17 Steps across 2 coordinates were unrunnable from the CLI.

### 4.2 A second, subtler defect — caught by the new fitness test

The first implementation added a **defaulted method** to the existing
`StepDefinitionContributor`. It compiled and the in-process suites passed, but the fitness test
immediately failed with:

```text
java.lang.AbstractMethodError: Receiver class example.uppercase.UppercaseContributor
does not define or inherit an implementation of the resolved method
'abstract java.util.Map capabilities()'
```

Kotlin emits interface members with defaults as **abstract plus a `DefaultImpls` holder**, so that
addition was source-compatible but **not binary-compatible**: every already-built plugin JAR would
break at runtime. The design was corrected to a **separate SPI**, which keeps all existing plugin
JARs loadable and each class single-purpose. `ExternalStepCapabilityContributionTest` now guards
this mechanically:

```text
StepDefinitionContributor must keep exactly {id, definitions}
```

### 4.3 Applied design

```text
pipeline-domain (SDK)
  StepCapabilityContributor { id, capabilities(): Map<StepCapability, Any> }   NEW, additive

pipeline-application (runtime adapter, still the ONLY ServiceLoader site)
  collectContributedCapabilities()        classloader-SENSITIVE: must run inside the
                                          plugin-classloader window; fails closed on a
                                          duplicate capability owner
  capabilityAccessFactory(contributed)    PURE composition; null when nothing contributed,
                                          so the coordinator falls back to the canonical
                                          bridge bit-equivalently

Main.kt (host composition)
  collects capabilities INSIDE the same TCCL window that already wrapped registerInto,
  then passes the composed factory through to runCanonicalPipeline

plugin JAR
  implements StepCapabilityContributor and lists it in META-INF/services
```

Why the classloader split matters: contributors live on the **plugin** classloader, and the CLI
sets it as the thread context classloader only for the duration of `registerInto`. A `ServiceLoader`
lookup performed outside that window silently returns nothing (no error), which is exactly how the
first attempt failed. Discovery (impure, window-bound) is therefore separated from composition
(pure) so the sensitive step is explicit and cannot hide inside a lazily-invoked lambda.

Properties preserved:

- **hexagonal direction**: the SPI is an inner contract; the runtime adapter and the plugin both
  depend inward; production core names **no** concrete plugin type;
- **single ServiceLoader site**: `ExternalStepPluginDiscovery` loads both SPIs; no second adapter;
- **fail-closed**: duplicate capability ownership throws; a declared-but-uncontributed capability
  is still rejected at admission;
- **zero step-specific core changes**: adding a plugin still touches neither the coordinator, the
  dispatcher, the compiler, nor any catalogue;
- **binary compatibility**: no member was added to any existing SPI.

## 5. Test evidence

### v2 `:pipeline-application:test` — 191 tests across 12 suites, 0 failures, 0 errors

| Suite | Tests | Result |
| --- | --- | --- |
| `UtilitiesJsonStepContractSuiteTest` | 26 | 0 failures |
| `Lfc2E2ExpansionGateFitnessTest` | 22 | 0 failures |
| `TestingJunitStepContractSuiteTest` | 20 | 0 failures |
| `UtilitiesTarStepContractSuiteTest` | 17 | 0 failures |
| `UtilitiesYamlStepContractSuiteTest` | 17 | 0 failures |
| `UtilitiesArchiveStepContractSuiteTest` | 15 | 0 failures |
| `UtilitiesChecksumsStepContractSuiteTest` | 15 | 0 failures |
| `UppercaseStepContractSuiteTest` | 14 | 0 failures |
| `UtilitiesFilesystemStepContractSuiteTest` | 12 | 0 failures |
| `UtilitiesPropertiesStepContractSuiteTest` | 12 | 0 failures |
| `Lfc2E2PrepFitnessTest` | 10 | 0 failures |
| `ExternalStepCapabilityContributionTest` | 6 | 0 failures (NEW) |

The new suite pins: contributors really do contribute; composition is pure and returns `null` for an
empty contribution; the composed access exposes canonical **plus** contributed capabilities and
returns the contributed implementation verbatim; duplicate ownership fails closed; **every
capability an EXTERNAL Step declares is canonical-or-contributed**; and the
`StepDefinitionContributor` member set is frozen at `{id, definitions}`.

That fifth row is the strongest: it is the mechanical statement of the defect class, so a future
plugin Step declaring an unowned capability fails the suite instead of silently becoming
unrunnable from the CLI.

Scope note: the assertion is deliberately limited to EXTERNAL Steps. `core.milestone`,
`core.cleanWs`, `core.deleteDir` and `core.archiveArtifacts` declare capabilities that the minimal
canonical bridge used in the test does not expose (they are supplied by the durable runtime with
its milestone state store). That is a separate, already-documented concern — the `core.milestone`
capability gap recorded in the LFC-2E2 receipts — and folding it into this assertion would have
hidden it.

### testing-plugin — 34 tests, 0 failures

| Suite | Tests |
| --- | --- |
| `TestingEventsContractTest` | 16 |
| `JunitXmlAdapterContractTest` | 10 |
| `TestReportDomainContractTest` | 8 |

## 6. Pre-existing failures (NOT regressions) — base-vs-head evidence

Two `:pipeline-application:test` failures reproduce on cycle base `440fc7ca`
(`UatLocal005CheckoutGitTest > SC-007`, `UatLocal007SandboxProfileTest > SB-S-010`; base log
sha256 `8c383cf953b00d342530d179559dafac0ac007d5f9b29eafe77d7d2fc718f443`). Classified
pre-existing and out of LFC-2E3 scope, consistent with `UatLocal008` / `UatLocal009`.

## 7. Counter rollup (E3-T4)

| Indicator | Before T4 | After T4 |
| --- | --- | --- |
| Testing `.pipeline.kts` fixtures | 0 | 2 (+2 XML reports) |
| Installed-CLI compile acceptance (testing) | n/a | **PASS** (both) |
| Installed-CLI execute acceptance (testing) | n/a | **PASS** (both) |
| Plugin Steps unrunnable from the CLI | 17 | **0** |
| Public SDK SPIs | 1 (`StepDefinitionContributor`) | 2 (+ `StepCapabilityContributor`) |
| Existing SPI members changed | 0 | **0** (binary compatible) |
| Change to `StepDefinitionContributor` | — | none |
| ServiceLoader sites for Steps | 1 | 1 |
| Production core step-specific changes | 0 | 0 |
| Capability tokens | — | unchanged (no new token; T4 supplies existing ones) |

## 8. Files added / changed

```text
examples/testing/junit-success.xml                                        (new)
examples/testing/junit-failures.xml                                       (new)
examples/testing/junit-success.pipeline.kts                               (new)
examples/testing/junit-failures.pipeline.kts                              (new)
examples/testing-plugin/.../pipeline/testing/TestingCapabilityContributor.kt        (new)
examples/testing-plugin/src/main/resources/META-INF/services/<capability SPI>       (new)
examples/utilities-plugin/.../pipeline/utilities/UtilitiesCapabilityContributor.kt  (new)
examples/utilities-plugin/src/main/resources/META-INF/services/<capability SPI>     (new)
v2/pipeline-domain/.../domain/step/StepCapabilityContributor.kt                     (new SDK SPI)
v2/pipeline-application/.../ExternalStepPluginDiscovery.kt                          (discovery + composition)
v2/pipeline-application/.../Main.kt                                                 (host composition)
v2/pipeline-application/src/test/.../ExternalStepCapabilityContributionTest.kt      (new, 6 rows)
```

## 9. Known limitations / next slice

- The capability SPI supplies plugin capabilities to the **canonical durable** run path. The
  scripted frontend path (`runScriptedFrontend`) was deliberately left unchanged; if it needs plugin
  capabilities, that is a separate composition point with its own evidence.
- `core.milestone` / `core.cleanWs` / `core.deleteDir` / `core.archiveArtifacts` capability
  availability remains as previously documented. T4 neither widened nor narrowed it, and the new
  fitness row makes the external/internal boundary explicit so the two concerns cannot be conflated.
- E3-T3's event **transport** gap remains classified (see `E3_T3_TESTING_EVENTS_RECEIPT.md` §5.3).
  It is a distinct seam from capability contribution and was not addressed here.
- R1 (`publishHTML`) is next; it inherits a working CLI execution path for plugin Steps.

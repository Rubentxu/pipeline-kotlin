# LFC-2E3-P / P2 — TYPED STEP-OUTPUT VALUE PIPING

| Field | Value |
| --- | --- |
| Cycle | LFC-2E3-P — PLATFORM HARDENING |
| Slice | P2 — generic typed Step-output value piping |
| Status | GREEN **and hardened/frozen** — RED (4 rows) pass unchanged; 13 contract rows incl. identity + schema-drift + non-lookup laws; 239 tests, 0 failures |
| Predecessors | P1 (`AGENTS.md` ABI law + guards) |
| Production core changes | compiler lowering + coordinator publication (generic; NO concrete StepKey named) |
| SDK changes | ADDITIVE: new `StepOutputPiping.kt`; `RegistryStepSpec`/`OpaqueStepNode` gained OPTIONAL fields; `registryStep` untouched; new `registryStepPublishing` |
| `old plugin ABI regressions` | **0** — the frozen fixture JAR rebuilt byte-identically (see §6) |

## 1. The gap, restated

```kotlin
val report = junit(<report-glob>)   // typed output, computed at RUN time
publishReport(report = report)      // the consumer wants THAT value
```

Before P2 this was inexpressible. A Step's typed output was committed durably and observable from
outside the run, but no later Step could receive it. The only workarounds were all forbidden or
impossible:

- duplicate the data out of band — impossible when the value is computed at run time;
- reach the journal/coordinator from a handler — forbidden (LB-02 / G3-A4.2);
- fabricate the value during DSL construction — forbidden ("no fake runtime values").

The limitation was recorded in passing in `PipelineDsl.kt`:

```text
NOTE: No return value — consumers use `sh(returnStdout=true)` for runtime values.
Documented limitation per D2; addressed in ML-R8 follow-up.
```

It is also why E3-R2's linkage could not hand `publishHTML` the parsed `TestReport`.

## 2. RED, then GREEN

`StepOutputPipingRedTest` shipped first as four executable absence characterizations, each naming a
specific missing seam. All four failed for the expected reason:

```text
RED 1 - RegistryStepSpec cannot carry a durable output identity
RED 2 - no canonical StepOutputResolver port exists
RED 3 - no typed StepOutputRef a DSL author can bind
RED 4 - no capability token for output resolution
```

After implementation the SAME four rows pass **unchanged** — no assertion was relaxed, and a fifth
characterization row (an encoded output is already durable, it is simply not bindable) passed
before and after.

## 3. Design

```text
producer Step
  -> typed output
  -> durable output identity (declared name + type tag)
  -> StepOutputRef          (a DECLARATIVE reference, never a value)
  -> consumer Step input binding
  -> canonical resolution through a DECLARED capability
```

**Domain (`pipeline-domain`)**

```text
StepOutputDeclaration(name, typeTag)     what a producer publishes
StepOutputRef(name, typeTag)             what a consumer binds; accepts(declaration)
StepOutputResolutionError (sealed, 5)    UnknownOutput | NotYetProduced |
                                         ProducerProducedNoOutput | TypeMismatch | Unreadable
StepOutputResolutionException            typed carrier
StepOutputResolver                       resolveEncoded(ref) | isResolvable(ref)
STEP_OUTPUT_RESOLVER_CAPABILITY          SDK-owned token: StepCapability("step.output.resolver")
```

The port exposes **encoded** output. Returning a typed value would require an erased cast at the
seam — exactly the type-erasing escape hatch the strict-typing rules forbid. The plugin decodes with
its own codec.

**DSL (`pipeline-scripting-api`)** — additive only:

```text
registryStep(...)                     UNCHANGED (the function plugins call)
registryStepPublishing(..., outputName, outputTypeTag): StepOutputRef    NEW
RegistryStepSpec                      + outputName, outputTypeTag (optional, appended)
```

**Runtime (`pipeline-application`)**

```text
OpaqueStepNode                        + outputName, outputTypeTag (optional, appended)
DslCompiledPipelineCompiler           passes the declaration through verbatim
CanonicalDurableRunCoordinator        publishes IDENTITY per dispatched Step
JournalStepOutputResolver             reads the JOURNAL, the single authority
CanonicalRuntimeContext               + stepOutputResolver (optional)
CanonicalRuntimeCapabilityAccess      exposes the port under the SDK token when present
```

## 4. Laws, and how each is enforced

| Law | Enforcement |
| --- | --- |
| no fake runtime values | `StepOutputRef` carries `(name, typeTag)` only; construction cannot fabricate a value |
| no `Map<String, Any>` escape hatch | the seam transports `EncodedStepValue`; the plugin decodes with its own codec |
| no ambient lookup | resolution goes through a DECLARED capability; the handler never sees the journal |
| durable producer identity | `name -> producing operationId`, recorded by the coordinator as it dispatches |
| replay reuses committed output | resolution reads the journal row; nothing is recomputed |
| consumer cannot observe before producer | the journal has no output yet → `NotYetProduced` |
| type mismatch fail-closed | producer tag must equal the consumer's expected tag → `TypeMismatch` |
| no Step-specific coordinator routing | the coordinator reads only `OpaqueStepNode.outputName` |

Two design decisions worth stating:

**Ordering is enforced by the authority, not by bookkeeping.** The name is registered *before*
execution on purpose. A consumer that runs early still resolves against the journal, finds no
committed output, and fails closed. A counter-based ordering check would have been a second
authority for something the journal already knows.

**Duplicate output names are a contract violation, rejected before any effect** — never
last-wins. The check runs before structural preparation, so a duplicate never executes.

## 5. Test evidence

| Suite | Tests | Result |
| --- | --- | --- |
| `StepOutputPipingRedTest` | 5 | 0 failures (4 RED rows now pass unchanged) |
| `StepOutputValuePipingTest` | 9 | 0 failures (NEW) |
| `PluginBinaryCompatibilityFitnessTest` | 6 | 0 failures (P1 + §7 hardening) |

Plugin-suite regression total: **235 tests, 0 failures, 0 errors.**

The mechanism is proven **generically**, with a test-local producer/consumer pair whose producer
computes its output at run time — no product Step is involved, per the cycle directive. Asserted:
the consumer receives the producer's actual runtime value; ordering, type mismatch, unknown name and
duplicate name all fail closed; admission rejects a consumer without the capability and admits it
with it; the coordinator names no concrete StepKey.

## 6. Independent confirmation that P2 was additive

`AGENTS.md § PLUGIN ABI COMPATIBILITY` requires an SPI addition to be additive. Two independent
checks agreed:

1. the P1 golden manifest did **not** change — `registryStep` is a `StageScope` member whose JVM
   name (`registryStep-TmLEHys`) encodes the value-class parameter ABI, and it is untouched;
2. the frozen ABI fixture JAR **rebuilt byte-identically**:

```text
committed:   62daf4d6c8980cd2be11b4ae188437eb10c751519fee59d5cbbe37b3f1d5acf4
regenerated: 62daf4d6c8980cd2be11b4ae188437eb10c751519fee59d5cbbe37b3f1d5acf4
```

Had P2 changed the plugin-facing surface, that digest would have moved.

## 7. P1 hardening added in this slice

Auditing `registryStep` revealed that P1's frozen-SPI list guarded what plugins **implement** but
not what plugins **call**. A new row closes that:

```text
the DSL function plugins actually CALL keeps its exact JVM signature
```

It freezes `registryStep-TmLEHys` (+ `$default`) and `registryStepPublishing-0QqE4n0` (+ `$default`)
on `StageScope`. This is the check that would have caught the original E3-T4 mistake directly, and it
is why P2 introduced a NEW function rather than a new parameter.

## 8. Producer-side wiring in the testing plugin

`core.junit` now publishes its output when asked:

```kotlin
fun StageScope.junitPublishing(reportPaths: List<String>, outputName: String): StepOutputRef
```

with `JunitStepDefinition.OUTPUT_TYPE_TAG = "pipeline.testing.junit.JunitStepOutput"` as the
producer-declared contract. This is a plugin-owned facade, so the addition is additive for the SDK.

## 9. Honest limitations

- **No product consumer yet.** The mechanism is complete and proven generically, and `core.junit`
  can publish, but no shipped product Step consumes a piped output. The natural first consumer is a
  quality gate (`fail-on-test-failures`), which the cycle deferred precisely because it is *policy*:
  it must live outside the parser and decide what failing tests mean. It now has a mechanism to be
  built on. Building it was not required to prove piping and was not invented here.
- **Type agreement is checked at resolution time, not statically.** Kotlin's DSL cannot prove two
  Step facade signatures agree. The `typeTag` contract makes the check explicit and fail-closed;
  a fully static version would need a type-level encoding the directive did not ask for.
- **`RegistryStepSpec` and `OpaqueStepNode` gained optional fields.** They are SDK-constructed
  structural IR, not interfaces plugins implement or constructors plugins call, so this is additive
  for the plugin surface — confirmed independently by §6.
- **Scripted-frontend path** (`runScriptedFrontend`) was not extended; the piping seam covers the
  canonical durable path, which is where Step composition happens.

## 10. Counter rollup (P2)

| Indicator | Before P2 | After P2 |
| --- | --- | --- |
| Step-output piping | impossible | **typed, durable, fail-closed** |
| `old plugin ABI regressions` | 0 | **0** |
| Production core step-specific routing | 0 | **0** |
| Plugin-suite tests | 213 | 235 |
| SDK SPIs added | 0 | 0 (additive types/functions, no new SPI interface) |

## 11. Next slice

**P3 — Local Event Transport.** Publish the E3 domain events through a canonical LOCAL boundary
(NO network, NO controller, NO EVT-4), keeping `journal/control state = authority` and
`events = observability`, with the E3 testing events as the first real consumer.

---

# 12. HARDENING BEFORE FREEZE (P2.1)

Review found `StepOutputRef` too weak: a bare textual `typeTag` is not an identity, and a reference
could in principle be used as an arbitrary journal lookup key. Three additions closed that before
P2 was frozen.

## 12.1 Identity is now three parts, and two of them are derived

```text
StepOutputRef
  = producerKey   (which Step FAMILY produced it)      consumer-declared, checked
  + name          (logical durable output name)        consumer-declared, checked
  + typeTag       (stable type identity)               consumer-declared, checked

StepOutputPublication (recorded at publication)
  = declaration            (producerKey + name + typeTag)
  + producerOperationId    (the producer INVOCATION that committed the value)
  + codecIdentity          DERIVED, never hand-written
```

`codecIdentity = <output codec class name> "|" <codec-declared schema>`, via `stepCodecIdentity()`.
It is derived from the producer's **registered `StepDefinition`**, so it cannot drift from the code
it describes — the failure mode a hand-written tag invites. A declaration whose producer has no
registered definition can no longer be published at all (rejected before any effect).

The publication map is now keyed by `(producerKey, name)`, so two families cannot address each
other's outputs by sharing a name.

## 12.2 Schema evolution fails closed

The codec identity is recorded at publication and **re-derived at resolution**. A mismatch —
typically a plugin upgrade between a run and its replay — throws
`StepOutputResolutionError.SchemaDrift` instead of decoding old data with a new codec. A producer
that is no longer registered is also a drift, not a silent success.

```text
published under "abi.PublishedCodecV1|{}"  vs  current "abi.CurrentCodecV2|{}"
  -> SchemaDrift, refuse
```

This is the guard against the expensive-to-reverse failure the review named: an old replay
deserialising silently under a new codec as if the two contracts were equivalent.

## 12.3 `StepOutputRef` is not an arbitrary journal lookup key

Resolution consults **only** the published set. A Step that runs, journals a committed output and
does *not* declare an output name is unreachable by reference, even though its row exists.

The dedicated row proves it end to end: a producer runs successfully and commits a journal row, then
a reference to it is refused with `UnknownOutput`. This is the sharpest of the three: it is what
stops the piping seam from degenerating into a general-purpose read of durable state.

## 12.4 Dashboard invariants added

`AGENTS.md` now carries two more invariants alongside the original six, plus two new law sections
(`STEP OUTPUT BINDING IDENTITY`, `LOCAL EVENT TRANSPORT` for P3):

```text
untyped output bindings         = 0
event authority violations      = 0
```

## 12.5 A test that was compiled but NEVER RAN

Building these rows exposed a real trap. One hardening law was written as:

```kotlin
fun `...`() = runBlocking { ... }   // lambda's last expression is assertInstanceOf, returning T
```

`assertInstanceOf` returns its cast value, so the Kotlin function returned a non-`Unit` type and
**JUnit 5 silently did not discover the test**. The suite reported green with the law unexecuted.

The XML canary caught it (`13 @Test` in the source, `12 <testcase>` in the XML), the body was
converted to a block, and the law now runs. Recorded as `AGENTS.md` testing rule 27a:

> A green suite is not evidence that a specific test executed.

Without that check, this receipt would have claimed a law it never enforced.

## 12.6 Evidence

| Suite | Tests | Result |
| --- | --- | --- |
| `StepOutputValuePipingTest` | 13 | 0 failures (discovery verified by name in the XML) |
| `StepOutputPipingRedTest` | 5 | 0 failures |
| `PluginBinaryCompatibilityFitnessTest` | 6 | 0 failures |

Plugin-suite regression: **239 tests, 0 failures, 0 errors.** All eight invariants at 0.

P2 is now frozen.

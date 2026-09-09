# LB-02 / EP-C — `example.uppercase` external plugin Step certification

## Scope

First EXTERNAL plugin Step certified through the open-world seam (LB-02 final gate):
`example.uppercase`, contributed by `examples/example-uppercase-plugin` via
`StepDefinitionContributor` (ServiceLoader), hosted by `--plugin-jar` with ONE
classpath feeding compile + runtime discovery.

## Evidence

- Suite: `UppercaseStepContractSuiteTest` — 14 tests, 14/0/0
  (`:pipeline-application:test --tests 'UppercaseStepContractSuiteTest*'`,
  JUnit XML `TEST-dev.rubentxu.pipeline.v2.application.UppercaseStepContractSuiteTest.xml`).
- Affected set re-run: `EP_F26_GenericProductionPathProofTest` 4/0/0 (18/0/0 total).
- Coverage matrix:

| Row | Coverage | Status |
| --- | --- | --- |
| identity | Suite (`KEY == example.uppercase`, duplicate registration fails closed) | green |
| contract completeness | Suite (descriptor, READ_ONLY, MEMOIZED, empty capabilities) | green |
| input codec | Suite (encode/decode round-trip, JSON-object envelope) | green |
| codec fail-closed decode | Suite (foreign payload kind rejected) | green |
| output codec | Suite (typed `UppercaseOutput` symmetric round-trip) | green |
| handler semantics | Suite (`hello` → `HELLO` through the typed handler seam) | green |
| registry resolution | Suite via `ExternalStepPluginDiscovery` (ServiceLoader, no manual registration) | green |
| capability admission | Suite (`RegistryExecutionPreparation` Ready with declared-empty) | green |
| missing capability | Suite (Rejected, fail-closed) | green |
| fresh durable | Suite (one terminal SUCCEEDED operation row) | green |
| replay | Suite (reuse of a SUCCEEDED run) | green |
| divergence | Suite (changed input → typed Failure/Unstable) | green |
| observability | Suite (StepStarted + StepFinished) | green |
| real pipeline scenario | Suite (DSL `registryStep` → `DslCompiledPipelineCompiler` → `CanonicalDurableRunCoordinator` → Success) | green |
| installed distribution scenario | delegated to EP-6 real-distribution proof (WITH plugin SUCCESS / WITHOUT plugin compile-exit-1) | green (delegated) |
| architecture fitness | delegated to `EP_F26_GenericProductionPathProofTest` + fitness rules (zero plugin-key knowledge in production) | green (delegated) |

## Zero-production-change invariant

All production surfaces were SHA-256-frozen in
`LB02_EXTERNAL_PLUGIN_INFRA_BASELINE.md` BEFORE the plugin JAR entered the proof
loop. The plugin contribution, discovery, and certification required ZERO
production semantic changes (post-proof diff documented in the baseline file).

## Certification result

```text
example.uppercase = CERTIFIED
```

This is the open-world proof certificate: an external plugin Step reaches
CERTIFIED through the same registry-driven seam, the same canonical spine, and
the same durable/observability contracts as core Steps — with no privileged
core path and zero production changes.

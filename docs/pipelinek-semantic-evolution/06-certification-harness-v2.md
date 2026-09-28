# Certification & Harness v2

## 1. Problem

The previous certification pattern could prove only that unsupported/non-canonical Step forms fail closed. It could not detect supported-looking DSL APIs that compile into valid canonical Steps while losing semantics.

Certification must therefore prove **semantic conservation**, not just canonicality.

## 2. Certification object

Certification binds to:

```text
source SHA
+ distribution SHA256
+ plugin lock digest
+ DSL API version
+ engine structural-shape version
+ event schema set digest
```

No PASS transfers across a byte-changing candidate.

## 3. Certification profiles

### HF0 — pure contracts

- codecs;
- ADT decisions;
- predicate evaluator;
- body/directive policy resolution;
- event codec.

### HF1 — in-process canonical runtime

- real registry;
- capability admission;
- typed outcome;
- event timeline.

### HF2 — installed distribution

- exact ZIP;
- real CLI;
- external plugin jar;
- executable `.pipeline.kts`.

### HF3 — restart/resume

- kill/restart;
- journal reuse/rerun;
- call-site identity;
- no duplicated forbidden effects/events.

### HF4 — rootless sandbox

- filesystem/process/isolation contracts.

### HF5 — service sandbox

- network/service plugin integration.

### HF6 — online smoke

- explicitly opt-in external service checks.

## 4. Mandatory Semantic Witness matrix

Every supported DSL surface must have:

| Witness | Meaning |
|---|---|
| Carrier | exact typed carrier is present |
| Discriminant | changing semantic input changes behavior |
| Negative | unsupported/incoherent form fails pre-effect |
| Outcome | typed outcome is correct |
| Event | required events, forbidden events, order |
| Replay | restart/reuse semantics |
| Installed | same behavior from packaged distribution |

A test named `desugars`, `maps`, `preserves`, `routes`, `when`, `post`, `retry`, etc. that only proves compilation is insufficient evidence.

## 5. Directive certification

For every directive:

- policy shape admitted;
- exact lifecycle phase;
- discrimination test;
- no body effect when guard skips;
- event ordering;
- replay/state behavior;
- external directive zero-core-change test.

Example `when(false)`:

```text
StageGuardEvaluationStarted
StageGuardEvaluated(NO_MATCH)
StageSkipped
```

Forbidden:

- StepStarted for body Steps;
- body effect events;
- success pretending body ran.

## 6. Block Step certification

Required:

- body cardinality;
- context propagation;
- failure fold;
- interruption semantics;
- durable body identity;
- nested composition;
- plugin body Step through generic `BodyInvoker`.

## 7. Event certification

For each Step/directive event family:

- one emission authority;
- stable key/schema;
- no secret leakage;
- deterministic causation/correlation;
- expected order constraints;
- duplicate/replay law;
- external observer does not affect outcome;
- reactor idempotency if applicable.

## 8. Plugin certification bundle

A third-party library must ship or expose a certification descriptor:

```text
plugin manifest
contract fixtures
semantic scenarios
expected event constraints
minimum HF levels
```

Harness runs the plugin against the installed PipelineK candidate, not against plugin-specific internal mocks only.

## 9. Adversarial checks

The harness must mutate or generate cases for:

- unknown Step/directive/event keys;
- schema mismatch;
- missing capability;
- undeclared capability use;
- body policy mismatch;
- directive policy mismatch;
- duplicate registration;
- event key collision;
- incompatible plugin API range;
- fake runtime value;
- silent no-op options;
- Step builder that emits twice;
- external plugin requiring a core edit.

## 10. Harness verdict

```kotlin
sealed interface CertificationVerdict {
    data class Pass(val evidence: EvidenceBundle) : CertificationVerdict
    data class Fail(val defects: NonEmptyList<CertificationDefect>) : CertificationVerdict
    data class Blocked(val dependencies: NonEmptyList<ExternalDependency>) : CertificationVerdict
}
```

No free-text-only verdict.

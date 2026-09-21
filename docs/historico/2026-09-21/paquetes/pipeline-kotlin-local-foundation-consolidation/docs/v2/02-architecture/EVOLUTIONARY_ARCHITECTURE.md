# Evolutionary architecture operating model

## Philosophy

Architecture emerges through validated increments, not simultaneous speculative frameworks. Each milestone must leave a smaller ambiguity surface than it found.

## Strangler rule

For every legacy concept being replaced:

1. define the target contract;
2. add characterization tests around required existing behavior;
3. route one vertical slice through the target;
4. migrate all callers;
5. add a fitness rule that prevents new callers to the legacy path;
6. delete the legacy path;
7. close the milestone.

Do not maintain permanent adapters between two canonical models.

## Vertical slices

Prefer a real sample pipeline through the whole stack over horizontal framework work. Recommended reference pipeline:

```text
checkout -> withCredentials -> withEnv -> sh(build) -> parallel(test/lint)
         -> junit -> archiveArtifacts -> post(always)
```

Every milestone should keep or improve this pipeline.

## Spikes

Spikes are mandatory before:

- removing/replacing source rewriting around shell `$VAR` handling;
- choosing the strong local sandbox backend;
- finalizing cross-platform Jlink assembly if Kotlin compiler/scripting modules expose jlink friction;
- adopting a persistent graph engine beyond a simple local projection.

## Fitness before features

A milestone is not closed if architecture fitness tests are red even when behavior UAT passes.

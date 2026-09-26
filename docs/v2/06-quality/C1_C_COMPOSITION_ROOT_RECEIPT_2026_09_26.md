# C1-C — CompositionRoot Extraction — Receipt (2026-09-26)

## Scope

C1-C of the isolated H1/H2/H3 partition plan in
`docs/v2/06-quality/C1_ISOLATION_2026_09_26.md`.

The characterization confirmed that `Main.kt` owned a private
`runCanonicalPipeline` composition root with exactly two production call sites.
No `CompositionRoot.kt` existed before this slice.

## Change

- Added `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CompositionRoot.kt`.
- Moved the canonical durable coordinator assembly without changing the 22
  arguments, defaults, journal wiring, registry wiring, retry/wait journals,
  artifact index, workspace semantics, or sandbox resolution.
- Changed the extracted function visibility from file-private to `internal`
  solely because Kotlin file-private top-level functions cannot be called from
  another source file. No public API or CLI contract was added.
- Left both `Main.kt` call sites unchanged.
- Removed only the original function body from `Main.kt`.
- Did not modify `parseCliArgs`, `main`, `runScriptedFrontend`, plugin
  classpath helpers, or any durable coordinator logic.

`Main.kt` decreased from 1,148 to 1,087 lines in the source snapshot. The new
composition root is 96 lines including focused documentation.

## Verification

1. `cd v2 && timeout 600 ./gradlew :pipeline-application:compileKotlin --console=plain`
   - exit code: 0
   - observed: `BUILD SUCCESSFUL in 7s`
   - log SHA-256: `4b176a24ed3c265d5c8b64c6219596c6c9c058b5881e4a73c8a626623262bde7`
2. `cd v2 && timeout 600 ./gradlew :pipeline-application:test --rerun-tasks --tests 'dev.rubentxu.pipeline.v2.application.CanonicalInMemoryCliTest' --console=plain`
   - exit code: 0
   - JUnit XML: 1 test, 0 skipped, 0 failures, 0 errors
   - XML SHA-256: `c7874031bfdbeb0f1cb471ac49e7826d2e67c0f8d60f77d3c244aabc7d7dbc22`
   - log SHA-256: `dd92987b390f21ae18747f25f064c81ad6258a7a46bd5fd297170f9ef8e5df14`
3. `cd v2 && timeout 600 ./gradlew :pipeline-application:test --rerun-tasks --tests 'dev.rubentxu.pipeline.v2.application.scripted.R4BProductionWiringFitnessTest' --console=plain`
   - exit code: 0
   - JUnit XML: 2 tests, 0 skipped, 0 failures, 0 errors
   - XML SHA-256: `a7fe4bfe521b78dd669ade079293bfb5ad1d364b1b3c9fc2444b91928a2a1cd7`
   - log SHA-256: `517489ee75f7c96c4124e10755d3629bd9cfa57b21e1d477e206f90579ab77f8`
4. Structural checks:
   - `Main.kt` contains two executable `runCanonicalPipeline(...)` call sites and
     no function definition.
   - `CompositionRoot.kt` contains the sole function definition.
   - `git diff --check` passed after removing the extra EOF blank line.

The full repository `check` gate was not rerun. This slice only moves one
application-level composition function and does not change shared domain,
event, build, serialization, or public SDK contracts.

## Reference and quality closure

- Reference implementation consulted: none applicable. This is an internal
  composition-root partition, not a Jenkins behavior change.
- Behaviour adopted: preserve the canonical durable execution path exactly while
  making the CLI entrypoint thinner.
- Intentional deviations: `private` became `internal` because cross-file Kotlin
  access requires it; the symbol remains outside the public API surface.
- Security implications reviewed: no new capability, process, filesystem, or
  credential authority was introduced. Existing wiring was moved verbatim.
- Tests demonstrating the contract:
  `CanonicalInMemoryCliTest`, `R4BProductionWiringFitnessTest`.

## Remaining C1 work

C1-C is implemented locally. C1-D, the ADR-0078 `PipelineDsl` partition,
remains a separate WorkItem. The generic SDDK release tooling remains blocked
by the previously recorded repository-incompatible Cargo lockstep precondition.

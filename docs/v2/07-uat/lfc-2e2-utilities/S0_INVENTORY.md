# LFC-2E2 — Utilities OFFICIAL_PLUGIN (S0 inventory)

**Date**: 2026-09-19
**Status**: S0 INVENTORY (no production code written yet)
**Slice author**: pipeline-go (long-running mandate)
**Source of truth**: `docs/v2/05-roadmap/LFC2_STEP_ECOSYSTEM_EXPANSION.md`
(§LFC-2E2), `docs/v2/01-product/STEP_ECOSYSTEM_MATRIX.md` (Pipeline utility rows)
**Plugin seam authority**: `v2/pipeline-step-sdk/scm-git` (F5.1 CERTIFIED)
**Plugin mechanism**: ServiceLoader → `StepDefinitionContributor` (proven by scm-git + junit + example-uppercase)

---

## 1. Strategic placement

Post-`LPR-GATE-1` v0.39.0 the ecosystem enters dogfooding + Step Ecosystem Expansion
(LFC-2E). The first new family after the `LFC-2E0` catalog certification is
**LFC-2E2 utilities OFFICIAL_PLUGIN** — the highest-leverage P1 surface because:

- it covers the basic typed-value I/O that almost every real CI pipeline needs;
- it does NOT need a credential, network, or process capability;
- it can be fully proven without external dependencies (json + sha256 alone);
- it requires **zero** production core edits — the registry seam already accepts
  new families through `StepDefinitionContributor` (ServiceLoader-discovered).

Reuse the proven scm-git build-time provenance pattern
(`compute<Module>Digest` → `META-INF/<module>-release.properties`) so the new
plugin's `PluginManifest` carries a real SHA-256 of the JAR contents, never a
hand-typed value.

---

## 2. Scope for first slice (S0 → G8)

Pick the three smallest-yet-coherent Steps that cover the **registry + capability +
codec + durable + replay + contract suite + installed-distribution** chain
end-to-end:

```text
core-utils.readJson   read a file → typed JsonElement   (Effect.READS_WORKSPACE; ReplayPolicy.MEMOIZED)
core-utils.writeJson  write a typed JsonElement → file (Effect.WRITES_WORKSPACE; ReplayPolicy.NEVER)
core-utils.sha256     compute SHA-256 of a file         (Effect.READS_WORKSPACE; ReplayPolicy.MEMOIZED)
```

Why this scope:

- `readJson` proves I/O + typed value extraction (no shell).
- `writeJson` proves durable side-effect + path-relative-to-workspace
  resolution + capability admission (writes).
- `sha256` proves non-trivial deterministic computation over file content and
  covers the "typed value to caller" pattern independent of JSON parsing.

The other rows (`writeYaml`, `toml`, `zip`, `findFiles`, …) become **second
slice** once the seam is proven green end-to-end.

---

## 3. Module layout

```text
v2/pipeline-step-sdk/utilities/
  build.gradle.kts                             # mirrors scm-git build (Kotlin JVM 21, kotlinx-serialization, sha256 digest)
  src/main/kotlin/dev/rubentxu/pipeline/v2/sdk/utilities/
    step/
      CoreUtilsReadJsonKey.kt                  # PluginStepId("core-utils.readJson")
      CoreUtilsWriteJsonKey.kt                 # PluginStepId("core-utils.writeJson")
      CoreUtilsSha256Key.kt                    # PluginStepId("core-utils.sha256")
      CoreUtilsReadJsonCodec.kt                # StepCodec<ReadJsonInput> + StepCodec<ReadJsonOutput>
      CoreUtilsWriteJsonCodec.kt
      CoreUtilsSha256Codec.kt
      CoreUtilsReadJsonStepDefinition.kt       # StepDefinition<ReadJsonInput, ReadJsonOutput>
      CoreUtilsWriteJsonStepDefinition.kt
      CoreUtilsSha256StepDefinition.kt
      CoreUtilsStepDefinitionContributor.kt    # registers all three; builds StepProviderMetadata
      CoreUtilsDsl.kt                          # ergonomic Kotlin extensions (lowercase readJson/writeJson/sha256)
    domain/
      ReadJsonInput.kt / ReadJsonOutput.kt     # typed value classes, no Any?/Map smuggling
      WriteJsonInput.kt / WriteJsonOutput.kt
      Sha256Input.kt / Sha256Output.kt
  src/main/resources/META-INF/services/
    dev.rubentxu.pipeline.v2.domain.step.StepDefinitionContributor
  src/test/kotlin/dev/rubentxu/pipeline/v2/sdk/utilities/
    CoreUtilsReadJsonStepDefinitionTest.kt     # HF0..HF2 contract suite
    CoreUtilsWriteJsonStepDefinitionTest.kt
    CoreUtilsSha256StepDefinitionTest.kt
    CoreUtilsStepContractSuiteTest.kt          # cross-family contract checks
```

Plus registration in `v2/settings.gradle.kts`:

```text
":pipeline-step-sdk:utilities",
```

---

## 4. Capabilities required (declared == used)

- `core-utils.readJson`  → `WORKSPACE_IDENTITY_CAPABILITY` (resolve the input path against the workspace root).
- `core-utils.writeJson` → `WORKSPACE_IDENTITY_CAPABILITY`.
- `core-utils.sha256`     → `WORKSPACE_IDENTITY_CAPABILITY`.

No `CREDENTIAL_USE`, `NETWORK`, `PROCESS_EXECUTION`, or `SHELL_OPERATIONS_CAPABILITY`. All three are deterministic file operations.

---

## 5. Effects / replay / recovery

| Step               | effects                        | replayPolicy | recoveryPolicy |
|---|---|---|---|
| `core-utils.readJson` | `READ_ONLY`                  | `MEMOIZED`   | `None`         |
| `core-utils.writeJson`| `WRITES_WORKSPACE`           | `NEVER` (write side-effect; replay must NOT silently re-write) | `None` |
| `core-utils.sha256`   | `READ_ONLY`                  | `MEMOIZED`   | `None`         |

`ReplayPolicy.NEVER` for `writeJson` matches the F5.1/SCM lesson (E-EM-11 NEVER-1): a write must NOT silently re-execute on replay. If a previous run recorded a successful write, replay aborts the re-execution with a typed failure rather than duplicating the effect.

---

## 6. Zero-core-change contract

The slice MUST land with the following counts == 0 against `pipeline-application` (production source):

```text
coordinator modifications           = 0
durable modifications              = 0
compiler concrete-Step cases       = 0
core metadata rows                 = 0
legacy catalogue entries           = 0
dispatcher cases                   = 0
canonical-namespace StepDefinition = 0
```

The new module:

- declares its own `PluginManifest` with `Delivery.OFFICIAL_PLUGIN`, families
  `{ UTILITIES }`, real build-time digest + version;
- ships `META-INF/services/dev.rubentxu.pipeline.v2.domain.step.StepDefinitionContributor`
  with the contributor class name (one line);
- is picked up by the existing
  `ExternalStepPluginDiscovery.registerInto(composedStepRegistry)` call in `Main.kt`;
- the existing canonical executor path (`CanonicalDurableRunCoordinator`) admits
  it through `StepRegistry.register(...)` exactly the same way it admits
  `scm-git.checkout` and `junit.results`.

The Step DSL façade lives entirely inside the new module. The compiler does NOT
need to know the new StepKey exists — `registryStep(stepKey = ..., encodedInput = ...)`
is the only lowering primitive.

---

## 7. Test plan

### Unit tests (HF0 / HF1)

For each Step:

- identity (Key value stable);
- contract completeness (`key`, `descriptor`, `inputCodec`, `outputCodec`,
  `requiredCapabilities`, `effects`, `replayPolicy`, `recoveryPolicy` all non-null);
- input codec round-trip preserves every field (encoded == encode(decode(encode)));
- output codec round-trip preserves the typed Output;
- canonical envelope serializes cleanly to JSON;
- registry resolution (`StepRegistry.findByKey(...)`) returns the StepDefinition;
- capability admission fails closed when `WORKSPACE_IDENTITY_CAPABILITY` is missing;
- fresh / replay / divergence / typed-failure / observability matrix.

### Integration tests (HF2 / installed-distribution)

- real `.pipeline.kts` that calls `readJson(...)` + `writeJson(...)` + `sha256(...)`
  under `:pipeline-application:installDist`;
- pipelinek CLI executes the script with exit 0;
- the produced artifact JSON is byte-identical to the input JSON (after formatting
  normalization);
- the recorded SHA-256 matches `sha256sum` of the produced file.

### Cross-cutting

- `StepContractSuite` (existing harness) extended with utilities rows.
- No batch regressions in LPR-GATE-1 corpus (`fixtures 01..23`).

---

## 8. Source-of-truth references used in this inventory

| Topic | Reference |
|---|---|
| Plugin manifest / contributor pattern | `v2/pipeline-step-sdk/scm-git/src/main/kotlin/.../ScmGitStepDefinitionContributor.kt` |
| Step contract shape | `v2/pipeline-step-sdk/scm-git/src/main/kotlin/.../GitCheckoutStepDefinition.kt` |
| Codec pattern (kotlinx.serialization) | `v2/pipeline-step-sdk/scm-git/src/main/kotlin/.../GitCheckoutCodec.kt` |
| DSL façade pattern | `v2/pipeline-step-sdk/scm-git/src/main/kotlin/.../ScmGitDsl.kt` |
| Build-time digest | `v2/pipeline-step-sdk/scm-git/build.gradle.kts` |
| Discovery | `v2/pipeline-application/src/main/kotlin/.../ExternalStepPluginDiscovery.kt` |
| Step composition authority | `v2/pipeline-application/.../CoreStepRegistryFactory.kt` |
| Replay decision authority | `v2/pipeline-step-sdk/runtime/.../EffectReplayPolicy.kt` |
| Public plugin extension contract | `AGENTS.md` "External plugin golden path" § |

---

## 9. Open questions

None at S0. All numbers above are stated as target contract; the next phase
(S1 registry seam proof, S2 corpus migration, …) MUST measure every line above
empirically and reconcile deviations before G8 CERTIFIED is recorded.


# CORE Step admission record — universal-core freeze (LFC-2E1)

**Cycle:** LFC-2E1 (universal-core freeze)
**Date:** 2026-09-17
**Status:** **ACTIVE — frozen core surface**

---

## Purpose

Per the LFC-2E1 universal-core freeze directive:

> new CORE Step → requires explicit admission record
> plugin Step → cannot require coordinator switch
> no new:
>   CanonicalXxxNodeDispatcher
>   LEGACY_PLUGIN_IDS entry
>   plugin-name routing

This file is the **canonical admission record** for any Step classified as `CORE`
in the certification matrix (`docs/v2/status/step-certification.yaml`). It is the
authoritative answer to "is `core.X` allowed in the CORE namespace, and if so,
why?"

The frozen surface is intentionally small. Steps are admitted only when they
satisfy the criteria below.

---

## Admission criteria

A new Step may be admitted to the CORE namespace ONLY when ALL of the following
hold:

1. **Universal primitive**: the Step is universally needed for any CI/CD
   pipeline (atomic primitives: emit, error, sleep; or filesystem mutations that
   every CI must perform).
2. **Zero-domain-knowledge**: the Step does NOT require knowledge of an external
   domain (no JSON/YAML, no Git, no JUnit, no HTTP, no Docker, no SCM, no
   credentials management — those are OFFICIAL_PLUGIN or external candidates).
3. **No production code change to coordinator**: the Step routes through the
   registry seam (`StepRegistry` → `StepDefinition` → `StepHandler`) and requires
   no switch in `CanonicalDurableRunCoordinator` or `CanonicalNodeDispatcher`.
4. **Capability declaration**: the Step's `StepContract.requiredCapabilities`
   is declared and matches the capabilities it actually uses.
5. **G0..G8 burn-down complete**: the Step has a G8 final certification receipt
   (`docs/v2/07-uat/..._G8_FINAL_CERTIFICATION_RECEIPT.md`) and passes
   `Lfc2ZeroLegacyResidualFitnessTest` plus `Lfc2E0GlobalClosureFitnessTest`.

A Step that fails any criterion MUST be classified as OFFICIAL_PLUGIN (separate
JAR, ServiceLoader discovery, zero production changes to core) or rejected.

---

## Frozen CORE surface (12 keys)

The following StepKeys are admitted to the CORE namespace as of
`docs/v2/status/step-certification.yaml` (post-LFC-2E0, 2026-09-17). Any new
CORE key MUST be added here with an explicit reason; absence here + a `core.*`
declaration in code triggers a `Lfc2UniversalCoreFreezeFitnessTest` failure.

| Step | Category | Admitted reason | Receipt |
|---|---|---|---|
| `core.echo` | atomic primitive | Universal log output; every pipeline logs | [G8](CORE_ECHO_CERTIFICATION.md) |
| `core.sh` | process primitive | Universal shell execution; the only way to run a process | [G8](LB02_S6_BURN_DOWN_AND_CERTIFICATION.md) |
| `core.error` | control signal | Typed failure signal; every pipeline may abort | [G8](S2_A1_CORE_ERROR_G8_FINAL_CERTIFICATION_RECEIPT.md) |
| `core.sleep` | atomic primitive | Universal time delay (no domain knowledge required) | [G8](S2_A2_CORE_SLEEP_G8_FINAL_CERTIFICATION_RECEIPT.md) |
| `core.file.writeFile` | filesystem | Universal workspace write; every pipeline writes files | [G8](S2_A3_CORE_WRITEFILE_G8_FINAL_CERTIFICATION_RECEIPT.md) |
| `core.emit.event` | observability | Universal event emission; every pipeline observability | [G8](S2_A4_CORE_EMITEVENT_G8_FINAL_CERTIFICATION_RECEIPT.md) |
| `core.isUnix` | platform identity | Universal OS detection; no domain knowledge | [G8](S2_A5_CORE_ISUNIX_G8_FINAL_CERTIFICATION_RECEIPT.md) |
| `core.deleteDir` | filesystem | Universal workspace cleanup; every pipeline cleans | [G8](S2_A7_CORE_DELETEDIR_G8_CERTIFICATION_RECEIPT.md) |
| `core.milestone` | control signal | Universal execution marker (NOTIFICATION, not domain) | [G8](S2_A9_CORE_MILESTONE_G8_CERTIFICATION_RECEIPT.md) |
| `core.cleanWs` | filesystem | Workspace hygiene; admitted as OFFICIAL_PLUGIN_CANDIDATE (per matrix) but lives in `core.cleanWs` for now | [G8](S2_A10_CORE_CLEANWS_G8_CERTIFICATION_RECEIPT.md) |
| `core.archiveArtifacts` | artifact emission | Universal artifact retention; every pipeline emits | [G8](S2_B10_ARCHIVEARTIFACTS_G8_CERTIFICATION_RECEIPT.md) |
| `core.waitUntil` | orchestration | Universal block-step polling (RepeatUntil machinery) — admitted to CORE because it's an atomic polling primitive, but production routing goes through `BodyExecutionPolicy.RepeatUntil` (orchestration path) not registry. Documented as `execution: ORCHESTRATION` in YAML. | [G8](S2_A8_CORE_WAITUNTIL_WU_G5B_LEGACY_REMOVED_RECEIPT.md) |

---

## STOPPED_G7 (registry-routed, but G7 installed-acceptance blocked)

These keys are admitted to the registry namespace (registry-routed, not legacy),
but their `CERTIFIED` state is BLOCKED at G7 per ADR-0074 (STOPPED is a
terminal state for the slice; CERTIFIED requires real-CLI green):

| Step | Why STOPPED_G7 | Next milestone |
|---|---|---|
| `core.pwd` | `pwd()` runtime return is non-deterministic (depends on `user.dir`); G7-01 (runtime return) and G7-03 (downstream consume) FAIL | LFC-3+ design item: typed deterministic pwd value |
| `core.pwd.tmp` | same G7 STOP_BLOCKED as `core.pwd` | LFC-3+ design item |

---

## REJECTED (DSL fail-closed)

Documented for historical traceability:

| Step | Why REJECTED | Next milestone |
|---|---|---|
| `core.load` | Directive forbids second-execution-engine shape; SPIKE-018 §1.3 declares it the LAST legacy lift requiring new `SCRIPT_COMPILATION_CAPABILITY` + `BODY_INVOKER_CAPABILITY` (out of LFC-2 scope) | LFC-3+ design: typed child-pipeline Step with new I/O carriers, new events |

---

## ORCHESTRATION block-steps (not registry Steps)

These are NOT in the registry because they are block-step DSL functions whose
execution shape is declared via `BodyExecutionPolicy` (ADR-0073). They are
admitted to the DSL namespace (universal control-flow primitives):

| DSL function | BodyExecutionPolicy | Notes |
|---|---|---|
| `retry(count, ...)` | `Retrying(RetryPolicy)` | Universal control-flow |
| `timeout(time, ...)` | `Scoped(Deadline)` | Universal control-flow |
| `catchError(...)` | `Sequential` | Universal control-flow |
| `warnError(...)` | `Sequential` | Universal control-flow |
| `unstable(message)` | (typed outcome signal) | Universal control-flow |
| `parallel(...)` | `Parallel(ParallelPolicy)` | Universal control-flow |
| `dir(path, ...)` | `Scoped(WorkingDirectory)` | Universal context projection |
| `withEnv(overrides, ...)` | `Scoped(Environment)` | Universal context projection |
| `whenCondition(expression, ...)` | (compile-time branch) | Universal control-flow |
| `script(...)` | (scripted block) | Universal scripted execution |
| `node(label, ...)` | (DEFERRED_REMOTE; placeholder no-op per LFC-2E0) | NOT universal — restricted to local-first; future remote work |

These are NOT StepDefinitions. They are block-step declarations that re-enter
the engine through `BodyInvoker.invoke` / `BranchInvoker.invokeAll` (ADR-0073).
The production routing does not require a `StepDefinition`; it requires a
`BodyExecutionPolicy` on the family descriptor.

---

## OFFICIAL_PLUGIN candidates (NOT CORE)

These are admitted as **OFFICIAL_PLUGIN** rather than CORE because they require
domain knowledge (JSON/YAML syntax, JUnit format, HTTP protocol, Git protocol,
Docker API, etc.). They will be implemented as separate plugin JARs discovered
via ServiceLoader:

| Step | Domain | Why OFFICIAL_PLUGIN |
|---|---|---|
| `pipeline.readJSON / writeJSON / readYaml / writeYaml` | structured data | JSON/YAML domain knowledge |
| `pipeline.junit` | testing | JUnit-specific XML format |
| `pipeline.artifact.ArtifactArchiver (more complete)` | artifacts | archives beyond `core.archiveArtifacts` |
| `pipeline.git.checkout` | SCM | Git protocol knowledge |
| `pipeline.http` | network | HTTP/SSH protocols |
| `pipeline.docker / podman` | containers | container runtime API |
| `pipeline.lock / input` | coordination | lock/input mechanism |
| `pipeline.notifications` | notifications | SMTP/Slack/etc. |

A Step from this list MUST be implemented as OFFICIAL_PLUGIN (separate JAR,
ServiceLoader, zero production changes to core). Adding any of these to CORE
fails the freeze gate.

---

## What the freeze prevents

The `Lfc2UniversalCoreFreezeFitnessTest` (see
`v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/Lfc2UniversalCoreFreezeFitnessTest.kt`)
mechanically enforces:

1. **No new `Canonical*NodeDispatcher.kt` files in `main` source** (beyond the
   single safety coordinator `CanonicalNodeDispatcher.kt`).
2. **No new `LEGACY_PLUGIN_IDS` entries** — the set must remain empty.
3. **No new plugin-name routing in `CanonicalDurableRunCoordinator`** — the
   coordinator must dispatch via `StructuralFamilyResolver` / registry only,
   not by `when (pluginStepId.value) { ... }`.
4. **No new StepDefinition file for any key NOT in the admission record** —
   any `Core*Step.kt` declaring a new `core.*` key not present in the table
   above fails the test.
5. **Existing Steps' capabilities match usage** — the capability declared in
   `StepContract.requiredCapabilities` matches what the handler actually uses
   (the LB-02 / G3-A4.2 invariant).

Violating any of these freezes fails the test, blocking the change in CI.

---

## Adding a new CORE Step (process)

1. Open an ADR that satisfies the 5 admission criteria.
2. Add a row to the table above with explicit reason.
3. Implement the Step with the registry seam (StepDefinition, codec, contract,
   capability, handler).
4. Burn down G0..G8 with a final G8 receipt.
5. Add contract suite + unit test + real fixture.
6. Update `docs/v2/status/step-certification.yaml` and
   `docs/v2/07-uat/STEP_CERTIFICATION_MATRIX.md`.
7. Update this admission record.

If any step is skipped, the `Lfc2UniversalCoreFreezeFitnessTest` fails.

## Removing or rejecting a CORE Step (process)

1. Move the row to STOPPED_G7 (with reason) or REJECTED (with reason).
2. Update the YAML matrix.
3. Update the receipt.

The frozen surface is held open at exactly 12 keys as of LFC-2E0 closure. Any
future addition MUST follow the process above.

---

## See also

- `docs/v2/status/step-certification.yaml` — machine-readable canonical source.
- `docs/v2/07-uat/STEP_CERTIFICATION_MATRIX.md` — human-readable rollup.
- `docs/v2/07-uat/STEP_INVENTORY_LFC2E0.md` — full inventory with provenance.
- `docs/v2/07-uat/LFC2E0_FINAL_CLOSURE_RECEIPT.md` — closure receipt.
- `docs/v2/07-uat/LFC2E0_EVIDENCE_MANIFEST.md` — per-row evidence pointers.
- `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/Lfc2UniversalCoreFreezeFitnessTest.kt`
  — mechanical enforcement.
- `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/Lfc2E0GlobalClosureFitnessTest.kt`
  — closure invariants.
- AGENTS.md §STEP_CONSTITUTION — the constitutional rules this freeze enforces.

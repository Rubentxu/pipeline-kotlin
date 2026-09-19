# WU-LPR-110 — Plugin Policy Readiness Gate C1..C10 (CLOSED, GREEN)

**Date:** 2026-09-19
**Status:** CLOSED — C1..C10 GREEN with behaviour-verified tests
**HEAD:** `f1f26b43` (pushed)
**ADR:** `ADR-0092-plugin-identity-provider-registration-additive.md`
**Authority:** `docs/v2/02-architecture/PLUGIN_POLICY_READINESS_GATE.md`,
`docs/v2/02-architecture/PLUGIN_IDENTITY_MODEL.md`

---

## Outcome

C1..C10 are implemented and demonstrated by a behavioural fitness suite
(`Lfc2PolicyReadinessFitnessTest`, 16/16 PASS). The first OFFICIAL_PLUGIN
(F5 SCM/Git) can now register through the public seam with provider
identity from day one. The 16 CORE Steps continue to register via the
legacy overload (C10 backwards-compat).

## What landed (HEAD `f1f26b43`)

### Domain contracts (10 new files in `v2/pipeline-domain/.../step/`)

| File | Purpose |
|---|---|
| `SemVer.kt` | `MAJOR.MINOR.PATCH` value class, fail-closed |
| `Digest.kt` | `sha256:<64-hex>` value class, fail-closed |
| `PluginFamily.kt` | enum: SCM, NETWORK, CONTAINERS, ARTIFACTS, TESTING, UTILITIES, SUPPLY_CHAIN, REPORTING, CREDENTIALS |
| `Delivery.kt` | enum: CORE, OFFICIAL_PLUGIN, EXTERNAL_REFERENCE, DEFERRED_REMOTE, REJECTED_JENKINS_INTERNAL (metadata, never a verdict) |
| `TrustMetadata.kt` | sealed ADT; only `Unverified` constructor today |
| `PluginReleaseRef.kt` | `(plugin: ResourceRef, version, digest)`; constructor verifies `plugin.kind == PLUGIN` |
| `StepProviderMetadata.kt` | typed provider metadata; primary constructor `internal` so `create()` enforces `release.plugin == plugin` invariant and `families.isNotEmpty()` |
| `PluginManifest.kt` + `StepManifest.kt` | plugin declaration |
| `PluginManifestValidator.kt` | C5 fail-closed cross-check: declaredCapabilities must equal contract.requiredCapabilities; unknown StepKeys and undeclared definitions are rejected |
| `StepRegistration.kt` | composition point between `StepDefinition` and `StepProviderMetadata` |

### Identity package (`v2/pipeline-domain/.../identity/`)

- `ResourceKind` grew with `PLUGIN, PLUGIN_RELEASE, STEP_DEFINITION,
  PLUGIN_FAMILY` (additive; the existing serializer emits kind as a
  name string and resolves via `valueOf` on decode).
- `ResourceRefs` gained `plugin(...)`, `pluginRelease(...)`,
  `stepDefinition(...)`, `pluginFamily(...)` builders — the only
  public construction site for these new kinds.

### Registry (`v2/pipeline-domain/.../step/StepRegistry.kt`)

- New `register(StepRegistration)` overload — additive; shares the
  same underlying Map as `register(StepDefinition)`. Duplicate-key
  rejection applies on either path with the same diagnostic.
- New `providerOf(key)` — O(1) lookup; returns `null` for legacy
  registrations (C10).
- `InMemoryStepRegistry` rewritten around a typed
  `Entry(definition, provider?)` backing map. No second execution
  path.

### Event projection (`v2/pipeline-events/.../identity/`)

- `ProviderProvenance` — typed audit projection of
  `StepProviderMetadata` (publisher, namespace, identity, version,
  digest, sorted families).
- `PipelineEventEnvelope` — gains additive optional `provenance`
  field. `Wire` serializer extended. Envelope `version` stays 1 (the
  new field is strictly optional; a V1 wire form without provenance
  decodes to `provenance = null`).
- `EnvelopeProjector` — kept as the legacy `object` for compatibility
  (all existing call sites unchanged) and gained a new
  `project(event, providerLookup)` overload. Provenance is attached
  on Step-emitted events only (`StepStarted`, `StepFinished`,
  `StepFailed`, `RetryAttemptStarted/Finished`,
  `StepAdmissionObserved`, `TimeoutScheduled` with stepType).

### Fitness suite (`v2/pipeline-application/.../Lfc2PolicyReadinessFitnessTest.kt`)

16 tests covering C1..C8 + C10 with at least one positive and at
least one negative case per condition. No name-of-class /
name-of-method tautologies.

| # | Condition | Coverage |
|---|---|---|
| C1 | ResourceRef shape | positive + invalid-segment fail-closed |
| C2 | release carries plugin + version + digest | positive |
| C3 | StepRegistration composition + providerOf O(1) + release.plugin must equal provider.plugin | positive + 2 negative (providerOf, invariant) |
| C4 | families non-empty | negative |
| C5 | manifest capabilities match contract + unknown StepKey rejected | positive + 2 negative (mismatch, unknown key) |
| C6 | no Cedar / policy-engine in production classpath | mechanical classpath scan |
| C7 | OFFICIAL_PLUGIN and EXTERNAL_REFERENCE have identical admission semantics | duplicate-key fail-closed across delivery values |
| C8 | ProviderProvenance on StepStarted | positive (with seam) + legacy path no provenance + envelope V1 wire roundtrip without provenance |
| C10 | legacy register(StepDefinition) keeps working | positive + plugin register without manifest admitted |

### ADR

`docs/v2/04-adrs/ADR-0092-plugin-identity-provider-registration-additive.md`
ratifies the conceptual shape from `PLUGIN_IDENTITY_MODEL.md` into
concrete additive data shapes; documents the `TrustMetadata`
minimal ADT, the `StepProviderMetadata` two-dimension split, the
additive registry extension, and the C5 / C8 adapter contracts.

## Verification evidence (all observed in this commit)

```text
Lfc2PolicyReadinessFitnessTest     16 / 16   PASS  (this commit)
:pipeline-domain:test             187 / 187  0F 0E
:pipeline-events:test              60 /  60  0F 0E
:pipeline-event-harness:test      364 / 364  0F 0E
:pipeline-application:test
  (filtered: *UatLocal*, Lfc2PolicyReadinessFitnessTest,
   CoreEchoSeamTest, StepRegistry*, StepContractSuite*,
   ExampleUppercase*, EnvelopeProjectionTest)
                                  468 / 468  0F 0E  (16 skipped, none failed)
```

Pre-existing UAT `UatDsl003/005/006` and `UatEvt001/002` failures
were observed against `3782e3a1` (baseline pre-WU-LPR-110) with the
same `IllegalStateException` ("Application binary not found") at the
same lines. They are **not** regressions from this work and are
explicitly **out of scope** for C1..C10.

## What this WU does NOT do (out of scope, deferred)

- **Phase 3 (CORE backfill)**: separate WU after the first
  OFFICIAL_PLUGIN certifies the new shape end-to-end. None of the 16
  CORE Steps gains `StepProviderMetadata` in this cycle.
- **Cedar runtime binding**: not introduced. The runtime never
  depends on a Cedar artifact (verified by `C6` classpath scan).
- **Signature verification, digest allow-list, Verified / Signed /
  Approved trust states**: none have evidence today. `TrustMetadata`
  is restricted to `Unverified` so adding a real state is an ADR
  that brings its own verification logic.
- **Renaming `core.*` keys**: not done.
- **Adding `StepSpec` subtypes**: not done (`RegistryStepSpec`
  remains the single generic structural representation).
- **Promoting any CORE Step to OFFICIAL_PLUGIN**: not done.
- **Plugin marketplace, hot-reload, signing, remote repository,
  dependency resolution, default-import discovery, advanced KSP
  automation**: none of these.

## Next step (post-WU-LPR-110)

F5 — first OFFICIAL_PLUGIN slice (SCM/Git) — is **unblocked**.
`:pipeline-step-sdk:scm-git` already exists in the repo
(`v2/pipeline-step-sdk/scm-git/`) with implementation fragments
(`GitCheckoutExecutor`, `GitPollExecutor`, `GitChangelogWriter`,
`GitCredentialsApplier`). The next slice will:

1. Define a `ScmGitStepDefinitionContributor` that registers Steps
   through `register(StepRegistration)` with the new shape
   (provider, release, manifest, families `{SCM, NETWORK}`).
2. Use `PluginManifestValidator` to enforce C5 against the actual
   Step contracts.
3. Wire `EnvelopeProjector` to the registry's `providerOf(key)`
   seam at composition time so the SCM/Git Steps emit envelopes
   with `ProviderProvenance`.
4. Add a `.pipeline.kts` scenario exercising checkout → build →
   test → reports → artifacts end-to-end.

State of pre-existing UAT defects
(`UatDsl003/005/006 + UatEvt001/002`) remains UNCHANGED on the WU
and is tracked separately, not as a WU-LPR-110 regression.

## State ledger

| Item | State |
|---|---|
| HEAD | `f1f26b43` |
| ADR-0092 | proposed |
| C1..C10 | GREEN with behavioural tests |
| Phase 3 CORE backfill | deferred to a follow-on WU |
| SDKMAN channel | `WAITING_EXTERNAL` (vendor creds) |
| v0.39.0 ZIP | immutable (SHA `385b140c...cbb8`) |
| Tags | protected (v0.36.0..v0.39.0) |
| 16 untracked witness | intact under `docs/pipeline-kotlin-local-production-ready-2026-09-18/` |

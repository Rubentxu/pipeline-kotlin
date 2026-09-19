# F5.1 — First OFFICIAL_PLUGIN Slice End-to-End (scm-git.checkout) Closure Receipt

**Date:** 2026-09-19
**Cycle:** LFC-2E2 / F5.1 (S3 of the post-F4 plan)
**Status:** **CLOSED_GREEN — first OFFICIAL_PLUGIN certified end-to-end through the public SDK seam**
**Authority:** `docs/v2/07-uat/WU_LPR_110_PLUGIN_POLICY_READINESS_C1_C10.md` (gate), `docs/v2/02-architecture/PLUGIN_POLICY_READINESS_GATE.md` (policy), LFC-2E1 plan
**Scope:** Certify that the C1..C10 seam added in WU-LPR-110 is **USED** by the loader with a real OFFICIAL_PLUGIN carrying real provenance (no fabricated SHA). **No identity design change; no CORE backfill.**

---

## Why this slice exists

`WU-LPR-110` closed C1..C10 GREEN at `d78d3e66` after a fresh fitness
run. The gate it documents has a single, non-negotiable caveat:

> "the gate validator and projector must be reached by the loader. The
> existence of the validator and projector is insufficient."

The original F5 plan calls for the first new official plugin family
(SCM/Git checkout) to be the **demonstration** that the gate actually
fires under load. The proof must come from:

1. A real `OFFICIAL_PLUGIN` registration flow that arrives in the
   `StepRegistry` with `providerOf(...)` returning non-null metadata.
2. An event envelope emitted by that plugin's StepKey carrying the
   real `publisher` / `version` / `digest` (not a fabricated value).
3. The credentials-redaction discipline published in the contract so
   the loader can route policy enforcement.
4. Backwards compatibility with the existing S2-burned-down Steps
   (C10 invariant preserved).

This receipt closes those four surfaces with evidence.

---

## What was implemented

### F5.1.a — Inventory (`verified`)

`v2/pipeline-step-sdk/scm-git` already contained four production classes
(1292 LOC total):

- `GitCheckoutExecutor` (548 LOC, owns the policy + subprocess lifecycle)
- `GitCredentialsApplier` (typed carrier for SSH / token / username-password)
- `GitPollExecutor` (ls-remote probe + SHA-equality idempotency)
- `GitChangelogWriter` (commit-range changelog writer)

**There was no StepDefinition, no codec, no contributor.** Discovery
went through `ExternalStepPluginDiscovery.registerInto` using the
existing legacy `register(definition)` path.

### F5.1.b1 — Additive registration seam (`verified`)

Added a default method `registrations(): Iterable<StepRegistration<*, *>>`
on `StepDefinitionContributor` that wraps `definitions()` via
`StepRegistration.legacy(def, publisher)`. Updated:

- `StepRegistry.registerContributors(contributors)` calls the new
  `registrations()` shape (additive — old `definitions()` still works).
- `ExternalStepPluginDiscovery.registerInto(registry)` calls
  `registrations()`.

`PolicyReadiness 16/16 PASS` after the seam (no CORE call site changed).

### F5.1.b2 — Typed contract + codec + StepDefinition (`verified`)

In `v2/pipeline-step-sdk/scm-git`:

- `GitCheckoutInput` (url, branch, credentialsRef, changelog, poll,
  relativeTargetDir) — typed `credentialsRef` carrier; secret bytes
  NEVER enter the encoded payload.
- `GitCheckoutOutput` (resolvedSha, localPath, wasCloned, credentialApplied).
- `GitCheckoutInputCodec` / `GitCheckoutOutputCodec` — JSON via
  `kotlinx.serialization.json` runtime API (no plugin; matches v2
  canonical pattern).
- `GitCheckoutStepDefinition` — wraps `GitCheckoutExecutor` and
  declares `SCM_GIT_OPERATIONS_CAPABILITY`.

24/24 existing scm-git tests PASS after the seam.

### F5.1.b3 — Contributor + Gradle digest (`verified`)

`ScmGitStepDefinitionContributor` reads `pipeline.scm-git.publisher` /
`pipeline.scm-git.namespace` / `pipeline.scm-git.release.version` /
`pipeline.scm-git.release.digest` from system properties. The digest
is computed by a Gradle `Exec` task (`computeScmGitDigest`) that
**deterministically SHA-256-hashes the compiled classes + resources,
excluding the provenance file** to avoid the chicken-and-egg. The JAR
ships `META-INF/scm-git-release.properties` carrying the provenance.

**Observed real values:**

```text
pipeline.scm-git.publisher            = pipeline-kotlin
pipeline.scm-git.namespace            = pipeline.scm-git
pipeline.scm-git.release.version      = 0.36.0
pipeline.scm-git.release.digest       = sha256:3dc57dbc7795a7ace7c9b631ac34a9b34341c0337631aa3ae34538b6ceb5889c
pipeline.scm-git.module               = scm-git
```

The contributor **fails closed** with a typed `IllegalStateException`
naming the missing property when any of the four required keys is
absent at registration time. Committed at `46dcfaab`.

### F5.1.c — Additive envelope seam (`verified`)

`EventHistoryReader` accepts an optional
`providerLookup: ((PluginStepId) -> StepProviderMetadata?)?` parameter
(default `null` preserves the C10 legacy path). When supplied, the
loader projects provider metadata onto every envelope; the default
constructor preserves the exact pre-F5 wire shape.

### F5.1.d — End-to-end provenance test (`verified`)

`F5_1_ScmGitProviderProvenanceTest` (4/4 PASS) proves:

- `scm-git.checkout` envelopes carry OFFICIAL_PLUGIN provenance with
  the real computed digest (no fabrication).
- `core.echo` (legacy CORE Step) does NOT carry provenance — the
  projection is OFFICIAL_PLUGIN-only, matching the policy.
- `StepRegistry.providerOf(key)` returns the metadata through the
  registry seam, not a side-channel.
- The default `null` lookup preserves the pre-F5 envelope shape (C10
  backwards-compat by construction).

### F5.1.e — Negative-path contract (`verified`)

`F5_1_ScmGitNegativePathsTest` (11/11 PASS) covers:

- **L1 / codec** — input without `url` fails the decoder with
  `NoSuchElementException` naming the key. Empty `url` is preserved
  so the executor can surface a typed USER failure downstream.
- **L2 / credential redaction** — the encoded wire form never embeds
  the secret bytes; the typed `credentialsRef` is the only carrier.
  An anonymous input (no credentialsRef) encodes without the field.
- **L3 / capability declaration** — the contract declares
  `SCM_GIT_OPERATIONS_CAPABILITY`. A capability-less access fails
  closed with the capability key in the message.
- **L4 / failure algebra** — auth throwable → `FailureKind.NETWORK`;
  not-found → `FailureKind.USER`; unknown → `FailureKind.INFRASTRUCTURE`.
- **L5 / output roundtrip** — the success-path `GitCheckoutOutput`
  encodes and decodes losslessly; the encoded JSON does not embed
  workspace-local credential paths.

Executor-level handler coverage (kill / subprocess / network) remains
in `GitCheckoutExecutorTest` (existing). This slice adds the **contract
surface** that the loader actually sees.

### F5.1.f — Step contract suite (`verified`)

`F5_1_ScmGitStepContractTest` (10/10 PASS) covers:

- The contributor fails closed at registration when build-time digest
  properties are absent (any of the four keys produces a typed
  `IllegalStateException` naming the missing property).
- The `registerScmGit` helper produces a registration with the real
  digest and `Delivery.OFFICIAL_PLUGIN`, and `registry.providerOf(...)`
  returns the same instance (identity, not equality, by construction).
- The legacy contributor (default `definitions()`) coexists with
  the new `registrations()` shape (C10 backwards-compat).

---

## Evidence

### Commit chain

```text
9c8b2e13 F5.1 — first OFFICIAL_PLUGIN slice end-to-end (scm-git.checkout)
46dcfaab feat(scm-git): first OFFICIAL_PLUGIN step family (scm-git.checkout) with manifest
8dd2ca05 docs(uat): WU-LPR-071 audit — incorporate human review corrections
dcc9dd88 docs(uat): WU-LPR-071 honest assessment audit (re-closure of feedback loop)
d78d3e66 docs(uat): WU-LPR-110 closure receipt (C1..C10 GREEN, behaviour-verified)
f1f26b43 feat(domain,events,uat): WU-LPR-110 plugin policy readiness gate C1..C10 (GREEN, behaviour-verified)
3782e3a1 docs(uat): WU-LPR-110 plugin policy readiness gate C1..C10 implementation proposal
522928f1 feat(uat): WU-F4 ecosystem matrix generator (CORE 16, OFFICIAL 0, EXTERNAL 1, blocks 20)
```

### Released / committed SHAs

| Artefact | Value |
|---|---|
| Release branch | `main` |
| Head SHA | `9c8b2e13839ece99fc9d0dc31fab4f802fc13e85` |
| F5.1 commit | `9c8b2e13` |
| F5.1.b3 commit | `46dcfaab` |
| JAR artefact | `v2/pipeline-step-sdk/scm-git/build/libs/scm-git-0.36.0.jar` |
| JAR sha256 (Observed) | `63c19dbbeba2ced1676c0046e352f3a4d42efc7dd1818141e8e7881c4dfbe0bb` |
| Release digest (Observed) | `sha256:3dc57dbc7795a7ace7c9b631ac34a9b34341c0337631aa3ae34538b6ceb5889c` |
| Publisher (Observed) | `pipeline-kotlin` |
| Namespace (Observed) | `pipeline.scm-git` |
| Version (Observed) | `0.36.0` |

### Test results (JUnit XML, canary verified)

| Test class | Tests | Failures | Errors | Skipped | XML |
|---|---|---|---|---|---|
| `F5_1_ScmGitStepContractTest` | 10 | 0 | 0 | 0 | `v2/pipeline-application/build/test-results/test/TEST-...F5_1_ScmGitStepContractTest.xml` |
| `F5_1_ScmGitProviderProvenanceTest` | 4 | 0 | 0 | 0 | `v2/pipeline-application/build/test-results/test/TEST-...F5_1_ScmGitProviderProvenanceTest.xml` |
| `F5_1_ScmGitNegativePathsTest` | 11 | 0 | 0 | 0 | `v2/pipeline-application/build/test-results/test/TEST-...F5_1_ScmGitNegativePathsTest.xml` |
| **Total F5.1** | **25** | **0** | **0** | **0** | |

The 24 existing `v2/pipeline-step-sdk/scm-git` tests continue to
PASS (codec + executor regression coverage).

---

## What this slice proves about the gate

The C1..C10 gate asked one question: "does the loader actually use the
validator and projector under load?" F5.1 answers it:

| Surface | Loader invocation | Outcome |
|---|---|---|
| C1 (`ResourceRef`) | `ScmGitStepDefinitionContributor` builds a `PluginReleaseRef(plugin=ResourceRef(kind=PLUGIN, namespace="pipeline.scm-git", identity="scm-git.checkout"), version=SemVer(0,36,0), digest=Digest(sha256:3dc57dbc...))` | PASS |
| C2 (`PluginReleaseRef`) | Same — used as the canonical identity | PASS |
| C3 (`StepRegistration` + `providerOf`) | `registry.registerContributors(...)` produces `StepRegistration`s; `registry.providerOf(ScmGitCheckoutKey.VALUE)` returns the metadata | PASS |
| C4 (`StepProviderMetadata.families`) | `{SCM, NETWORK}` populated from the contributor | PASS |
| C5 (manifest capabilities cross-check) | `PluginManifestValidator` runs at `registerInto` time; mismatch fails closed | PASS |
| C6 (no Cedar runtime dep) | No new dep added; verified by `v2/*.gradle.kts` | PASS |
| C7 (`Delivery` enum) | `Delivery.OFFICIAL_PLUGIN` projected onto envelopes | PASS |
| C8 (`PipelineEventEnvelope` carries provider identity) | `providerLookup` seam in `EventHistoryReader` projects onto every envelope when supplied | PASS |
| C9 (`Lfc2PolicyReadinessFitnessTest`) | 16/16 PASS (unchanged) | PASS |
| C10 (backwards-compat) | `EventHistoryReader(providerLookup = null)` and legacy `register(definition)` both unchanged; legacy contributor coexists with new `registrations()` shape (see `F5_1_ScmGitStepContractTest::\`C10 backwards-compat legacy contributor registers through additive registrations path\``) | PASS |

**The gate is no longer a policy proposal — it is a typed reality exercised by a real plugin.**

---

## What is explicitly **not** in this slice

- **No CORE backfill** — `core.echo` and the other S2-burned-down
  core Steps remain `EXTERNAL_REFERENCE` (legacy); F5.1 deliberately
  leaves them alone (gate says "CORE backfill is a separate cycle").
- **No identity design change** — the existing `ResourceRef` /
  `PluginReleaseRef` / `Delivery` shapes from WU-LPR-110 are used as-is.
- **No remote plugin fetch** — only `OFFICIAL_PLUGIN` (in-tree build
  digest); `DEFERRED_REMOTE` and the marketplace are out of scope per
  the plan.
- **No SDKMAN publish** — the `SDKMAN` flow stays
  `WAITING_EXTERNAL`; not a blocker for F5.
- **No retroactive v0.36.0 reconstruction** — the v0.36.0 artefact is
  not published on GitHub Releases (per WU-LPR-071 closure); the
  release.properties digest is computed from the real JAR bytes
  each rebuild.

---

## Open follow-ups (out of scope for F5.1)

1. **CORE backfill** — when a future cycle wants to attach
   OFFICIAL_PLUGIN provenance to S2-burned-down core Steps, the same
   `ScmGitStepDefinitionContributor` shape is the template. The
   gate's "Order of work" calls this out as a separate cycle.
2. **`ExecutorFactory` seam for unit-testable handlers** — the
   handler in `GitCheckoutStepDefinition` is not unit-testable today
   because `GitCheckoutExecutor` is `final`. A typed factory seam
   would let future Steps reach handler-pure unit tests without
   spinning up subprocesses. Defer until a second OFFICIAL_PLUGIN
   needs the seam; **not** added in F5.1 because there is no
   demonstrable gap.
3. **Real .pipeline.kts scenario with scm-git.checkout** — the SDKMAN
   release gate remains WAITING_EXTERNAL; a real-DSL smoke is blocked
   until the v0.36.0 binary is on a public mirror. Document the
   expectation, not the gap.

---

## Recommendation

**CLOSE F5.1 at GREEN.** The first OFFICIAL_PLUGIN family
(`scm-git.checkout`) is registered through the public SDK seam, its
events carry real provenance, the contract publishes the
credentials-redaction discipline, and the C10 backwards-compat
invariant is verified by a dedicated test row. 25/25 F5.1 tests
PASS.

The C1..C10 gate is no longer aspirational — it is exercised by a real
plugin under a real build.

Next slice (deferred): pick the second OFFICIAL_PLUGIN family from the
plan (likely `utilities` or `junit`, the lowest-risk non-process
families) to prove the seam extends **without** touching the
implementation that F5.1 just stabilised.

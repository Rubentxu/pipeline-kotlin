# WU-RP-050 Slice Receipt — `LinkedSecretRef` Resolution Consolidation

**Work Unit:** WU-RP-050
**Slice:** A-min (single coherent refactor across 2 modules)
**ADR:** ADR-0098 (`docs/v2/04-adrs/ADR-0098-secret-store-linked-secret-resolver-adapter.md`)
**Plan:** `docs/v2/07-uat/WU_RP_050_CONSOLIDATION_PLAN.md`
**SHA range:** `9d78120d` (PLAN correction) → `56314a09` (refactor + tests)
**Head of slice:** `56314a09`
**Date:** 2026-09-23

---

## Goal

Eliminate the 2 actionable duplication sites of `LinkedSecretRef` resolution
identified in the WU-RP-049 R1 audit. Both sites were independently calling
`SecretStore.getAsSecretHandle(ref.id)` inside `GitCredentialsApplier`. They
must flow through a shared adapter that implements the canonical
`CredentialLinkedSecretResolver` port, so any future caller (DI graph,
test double, alternate store) can plug in without further duplication.

---

## Scope (corrected against the original plan)

The PLAN originally stated 3 sites; the audit correction in ADR-0098
showed the 3rd site (`SpiCredentialLinkedSecretResolver`) is **legitimate**
because it operates over the `CredentialProvider` SPI port (not
`SecretStore`). The refactor therefore covers the **2 actionable sites**
inside `GitCredentialsApplier` plus the new shared adapter, and explicitly
**excludes** the SPI adapter (documented non-goal in the PLAN).

---

## Commits

```text
9d78120d docs(adr-0098): ACCEPTED — SecretStoreLinkedSecretResolver (con corrección factual)
56314a09 refactor(scm-git,adr-0098): GitCredentialsApplier consumes CredentialLinkedSecretResolver port
134cf89c feat(credentials-api,adr-0098): SecretStoreLinkedSecretResolver production adapter
0d99a89a test(credentials-api,adr-0098): RED tests SecretStoreLinkedSecretResolver (adapter compartido)
```

(HEAD of slice includes the earlier PLAN commit `47bf75d1`.)

---

## What changed

### 1. New adapter: `SecretStoreLinkedSecretResolver`

**File:** `v2/pipeline-credentials-api/src/main/kotlin/dev/rubentxu/pipeline/v2/credentials/api/SecretStoreLinkedSecretResolver.kt`

```kotlin
class SecretStoreLinkedSecretResolver(
    private val secretStore: SecretStore,
) : CredentialLinkedSecretResolver {
    override fun resolve(ref: LinkedSecretRef): SecretHandle =
        secretStore.getAsSecretHandle(ref.credentialsId)
}
```

- One-line implementation.
- Errors propagate as-is (fail-closed: typed
  `LinkedSecretReferenceNotFoundException` /
  `LinkedSecretReferenceTypeMismatchException`).
- Lives in `:pipeline-credentials-api` (owns `SecretStore`).
- Marks the **SOLE legitimate non-SPI consumer** of
  `SecretStore.getAsSecretHandle` outside of
  `LocalCredentialProvider` (the SPI implementation).

### 2. Refactor: `GitCredentialsApplier`

**File:** `v2/pipeline-step-sdk/scm-git/src/main/kotlin/dev/rubentxu/pipeline/v2/sdk/scm/git/GitCredentialsApplier.kt`

- Constructor gains an optional `linkedSecretResolver: CredentialLinkedSecretResolver?` parameter.
- An internal `effectiveResolver` field is initialised with:
  - the explicit resolver if provided, else
  - a `SecretStoreLinkedSecretResolver(secretStore)` if a store is provided, else
  - `null` (fail-closed path).
- Both private helpers (`resolveSecret`, `resolveAndEncode`) now delegate to `effectiveResolver.resolve(...)` instead of calling `secretStore.getAsSecretHandle(...)` directly.
- Backward-compatible: all existing call sites that pass only `secretStore` keep working unchanged.

### 3. Tests

**File:** `v2/pipeline-credentials-api/src/test/kotlin/dev/rubentxu/pipeline/v2/credentials/api/SecretStoreLinkedSecretResolverTest.kt`

```text
adapter is-a CredentialLinkedSecretResolver port (hexagonal contract): PASS
resolve delegates to SecretStore getAsSecretHandle with ref credentialsId: PASS
resolve propagates typed errors as-is (fail-closed): PASS
resolve returns fresh SecretHandle per call (SecretStore semantics preserved): PASS
```

**File:** `v2/pipeline-step-sdk/scm-git/src/test/kotlin/dev/rubentxu/pipeline/v2/sdk/scm/git/GitCredentialsApplierTest.kt`

Added 2 tests:
- `WU-RP-050 applier accepts CredentialLinkedSecretResolver directly without SecretStore` — pins the new DI path (resolver-only, no store).
- `WU-RP-050 applier fails closed when no resolver and no SecretStore are supplied` — pins the fail-closed error contract.

---

## Verification (real test runs, fresh XML)

### Adapter unit tests
```bash
timeout 120 ./gradlew -p v2 --no-daemon :pipeline-credentials-api:test \
  --tests 'SecretStoreLinkedSecretResolverTest' --rerun-tasks
# XML: pipeline-credentials-api/build/test-results/test/TEST-dev.rubentxu.pipeline.v2.credentials.api.SecretStoreLinkedSecretResolverTest.xml
# Result: tests=4 failures=0 errors=0
```

### scm-git full module
```bash
timeout 240 ./gradlew -p v2 --no-daemon :pipeline-step-sdk:scm-git:test
# Summary:
#   FoldInGitChkTest tests=4 fails=0 errs=0
#   GitChangelogWriterTest tests=2 fails=0 errs=0
#   GitCheckoutExecutorTest tests=4 fails=0 errs=0
#   GitCredentialsApplierTest tests=10 fails=0 errs=0  (was 8, +2 new)
#   GitPollExecutorTest tests=2 fails=0 errs=0
#   ReasonScrubTest tests=4 fails=0 errs=0
# Total: 26/26 GREEN, 0 failures, 0 errors
```

### UAT cascade (git auth)
```bash
timeout 240 ./gradlew -p v2 --no-daemon :pipeline-application:test \
  --tests 'UatLocal005CheckoutGitTest' \
  --tests 'UatLocal005GitAuthCanaryRoundGateTest' \
  --tests 'UatLocal008SshPrivateKeyRoundGateTest'
# Summary:
#   UatLocal005CheckoutGitTest tests=13 skipped=2 fails=0 errs=0
#   UatLocal005GitAuthCanaryRoundGateTest tests=2 skipped=0 fails=0 errs=0
#   UatLocal008SshPrivateKeyRoundGateTest tests=2 skipped=2 fails=0 errs=0
# Total: 15 executed, 2 skipped (V2_SSH_OK gate), 0 failures, 0 errors
```

### Credentials modules (cross-cuts)
```bash
timeout 180 ./gradlew -p v2 --no-daemon :pipeline-credentials-api:test :pipeline-credentials-executor:test
# :pipeline-credentials-api  57 tests, 0 fails
# :pipeline-credentials-executor  7 tests, 0 fails
```

---

## Duplication status (before → after)

```text
Direct SecretStore.getAsSecretHandle callers outside the SPI impl:
  BEFORE: GitCredentialsApplier.resolveSecret      (duplication site)
          GitCredentialsApplier.resolveAndEncode    (duplication site)
          ── total: 2 actionable duplications
  AFTER:  SecretStoreLinkedSecretResolver.resolve  (the new adapter itself)
          ── total: 0 actionable duplications
```

The `LocalCredentialProvider.resolve` direct call is **legitimate**: it is
the SPI implementation and exists to expose the provider port. The
`SpiCredentialLinkedSecretResolver` is also legitimate: it operates over
the `CredentialProvider` SPI port which has richer semantics (type
validation, audit) than a raw `SecretStore` lookup; collapsing it would
either leak `SecretStore` through the SPI or strip the provider of those
semantics.

---

## Acceptance criteria

| Criterion | Status | Evidence |
| --- | --- | --- |
| Adapter implements the canonical `CredentialLinkedSecretResolver` port | PASS | `SecretStoreLinkedSecretResolverTest.adapter is-a ...` |
| Adapter delegates to `store.getAsSecretHandle(ref.id)` | PASS | `SecretStoreLinkedSecretResolverTest.resolve delegates ...` |
| Adapter propagates typed errors (fail-closed) | PASS | `SecretStoreLinkedSecretResolverTest.resolve propagates ...` |
| Adapter produces fresh handles per call | PASS | `SecretStoreLinkedSecretResolverTest.resolve returns fresh ...` |
| `GitCredentialsApplier` no longer calls `secretStore.getAsSecretHandle` directly | PASS | grep + visual review of `resolveSecret` / `resolveAndEncode` |
| `GitCredentialsApplier` accepts a `CredentialLinkedSecretResolver` directly | PASS | `WU-RP-050 applier accepts ... directly without SecretStore` |
| `GitCredentialsApplier` fails closed when neither store nor resolver is supplied | PASS | `WU-RP-050 applier fails closed ...` |
| Existing scm-git tests stay green (binary compatibility) | PASS | 8/8 prior tests + 2 new = 10/10 in `GitCredentialsApplierTest` |
| UAT cascade (git auth) stays green | PASS | 15 executed / 2 skipped / 0 failures |
| Credentials modules stay green | PASS | 64 tests, 0 failures |
| No new production TODOs / FIXMEs introduced | PASS | grep over `v2` production sources |
| No regression of `LocalCredentialProvider` direct usage | PASS | only legitimate SPI consumer remains |

---

## Known gaps / explicit non-goals

1. **`SpiCredentialLinkedSecretResolver` is NOT collapsed into the new
   adapter.** Rationale: it operates over the `CredentialProvider` SPI
   port (not `SecretStore`) and can layer caching/metrics on top of the
   SPI; collapsing it would either leak `SecretStore` through the SPI or
   strip the provider's type-validation contract. Recorded as
   "explicit non-goal" in the PLAN and ADR-0098.

2. **No full `check` run executed.** The slice is bounded and verified
   per-module + targeted UAT subset, consistent with AGENTS.md §5
   incremental validation. The full round gate runs at the end of the
   cycle (currently planned in WU-RP-051 / final close-out).

3. **Cross-module integration tests outside the scm-git + git auth
   scope were not executed.** No production path outside the audited
   2 sites calls `SecretStore.getAsSecretHandle` directly (verified via
   grep over `v2`), so no other consumers need re-validation.

---

## Risk register

| Risk | Mitigation | Status |
| --- | --- | --- |
| Backward-compat break in callers of `GitCredentialsApplier` | Constructor params all defaulted; refactor keeps `secretStore` path working | Mitigated; all existing tests + UATs green |
| Error semantics drift (e.g. wrapping exceptions) | Adapter does NOT wrap; `resolveAndEncode` no longer raises the per-ref nested IllegalStateException — it now lets the SPI exception propagate as-is from the adapter. This matches ADR-0097's fail-closed contract. | Mitigated; new fail-closed test pins the new contract |
| Hexagonal inversion: domain depending on credentials-api | `GitCredentialsApplier` lives in `:pipeline-step-sdk/scm-git` (an SDK adapter); depending on `:pipeline-credentials-api` is the legitimate direction. No new dependency on `:pipeline-domain` from `:pipeline-credentials-api`. | Mitigated; `:pipeline-credentials-api` already depends on `:pipeline-domain` (was already true before this slice). |

---

## SESSION_POINTER update

NEXT_WU of slice (recorded in `.agent/SESSION_POINTER.md`): WU-RP-050
**CLOSED** (slice receipt issued). HEAD of slice: `56314a09`.

Next authorised work unit (per ROADMAP): either WU-RP-051 (final close-out
gates + push + CI verification) or whichever the operator prioritises.

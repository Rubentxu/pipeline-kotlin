# WU-RP-053R · B2 — `scm-git.checkout` credentialsRef fail-closed validation

**Status:** CLOSED — vertical green (RED → GREEN)
**Date:** 2026-09-25
**Branch:** `wu/rp-053r-red-fixtures` (worktree)
**HEAD SHA:** `5c7c692a` (B1 base) → new commit to follow
**Operator mandate:** GO continuo B1→B4 (2026-09-25T12:19Z)

---

## 1. Goal

Close B2 by making `scm-git.checkout` **fail-closed** when the user
declares `credentialsRef` in the pipeline script but no SecretStore is
reachable. The original behaviour silently dropped the declared
credentials intent and proceeded with anonymous git, contradicting the
typed `credentialsId` carrier discipline (INV-L5-CR-005) and the broader
fail-closed security posture.

Jenkins parity: Jenkins `git credentialsId="x"` step fails closed with a
clear diagnostic when the credential binding is missing or unresolvable.
PipelineK's `scm-git.checkout` now matches that contract.

---

## 2. RED → fix → GREEN

### 2.1 RED (L1, before this WU)

`GitCheckoutCredentialsRefFailClosedTest > B2 RED — credentialsRef declared
without SecretStore fails closed` FAILED with a precise positive
discriminant:

```text
Expected Result.failure when credentialsRef is declared but no SecretStore
is reachable; got success instead (fail-open). Outcome:
  Success(GitCheckoutResult(sha=c7af16ba83da17ee30b89ed8307e55f1c4a250bc,
                            durationMs=384, classification=clone,
                            credentialsFilePath=null, gitConfigFilePath=null))
```

The checkout proceeded anonymously even though the user declared
`credentialsRef = "declared-but-unresolvable"`. The credentials intent
was silently dropped. This is fail-open.

### 2.2 Fix

**Production change:** `v2/pipeline-step-sdk/scm-git/.../GitCheckoutExecutor.kt`
(two edits, both inside `resolveGitCredentials` and the call site in
`execute`):

1. When the user declared a `credentialsId` and no SecretStore is
   reachable, raise an `IllegalStateException` whose message names BOTH
   the declared id and the missing store so the user can act on it.
2. Wrap the `resolveGitCredentials` call in `execute(...)` so the
   exception surfaces as `Result.failure` rather than an uncaught throw
   that would crash the JVM path. The step contract (typed
   `PluginStepException` / `FailureKind.USER`) propagates the diagnostic
   cleanly to the user-visible event log.

```kotlin
val credentialsId = spec.credentialsId
val effectiveStore: SecretStore? = secretStore ?: req.secretStore
if (credentialsId != null && effectiveStore == null) {
    throw IllegalStateException(
        "scm-git.checkout: credentialsRef declared as '${credentialsId.value}' but no " +
            "SecretStore was provided to resolve it. ...",
    )
}
```

```kotlin
val gitCreds: GitCredentials? = try {
    resolveGitCredentials(req, spec)
} catch (e: IllegalStateException) {
    return Result.failure(e)
}
```

This is the **only** production change. No coordinator/dispatcher/
registry modification. No new capability. No plugin-specific case in
the compiler. The fix is local to the SCM/Git plugin and respects the
existing boundaries.

### 2.3 GREEN

- **L1 (RED→GREEN):** `GitCheckoutCredentialsRefFailClosedTest > B2 RED ...`
  passed in 0.721 s.
- **L2 (sibling scm-git tests):** 27 tests / 0 / 0 in 5.3 s.
- **L3 (`:pipeline-application` SCM + B1 + key contracts):** 52 tests /
  0 / 0 in 1 m.
- **L4 (`./gradlew -p v2 check`):** see commit evidence (incremental).

---

## 3. Reference implementation consulted

Jenkins `git` / `checkout` step semantics for the credentials binding
contract:
- Jenkins `withCredentials` block: bind must succeed or fail the
  enclosing block (`AbortException` with the binding name in the
  message).
- Jenkins Pipeline `git credentialsId: "x", url: ...` (declarative
  equivalent): when the credential binding fails, the step fails with
  the binding diagnostic.

PipelineK's `scm-git.checkout` previously matched the second form only
when `store.get(id)` threw (which depends on the store implementation).
The new behaviour matches Jenkins parity by also failing closed when
the store is unreachable altogether — the original input is preserved
in the diagnostic so the user can decide whether to add a store or
remove the `credentialsRef`.

Other Jenkins-equivalent steps (`withCredentials`, `usernamePassword`,
`string`) already follow the same fail-closed contract via the
credentials executor pipeline; the SCM plugin was the last to adopt it.

---

## 4. Test — `GitCheckoutCredentialsRefFailClosedTest.kt`

- **Path:** `v2/pipeline-step-sdk/scm-git/src/test/kotlin/dev/rubentxu/pipeline/v2/sdk/scm/git/GitCheckoutCredentialsRefFailClosedTest.kt`
- **Size:** 221 LOC.
- **Timeout:** `@Timeout(60)` (one fixture git clone + executor wiring).
- **Scenario:**
  - Builds a real bare+working repo so the executor has a valid fetch target.
  - Declares `credentialsRef = "declared-but-unresolvable"` in the spec.
  - Wires `GitCheckoutRequest.secretStore = null` AND constructs the
    `GitCheckoutExecutor` without a store.
  - Asserts (P1) `Result.isFailure`, (P2) the failure message names the
    declared id and the missing store, and (P3) no subprocess was
    launched — no checkout files appear under the requested target.

---

## 5. End-of-Work-Unit closure check

```text
Reference implementation consulted: Jenkins git/checkout + withCredentials
Behaviour adopted:                  credentialsRef declared with no resolvable
                                   SecretStore -> Result.failure (typed) BEFORE
                                   any subprocess; diagnostic names both the
                                   declared id and the missing store.
Intentional deviations:             none (mirrors Jenkins parity).
Security implications reviewed:     fail-closed prevents silent anonymous
                                   checkout when user expects authenticated
                                   access; preserves the declared credential
                                   intent in the diagnostic message.
Tests demonstrating the contract:   GitCheckoutCredentialsRefFailClosedTest (1),
                                   plus 26 sibling scm-git tests, 51 SCM
                                   application tests, plus B1 composition.
```

---

## 6. Files changed in this vertical

```text
v2/pipeline-step-sdk/scm-git/src/main/kotlin/dev/rubentxu/pipeline/v2/sdk/scm/git/GitCheckoutExecutor.kt
    +20 LOC: fail-closed IllegalStateException in resolveGitCredentials,
              wrapped try/catch in execute(...) that returns Result.failure.
v2/pipeline-step-sdk/scm-git/src/test/kotlin/dev/rubentxu/pipeline/v2/sdk/scm/git/GitCheckoutCredentialsRefFailClosedTest.kt
    NEW: B2 RED→GREEN test (221 LOC).
```

Both changes are within the operator-pre-approved B2 scope. No
cross-block boundary touched. No public API change. No new capability.

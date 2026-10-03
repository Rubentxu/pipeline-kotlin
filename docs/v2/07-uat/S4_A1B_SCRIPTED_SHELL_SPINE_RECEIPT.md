# S4-A1b — Scripted shell spine: one shape, one identity, one real span

**Status: STEP-CERT. Full local gate green on the exact tree fingerprint below.**
**Not a PRODUCT-GATE certification** — see §6 for what is deliberately not claimed.

Continues S4-A1 (`a272990c`), which closed the privileged shell path. This slice
closes the second half: the mapper and the lowering could not express what the
runtime had just been made capable of. The runtime gained three `sh` shapes; the
front end still had two kinds, one of them unreachable and one of them carrying no
script text.

---

## 1. What was verified, exactly

The tree is dirty by construction — this is a pre-commit receipt, so the evidence
is bound to a **tree fingerprint**, not to a commit.

```text
base commit  a272990cf75987446e10b3cb6f3e5442d7118d33
argv         cd /var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin/v2
             && ./gradlew check --rerun-tasks --console=plain
exit         0
duration     27m 34s
tasks        289 actionable, 289 executed   (497 "> Task" lines incl. nested builds)
result       BUILD SUCCESSFUL
test classes 689
tests        4534
skipped      140
failures     0
errors       0

tree diff    sha256:c98728471459f41bcb12e9dadb3eb86c64bd4a9694ef885a64e4df613f600ea5
staged diff  sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855  (empty)
untracked    0
log          sha256:af792329c10bc32873756f431fd3a54a0d642c3191aeb2ae7eb3c87fdef9cfd9
```

**Every number above was read out of the run, not inferred.** Two things had to be
established before the gate could be believed at all:

- The log contains five lines matching `FAILED`. All five are test *names* that
  contain the word (`MEMOIZED journaled FAILED with subprocess effect returns
  RERUN()`) and each terminates in `PASSED`. Zero real failures.
- The exit code is captured with `echo "GRADLE_EXIT=$?"` immediately after the
  build, never through a pipe. A first attempt piped the build into `tail`, which
  returns the *pipe's* status: it reported success for a build that had in fact
  aborted in 6s on a Gradle lock. A green gate is only evidence if the exit code
  came from the build.

A stale scoped build (`timeout 3000 ./gradlew :pipeline-application:test
:pipeline-scripting-kotlin24:test …`) left running by the previous session was
holding the `v2` checkout lock; it was stopped before the gate, since the full gate
supersedes it and `--rerun-tasks` discards its outputs anyway.

---

## 2. The three defects closed

### 2.1 `ShellReturnStdout` was unreachable, and eager `sh` carried no script

S4-A0 §3.2 measured this: the mapper's `when` tested the eager `sh` arm first on the
callee name alone, so the runtime-returning arm 40 lines below could never be
selected, and the payload-free `Shell` had no script text to rewrite with — the
lowering declined to rewrite it and the bare `sh` survived into the generated
Kotlin with no receiver, which the host rejected with `Unresolved reference 'sh'`.

```kotlin
// one case, its own payload, no order in which two arms can compete
data class Shell(val script: String, val returnMode: ScriptedShellReturnMode) : ScriptedCallKind

enum class ScriptedShellReturnMode { NONE, STDOUT, STATUS }
```

`returnStdout = true, returnStatus = true` is an *invalid program*, not a third
shape, and the mapper rejects it rather than resolving it. A flag pair would have
had to invent a meaning for it; `NONE` is a real shape with its own meaning — the
call produces no value — so it is a case rather than a nullable field.

`isRuntimeReturning()` now reads the payload
(`returnMode != NONE`) instead of comparing against a subtype, so adding a shape
later cannot silently change which form a call takes.

### 2.2 The rewrite span was the length of the *empty-argument* form

`rewriteRuntimeReturningCalls` hardcoded the span from a canonical spelling —
`readFile("")` = 12 characters — so the author's argument survived past the
rewritten call and **the generated source was not valid Kotlin**. `ScriptedMappedCall`
now carries `sourceLength`, the real character extent of the call expression, and
the replacement uses it. `readFile`/`fileExists`/`isUnix`/`pwd` now rewrite over
their true span.

The empty *payload* for `readFile`/`fileExists` is **not** fixed here and is marked
as S4-A2 territory in the code. Fixing it needs exact PSI argument matching, which
is S4-B2; pretending otherwise is what produced the invalid Kotlin in the first
place.

### 2.3 The artifact identity ignored the compiled artifact

`ScriptedRegistryCall` now carries `definitionDigest` from the compiled artifact
identity into the `OperationInput`, so the durable fingerprint is bound to it.
Without it, a scripted registry step could replay across two different compiled
artifacts occupying the same source position.

---

## 3. Three defects found *while* certifying, not before

The slice was handed over unverified. Running the gate found more than the change
itself, and all three are the same species: a declaration that no code honoured.

### 3.1 The version of a removed method was still advertised

`FACADE_SCHEMA_VERSION` read `facade-r4-pwd-readFile-fileExists-shReturnStdout-v1`
— naming `shReturnStdout`, a method this slice **removed**. Its own KDoc is
unambiguous:

> Bump when the generated call shape or the facade contract changes
> incompatibly: artifacts compiled against an older schema must never be silently
> reusable (user law 7).

Three incompatible changes to the generated call shape happened at once (eager `sh`
now rewritten at all; `shReturnStdout(id, script, null)` → `sh(id, script,
ReturnStdout, null, null)`; identity gained its return mode). Leaving the constant
at `v1` would have let an artifact compiled against a call shape that cannot be
reused present the same `facadeSchemaDigest` as one compiled against this one — a
declared compatibility dimension with no discriminating power. Bumped to
`facade-r4-pwd-readFile-fileExists-shSpine-v2`.

This is the `DomainEvent`-four-pins / `PLUGIN_LOCK_DIGEST` class recorded in S4-A0
§3.6: a field that exists and is filled with a constant.

### 3.2 A test pinned the old identity and would have been "fixed" by weakening it

`ScriptedSourceLocationTest` asserted `…:12:7:sh`. The honest update is not to accept
the new string mechanically. The law the change introduces is *"the return mode is
part of the durable identity"*, so the test now asserts that as a **property over
the closed enum** — every mode at one position yields a distinct identity, and each
is deterministic — rather than three more expected strings. A fourth mode added
later is then covered by construction instead of falling out of the assertion.

### 3.3 A fitness test was passing vacuously

`WULpr402RuntimeHonestDslFitnessTest` listed `"override suspend fun shReturnStdout("`
among the façades whose bodies must not touch a codec. The method no longer exists,
so `extractFunctionBody` returned blank and the entry was **silently filtered out**
— the law was being checked for four façades and not the fifth. The entry now names
the surviving `sh`, and the test asserts it inspected all five, so a vanished
override fails the fitness instead of quietly removing it from the check.

---

## 4. What remains open, unchanged

| Defect (S4-A0) | State | Destination |
|---|---|---|
| §3.1 `readFile`/`fileExists` empty payload | **open** — span fixed, payload not; marked in code | S4-A2 / S4-B2 |
| §3.4 unchecked `raw as JsonPrimitive` in `restoredOutput` | **open** — `ScriptedRegistryInvoker.kt:301`; a persisted `JsonObject`/`JsonArray` still escapes as `ClassCastException` instead of a typed `FailureKind` | S4-C4 |
| §3.5 `Unstable` cannot cross the registry seam | **open question**, not a proven defect | S4-C |
| §3.6 `PLUGIN_LOCK_DIGEST` is a constant | **open** | S4-C5 |
| §3.3 invocation ordinal is a count, not a position | **constraint**, not a proven defect | S4-C2 |

`JournaledScriptedOperationRuntime` remains `LEGACY_UNREACHABLE` from production,
exercised only by `ScriptedScopeTest` through an injected call-site provider.

---

## 5. Fitness that proves the change, not the implementation

- `S4A1ScriptedShellPathPrivilegeCanaryTest` (4) — counts call sites of
  `ShExecution.invokeShell` across every production source and requires exactly
  one. All four tests were proven non-vacuous in `a272990c` by reintroduction
  mutations (2/1/1 RED).
- `S4A0ScriptedLoweringCharacterizationTest` (11) — the three pinned
  `CHARACTERIZED DEFECT` assertions are **rewritten to assert correct behaviour**,
  which is the signal S4-A0 §3 prescribes: a characterization goes RED the day
  someone fixes it, and that RED is the trigger to rewrite it.
- `S4A0ScriptedRestorePathCharacterizationTest` (3) and
  `S4A0ScriptedUnstableOutcomeCharacterizationTest` (3) — untouched, still pinning
  the open defects in §4.
- `S4A1ScriptedShellReachabilityMeasurementTest` (5) — the measurement that made the
  bypass urgent.

---

## 6. What is deliberately NOT claimed

- **No CI.** `.github/workflows/` is empty since `754ddda0`; `gh run list` returns
  only Dependabot jobs. "CI green" is not an available evidence class here, and
  neither `PASS` nor `NOT_RUN` may be inferred from its absence.
- **No UAT against an installed distribution.** The gate is the local `check`
  lifecycle over sources and fixtures. HF2 (forked real distribution) coverage
  exists in the suite, but no scripted-shell UAT was executed against a built
  `distZip` for this slice.
- **This is a STEP-CERT, not a PRODUCT-GATE.** Per `CERTIFICATION_PROTOCOL.md` §4
  the two gates are separate; a new Step-CERT does not turn the Product-Gate green.
- **The evidence is bound to a tree fingerprint, not a SHA.** It becomes SHA-bound
  at the commit that follows. Until then it does not certify a commit.

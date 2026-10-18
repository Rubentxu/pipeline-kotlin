# H3 — Credentials store path: one authority

**Commits:** `769f3538` (fix), `5c925a3c` (harness hermeticity)
**Measured on:** 2026-10-18, tree clean at those commits
**Verdict:** CLOSED. Both the product defect and the harness defect it exposed are fixed and mutation-attributed.

---

## 1. The defect

Three readers decided where the credentials store lives. Two of them were
verbatim copies of the third, and the copies had drifted.

| Reader | Resolution |
| --- | --- |
| `MainCredentialsCli.resolveStoreFile()` | `PIPELINE_CREDENTIALS_STORE`, else `~/.pipeline/credentials.bin` |
| `composeWithCredentialsExecutor()` (durable branch) | `PIPELINE_CREDENTIALS_STORE`, else `<controlRoot>/../credentials.bin` |
| `Main.kt`, in-memory branch (same rule, copied) | `PIPELINE_CREDENTIALS_STORE`, else `<controlRoot>/../credentials.bin` |

The documented flow is `pipelinek credentials add` followed by `pipelinek run`.
The CLI has no control root in scope, so it can only write the user-home
default. A run without `--control-root` creates a **fresh temporary directory
per run** (`Files.createTempDirectory` in `Main.kt`), so the runtime default
resolved to `/tmp/credentials.bin`.

**Consequence.** The run never saw the store that had just been written, and it
said nothing: the "store absent" branch returns `null` by design. That is a
**silent no-op on a credentials path** (Semantic Constitution §2).

**Why it survived.** No live test pinned the control-root default. The one
guard that looked sufficient (`CredentialsCliAddPlaceholderRegressionTest`,
lines 71-86) asserted the CLI side against the CLI's own default re-derived in
the test, and its comment asserted that the runtime resolved the same one — a
condition that was false at HEAD.

## 2. The fix, as a reduction

`CredentialsStorePath` (in `pipeline-credentials-local`) is the ONE authority:

- one pure, total function — no I/O, no filesystem probe;
- a blank env value is **absent**, matching the `PIPELINEK_SCRIPT_CACHE_DIR`
  precedent, so `PIPELINE_CREDENTIALS_STORE=` cannot redirect the store into the
  working directory;
- the duplicated block in `Main.kt` is **deleted**, not rewritten;
- `composeWithCredentialsExecutor` **loses the `controlDirRoot` parameter**.
  That parameter is what made the defect expressible: with it in scope,
  `controlDirRoot.parent.resolve(...)` is a one-line change that compiles,
  passes every unit row, and restores the bug.

**Declared behaviour change.** The control-root-derived default is gone. A run
that previously read `/tmp/credentials.bin` now reads the file the CLI writes.
A user with a store present but no passphrase available now gets exit 3 with an
actionable stderr hint instead of a silently credential-less run. That is the
KDoc's declared intent ("store exists + passphrase missing -> fail fast"), and
the alternative — silently running without credentials a pipeline declared — is
the worse failure.

## 3. Evidence

All three runs used `--rerun-tasks` with the target XML deleted beforehand, so
the canary proves execution rather than a restored cache entry.

| Run | Scope | Result | XML timestamp |
| --- | --- | --- | --- |
| 1 | 11 new rows (5 pure + 2 + 3 fitness, one class each) | 11 pass, 0 fail | 16:12:30 / 16:12:33 / 16:13:24 Z |
| 2 | `:pipeline-credentials-local:test` (full module) + `:pipeline-application:test --tests '*Credential*'` | 14 classes / 79 tests, 0 fail, 0 skip | 16:12 |
| 3 | `:pipeline-application:test --tests '…UatLocal008CredentialsTest'` | 27 tests, 0 fail, 1 skip (`CR-BD-034`) | 16:23:15 Z |

Zero `^e:` compile errors in every run.

## 4. Mutations, attributed 1:1 to the rows that own the claim

Both mutations were verified **applied** (grep) before their result was read,
and both restorations verified by SHA-256.

| Mutation | Rows killed | Rows left green | Restore hash |
| --- | --- | --- | --- |
| M1 — restore the historical `<controlRoot>/../credentials.bin` resolution | 3/3 application fitness rows, with the expected messages | — | `ca94a07a…`, `41b0923a…` |
| M2 — restore the CLI-local `System.getenv` read | exactly the 2 CLI-reader rows | the 5 pure-function rows | `81a35251…` |

M2 leaving the 5 pure-function rows green is the **correct** attribution, not a
gap: those rows test `CredentialsStorePath.resolve` directly and are
independent of who calls it. A mutation that is expected to leave a claim alone
and does is evidence the claims are separated.

## 5. The regression this fix produced, and why it was the harness's

Run 2 was **RED**: 4 failures in `UatLocal008CredentialsTest`, all
`No RunFinished event in output`. Three of the four do not involve credentials
at all.

**Base-vs-head, measured** (the change stashed, same class, canary deleted):

| State | Result |
| --- | --- |
| Base `871c2c2b` (H3 stashed) | 27 tests, **0 failures**, 1 skip |
| Head (H3 applied, before the harness fix) | 27 tests, **4 failures**, 1 skip |

**Cause.** `~/.pipeline/credentials.bin` **exists on this machine** (141 bytes).
`runPipeline` called `StrictCliRun.text` with no `environment`, and
`OwnedSubprocess` **merges** that map into the inherited environment
(`builder.environment().putAll(environment)`). So the spawned run read the real
`$HOME`. The store existed, no passphrase was set and there was no TTY, so
`composeWithCredentialsExecutor` called `System.exit(3)` **before the event
stream opened**.

**The honest reading.** Those rows were green *by accident*. The old default
was `/tmp/credentials.bin`, which never existed, so the executor always
returned null. Fixing the product path removed the accident and exposed a
harness that never stated its assumption (HARNESS FIDELITY LAW §4). The fix
pins the store to a path inside the test's own fresh `controlRoot`, so "no
store" is **stated** rather than inherited. Rows that do use credentials already
pinned the variable to their fixture and are untouched.

**Correction to a normative document.** `AGENTS.md` names "four pre-existing
compatibility/UAT failures (UatLocal008 credential events, UatLocal009
archiveArtifacts)" as out of RETRY-D scope. Measured at base, `UatLocal008` is
green. That claim is stale and should not be used again as a reason to waive a
RED in that class.

## 6. Open items left deliberately open (not defects of this block)

1. **Certification-surface durability.** `retryControlJournal` is nullable at
   `CoordinatorCaps.kt:67`, `BodyExecutionEngine.kt:64`,
   `CanonicalDurableRunCoordinator.kt:105`. The only binder in the repository is
   `CompositionRoot.kt:187` (`src/main`); a census of
   `CanonicalDurableRunCoordinator(` construction sites finds **zero** in
   `src/main` and ~24 in tests, including 9 `*StepContractSuiteTest.kt` files —
   the instrument AGENTS.md names as the certification gate. Production is
   therefore durable and correct; the **test surface** is what is not. Recorded
   as an open box, not as a product defect.
2. **Environment-variable discoverability.** Three product env vars
   (`PIPELINE_CREDENTIALS_STORE`, `PIPELINE_STORE_PASSPHRASE`,
   `PIPELINEK_SCRIPT_CACHE_DIR`) are absent from `--help` and from the README.
   The natural home is a `CLI_ENVIRONMENT` constant rendered by `printUsage`,
   mirroring the `CLI_VERBS` anti-drift pattern.
3. **`System.exit(3)` inside a composition helper.** Fail-fast is correct
   behaviour, but terminating the process from a function that otherwise
   returns a value is hostile to composition and to testing. Not fixed here;
   recorded so it is not rediscovered as a surprise.

## 7. End-of-work-unit closure check

```text
Reference implementation consulted:  none applicable. No Jenkins step is
                                      involved; the rule is a product
                                      invariant (one authority), not a
                                      behavioural clone.
Behaviour adopted:                   the credentials store is located by the
                                      environment and by nothing else.
Intentional deviations:              the control-root-derived default is
                                      removed outright rather than kept as a
                                      fallback.
Security implications reviewed:      none. This change moves no secret and
                                      reads no secret; it only decides which
                                      file the existing passphrase-protected
                                      store is opened from. The passphrase
                                      handling path is untouched.
Tests demonstrating the contract:    v2/pipeline-credentials-local/src/test/kotlin/
                                        dev/rubentxu/pipeline/v2/credentials/local/
                                        CredentialsStorePathTest.kt
                                        CredentialsStorePathIsTheOnlyNameFitnessTest.kt
                                      v2/pipeline-application/src/test/kotlin/
                                        dev/rubentxu/pipeline/v2/application/
                                        CredentialsStorePathIsNotRecomputedFitnessTest.kt
```

**Ephemeral tests created during this block:** none. Every test written was
determined to protect a guarantee without equivalent coverage, so all were kept.
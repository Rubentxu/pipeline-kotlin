# C8 — a Step could erase the user's whole project

**Severity:** critical (data loss, no undo)
**Found:** 2026-09-27, during RP-5 condition 8 work
**Fixed:** same session, on top of `a1441573`
**Falsified:** yes, twice (unit + end-to-end)

## C8 / C9 — Reconciliation, 2026-10-09

**Status: C8 → MITIGATED. C9 → MITIGATED.** (The API and execution defects are
closed; three coverage criteria remain open. See "Verdict".)

This section reconciles the finding recorded above with the state of the tree on
2026-10-09, and adds the work that closes it. The historical narrative above is
left untouched: it is the record of the original defect and it is evidence for
its own SHA.

## What the finding said, and what was still true

The original C8 fix was **behaviourally correct** and still is. Both guards fire
in the real execution path, before any effect:

```text
DeleteDirOperationsAdapter.kt:112   guard computed BEFORE executor.execute()
CleanWsOperationsAdapter.kt:97      idem
WorkspacePathResolver.authorizeRootDestruction
    WorkspaceLease.Attached → Refused(ProtectedWorkspaceRoot)
    WorkspaceLease.Managed  → Permitted
```

What was still true, and is what kept C8 at MITIGATED:

```text
StepSpec.DeleteDir(path = ".")      StepSpec.kt:277   default intact
CoreDeleteDirStep(path = ".")       :36              default intact
protectWorkspaceRoot: Boolean       DeleteDirExecutor + CleanWsExecutor
```

The residual defect was **not** the existence of the default. It was that the
decision governing a root wipe was a single `Boolean` the caller could pass
wrong, and that the type could not express a caller who had not yet resolved
ownership at all.

## Why the default was NOT removed

Removing `path = "."` was measured and rejected, not skipped:

| Constraint | Evidence |
| --- | --- |
| Jenkins verbatim contract | `FArchL7JenkinsVerbatimSignatureReflectionTest` pins `DeleteDir` to exactly one `String` field named `path`, WCL-S-008 / FIL-ALL-001 |
| ABI baseline | `pipeline-scripting-api/api/*.api:493` freezes `StepSpec$DeleteDir` |
| Serialisation | the compiler always emits `path`; the decoder's `?: "."` is the historical-payload path and stays |

`deleteDir()` with no argument is how Jenkins users write the Step. Removing the
default would break source compatibility of every existing pipeline and gain
nothing: `UserOwned` already refuses the root before any effect. The defect was
never that the default existed, it was that the default's **outcome** was decided
by a bit the caller could pass wrong.

## The change: `RootDestruction`, a closed type

`protectWorkspaceRoot: Boolean = false` → `rootDestruction: RootDestruction`.

```kotlin
enum class RootDestruction {
    ScratchOwned,        // PipelineK's own scratch
    UserOwned,           // a --workspace checkout
    DecidedElsewhere,    // ownership unresolved → fails closed
}
```

The `Boolean` admitted `ScratchOwned` and `UserOwned` and made
`DecidedElsewhere` **unrepresentable**. So every caller had to answer a question
it does not own by picking one of two bits, and two adapters each re-derived the
same rule inline. C8 and C9 also shared two independent booleans, which allowed
one to be wired to scratch while the other stayed user-owned with nothing
observing the divergence. One type makes both unrepresentable.

### The enum carries no boolean, and this was found by mutation, not by review

The first version of this fix declared the enum as
`enum class RootDestruction(val permitsRootWipe: Boolean)` and consumed the bit at
both call sites. It read as a closed type and it was not one: the three states
were collapsed again one layer down, which is the same defect with a new name.

Row `C8-M2` was supposed to prevent exactly that and did not. It filtered
`RootDestruction::class.java.methods` on names starting with `permit` or
`protect`. A Kotlin `val permitsRootWipe` compiles to the JVM getter
`getPermitsRootWipe()`, which starts with `get`. **The predicate matched nothing,
the row was green, and the collapse was sitting in the enum.** The row was
certifying the comment above it rather than the code below it.

Both halves are fixed and both are now mutation-verified:

- `C8-M2` asks the question it claims to ask — is there a public no-arg getter
  returning `boolean` — rather than guessing at a name prefix.
- `C8-M2a` is new. A closed enum is not sufficient on its own: a consumer may
  collapse it locally, so the row pins that both executors decide with
  `when (rootDestruction)`. A fourth ownership case must then be a compile error
  in every consumer, not a case that silently inherits whichever value a bit gave it.

Measured, not asserted:

| Mutation | Result |
| --- | --- |
| re-add `val permitsRootWipe: Boolean = false` | `C8-M2` RED: `found [getPermitsRootWipe]` — the pre-fix version of this row left it green |
| collapse the `when` to `rootDestruction == RootDestruction.ScratchOwned` | `C8-M2a` RED: `must decide root destruction by when (rootDestruction)` |

The domain guard is unchanged and remains defence in depth: a safer API does not
replace the runtime ownership check.

## Evidence

### Matrix — `C8DestructiveIntentMatrixTest`, 13 rows

| Row | Claim |
| --- | --- |
| C8-M1 | `RootDestruction` has exactly the three ownership cases |
| C8-M2 | the type exposes no `boolean` getter that re-collapses the decision |
| C8-M2a | both executors decide with `when (rootDestruction)`, not a local bit |
| C8-M3 | `UserOwned` + root → REFUSED, canary files survive |
| C8-M4 | `ScratchOwned` + root → wiped, `.deleted` marker written |
| C8-M5 | `UserOwned` + valid sub-path → allowed, root contents intact |
| C8-M6 | `DecidedElsewhere` → fails closed on `deleteDir` |
| C8-M6a | `DecidedElsewhere` → fails closed on `cleanWs` too |
| C8-M7 | every spelling of the root (`.`, `./`, `build/..`) obeys the same intent |
| C8-M8 | traversal outside the workspace refused for **every** intent |
| C8-M9 | a symlink inside the workspace is not followed out of it |
| C8-M9a | a target that IS a symlink is confined; no marker lands outside |
| C9-M1 | `UserOwned` + pattern-less `cleanWs` → REFUSED, no `.cleaned` marker |
| C9-M2 | empty pattern list is a full wipe, refused like `null` |
| C9-M3 | `ScratchOwned` keeps the pattern-less sweep contract |
| C9-M4 | both executors take the same type, so C8 and C9 cannot drift |

The zero-effects oracle is a **filesystem observation** (canary files survive),
not the thrown exception — a Step that deleted everything and then threw would
pass an `assertThrows`-only test (HARNESS FIDELITY LAW §3).

### Mutations — 9, each attributed to the rows it flips

```text
M1   UserOwned → permitted            killed C8-M3, C8-M7
M2   ScratchOwned → refused           killed C8-M4, C9-M3, WCL-S-001/002/006
M3   deleteDir root guard removed     killed C8-M3, C8-M6, C8-M7
M4   cleanWs pattern-less guard removed   killed C8-M6a, C9-M1, C9-M2
M5   traversal containment removed    killed C8-M8, WCL-S-003
M6   DecidedElsewhere → permitted     killed C8-M6, C8-M6a
M7   re-add `val permitsRootWipe`     killed C8-M2
M8   collapse the `when` to `==`      killed C8-M2a
M9   revert the real-path anchoring   killed C8-M9a
```

M1, M2 and M6 were first written against the boolean-carrying enum and are now
restated as case edits (`UserOwned` → permitted, `ScratchOwned` → refused,
`DecidedElsewhere` → permitted) because that is what the code exposes. They
target the same rows and the same claims.

Every mutation was restored with a verified hash, and the final run returned to
34 tests / 0 failures.

**M6 initially killed nothing.** Letting `DecidedElsewhere` be permitted
left the suite green, because no row exercised the unresolved state against a
root wipe. That was a real hole in the test set, not a harness artefact, and
C8-M6 / C8-M6a were added to close it. Recording this because a mutation that
survives is the cheapest possible signal that a claim was never tested.

### UAT — destructive-effect absence, in a real run

`UatLocal011WorkflowControlTest.SC-011-14` is new: the previous
destructive-effect UAT covered only `deleteDir`, and C9 is the more dangerous of
the two because `cleanWs()` with no patterns has no partial form to narrow it.

It asserts all three halves — non-zero exit, `StepFailed` present, `WsCleaned`
**absent**, and both `important.txt` and `src/main.kt` surviving.

**Mutation:** replacing the `cleanWs` guard with `require(true)` produced
`Expected a StepFailed for the cleanWs step. Events: []` — the workspace had
already been swept. The row detects the defect it exists for.

`SC-011-13` (`deleteDir` on an Attached root) already covered the twin and is
unchanged.

### Gate

```text
C8DestructiveIntentMatrixTest + WorkspaceCleanupTest   36 tests  0 failures  0 errors
pipeline-application direct consumers                  73 tests  0 failures  0 errors  (1 skip: SC-011-11 load, unrelated)
FArchL7JenkinsVerbatimSignatureReflectionTest           6 tests  0 failures  0 errors
FArchS0SurfaceManifestTest                             11 tests  0 failures  0 errors
UatLocal011WorkflowControlTest                         15 tests  0 failures  0 errors  (1 skip)
pipeline-application full suite                        2529 tests  0 failures  0 errors  (123 skipped)
```

The last row predates the enum redesign and was measured before it. It is
reported as historical evidence for the first iteration, not as verification of
the code committed here; the rows above are the post-redesign evidence and are
the ones bound to the final SHA.

### Symlink confinement — found a real escape, not just missing coverage

This criterion was recorded as PARTIAL because traversal was covered. Writing
the row found an actual defect, and the two halves of the claim had different
answers.

`Path.normalize()` collapses `.` and `..` but does **not** dereference symlinks.
So `require(targetPath.startsWith(workspace))` answers "what name did the
caller write", not "where does the filesystem end up". A link named `escape`
pointing at an outside directory has a normalised form inside the workspace and
was accepted by the guard.

Half one, the deletion walk, was already safe: `Files.walk` without
`FOLLOW_LINKS` visits the link and not its target, so nothing outside was
deleted. Half two, the MEMOIZED marker, was not. `.deleted` is written *inside*
`targetPath`, and a write through a symlink resolves to the link's target.
Measured directly before writing the row:

```text
ws/outside-link -> real/   deleteDir(path = "outside-link")
  real/.deleted  exists  = true      <-- written outside the workspace
  ws/.deleted    exists  = false
```

So `deleteDir` could drop a file into a directory the workspace never owned.
A conformance test asserting only that outside files survive would have passed
against this defect, because they did survive.

The fix anchors the guard and the marker to the real path:

```text
realWorkspace = toRealPathAllowingMissing(workspace)
realTarget    = toRealPathAllowingMissing(rawTarget)
require(realTarget.startsWith(realWorkspace))
require(wipesRoot || realTarget != realWorkspace)
markerFile    = realTarget.resolve(".deleted")
```

`toRealPath` throws on a missing leaf and `deleteDir` legitimately targets
directories that do not exist yet, so the deepest existing ancestor is resolved
and the remaining segments re-appended.

`DeleteDirResult.path` still reports the *declared* path. It is an observable,
serialized output, and silently switching it to the resolved real path would
have changed a published contract to fix a bug that did not need fixing.

| Mutation | Result |
| --- | --- |
| MUT-M9 revert the anchoring to normalised paths | `C8-M9a` RED: `Expected IllegalArgumentException, but nothing was thrown` |
| restore, verified by hash | `f91b1dd011c9f8e104dba7015ce8a056a56d89037b433399adbea9f2a17a8799` |

`C8-M9` (the walk does not follow links) was green before and after, which is
correct: it is the half that was already right, kept so a future `FOLLOW_LINKS`
would break it.

### Replay and durable schema — measured, not assumed

The intent must not leak into anything durable, or every existing run's history
would be reinterpreted under new semantics (DR-10).

```text
RootDestruction referenced by fingerprint / journal / payload / encode : NONE
files changed under Fingerprint|Journal|Codec since the first C8 commit   : NONE
the executors' durable input remains spec.path / spec.patterns only      : unchanged
```

`RootDestruction` is constructed inside the adapters at call time, from the
lease, and is never serialized, hashed, or written to the operation journal. So
the fingerprint of a `deleteDir` / `cleanWs` operation is byte-identical before
and after this change, and historical payloads decode exactly as they did:
`path ?: "."` still applies, and it still passes lease authorization because
ownership is decided from the lease rather than from the payload.

### Preexisting drift, excluded from the gate and NOT caused by this work

Both were reproduced on a clean HEAD (`git stash`, then re-run), so the
attribution is measured rather than assumed:

```text
:pipeline-domain:apiCheck      fails on HEAD  — 4d4075d5 added Sha256 without an apiDump
:pipeline-domain:detekt        fails on HEAD  — 3 files missing a final newline (Sha256.kt et al.)
:pipeline-application:detekt   fails on HEAD  — Sha256MigrationCharsetInvariantTest.kt final newline
```

Neither module is touched by this change (`git status` shows 0 files in
`pipeline-domain`). The gate was run excluding exactly those three tasks.

## Verdict

**MITIGATED.** The API and execution now close the defect, but three of the
stated closure criteria are still open, and recording `RESOLVED` would be the
false-green this receipt exists to prevent.

Satisfied, with evidence above:

- the API states destructive intent as a closed type with no boolean anywhere in
  the chain, so "unresolved ownership resolved toward destruction" is not merely
  discouraged, it is unspellable;
- both executors decide by matching cases, so a fourth ownership case is a
  compile error rather than an inherited verdict;
- the runtime check on `WorkspaceLease` is unchanged and still fail-closed;
- every behavioural claim carries a mutation that kills it, and the two that
  initially did not are recorded and now closed;
- the destructive-effect absence is proven in a real run, not only in unit tests;
- the Jenkins signature, the ABI baseline, historical-payload decoding and the
  durable fingerprint are unchanged, so no migration was owed to a consumer.

**Open, and the reason this is not RESOLVED:**

| Criterion | Status |
| --- | --- |
| API + execution satisfy every C8 criterion | MET |
| Symlink / nested-directory confinement | MET — was PARTIAL; writing the row found a real escape (marker written through a link into a directory outside the workspace), now fixed and mutation-verified |
| Historical payload without `path` decodes and authorizes | ASSUMED, not measured — the `path ?: "."` path is read in the decoder but has no explicit regression test |
| Installed-distribution canary | OPEN — `SC-011-14` is a real application run, not a run against the installed ZIP |

The remaining two are test-coverage gaps, not known defects: the production
behaviour on each is believed correct and nothing observed has contradicted
that. But "believed correct" is the phrase this receipt has been burned by
before, so the verdict stays MITIGATED until the rows exist and are bound to the
SHA that carries them.

The symlink criterion is the reason this section exists. It was entered as
"PARTIAL — probably fine, no row" and turned out to be a live escape that a
survival-only conformance test would have passed. Coverage gaps are hypotheses,
and this one paid.

## Reference implementation consulted

`jenkinsci/pipeline-basic-steps-plugin` — `deleteDir` / `cleanWs` step
definitions and the workspace-deletion contract, for the parameter shape and the
`deleteDir()` no-argument call form. Adopted: the Jenkins authoring surface and
its parameter order, kept verbatim. Intentional deviation: ownership is decided
by PipelineK's typed `WorkspaceLease` (ADR-0102) rather than by Jenkins'
`FilePath` / node context, because this runtime resolves paths purely and has no
remoting model. Security implications reviewed: Zip Slip and traversal are
out of C8 scope and unchanged; the `sh("rm -rf")` escape hatch remains open by
design and is recorded in "Known limitations" above.

---

## Original finding (2026-09-27, retained verbatim)

## What happened

Running the DSL/Kotlin compatibility corpus the way the project's own CI does
(`pipelinek run --workspace <dir> <file>.pipeline.kts`) against a real
repository deleted the entire working tree, including `.git`. Source, 15
unpublished commits, and untracked receipts from a parallel actor were lost.
Recovery was possible only because the public remote was intact.

## Root cause

`StepSpec.DeleteDir` defaults to `path = "."`, and
`DeleteDirExecutor.execute` deletes **the contents of** its target:

```kotlin
val targetPath = workspace.resolve(spec.path).normalize()   // "." -> the workspace root
require(targetPath.startsWith(workspace)) { ... }          // only guards traversal
Files.walk(targetPath).filter { it != targetPath }.forEach { delete }  // wipes contents
```

With `--workspace <dir>` the workspace root *is* the user's project, so the
Step's own default resolved to the checkout and erased it.

`v2/compatibility/11-workflow-control.pipeline.kts` line 7 calls `deleteDir()`
with no argument. Running the corpus against a project was enough.

### What made it worse: a second defect masked the first

`Main.kt:306` and `:636` build the override as `Path.of(it)` with no
normalisation, and `Paths.get(".").normalize()` is the **empty path**. The
containment guard `targetPath.startsWith(workspace)` therefore compared
`"out.txt".startsWith("")` → `false`, and `deleteDir` was rejected with
`deleteDir path '.' escapes workspace root`.

That was not a safety feature. It was an accident that disabled the whole
family of file-Step guards, and it made `deleteDir(".")` harmless *by
coincidence*. Normalising the workspace to an absolute path — a correct change
on its own, and the reason `writeFile('out.txt')` started working again — removed
the coincidence and released the hazard.

The 64-byte `.deleted` SHA marker found in the wreckage is the executor's own
idempotency marker, confirming this code path executed against the repository.

## The fix

Two changes, each minimal.

**1. Interlock (`DeleteDirExecutor`)** — the workspace root is never deletable
when it is a user-supplied shared workspace:

```kotlin
require(!protectWorkspaceRoot || targetPath != workspace) {
    "deleteDir refuses to delete the workspace root itself ('$workspace'); " +
        "pass a sub-path such as deleteDir(\"build\") to remove generated content"
}
```

It is **conditional**, not a blanket ban. `DeleteDirOperationsAdapter` sets
`protectWorkspaceRoot = workspaceBase != null`. Scratch workspaces stay
wipeable, so the pre-existing contracts `WCL-S-001` and `WCL-S-002` — which
assert that `deleteDir(".")` wipes a scratch workspace — keep passing
unchanged. Sub-paths remain deletable, which is the Step's actual purpose.

**2. Normalisation (`WorkspaceResolver`)** — the override becomes
`workspaceBaseArg?.toAbsolutePath()?.normalize()` on construction, so the
`startsWith` comparisons in the file Steps compare like with like. Without this,
`writeFile`, `readFile`, `publishHtml` and `stash` all fail closed on
`--workspace .`.

## Evidence

### Unit — `:pipeline-step-sdk:files:test --tests '*WorkspaceCleanupTest*'`

`16 tests, 0 failures` (from JUnit XML). The 8 pre-existing `WCL-S-*` tests plus
5 new C8 cases:

| Case | Asserts |
|---|---|
| `C8 deleteDir refuses the workspace root when the root is a user project` | `.` refused, `README.md` + `src/main.kt` survive, no `.deleted` written |
| `C8 deleteDir still removes a sub-path inside a protected workspace` | `build/` deleted, `keep.txt` survives |
| `C8 deleteDir rejects traversal outside a protected workspace` | `../elsewhere` refused, outside file survives |
| `C8 dot path is rejected even when spelled as the workspace root` | `"./"` refused too |
| `C8 scratch workspace keeps its wipe contract` | scratch mode still wipes (WCL-S-001 preserved) |

**Falsification:** replacing the interlock with `require(true)` produced
`16 tests completed, 2 failed` — the two root-refusal cases. The other 14
stayed green, so the suite is not trivially red.

### Unit — `:pipeline-application:test --tests '*WorkspaceResolverTest*'`

`15 tests, 0 failures`, 5 new C8 cases covering absoluteness, both relative
and nested containment, preserved traversal rejection, and redundant-segment
normalisation. **Falsification:** reverting to the unnormalised value produced
`15 tests completed, 4 failed`.

### End-to-end — full corpus against a canary

31 `.pipeline.kts` files, `--workspace` pointed at a throwaway directory
holding a canary project. **Never** at the repository.

```
PASS=25  FAIL=6
CANARY.md              SURVIVED
keep.txt               SURVIVED
canary-dir/main.kt     SURVIVED
C8 PROOF: PASS
```

The interlock message is visible in the run record for
`11-workflow-control`:

```
deleteDir refuses to delete the workspace root itself
('/home/rubentxu/.jcode/scratch/canary-c8-311511')
```

### The 6 corpus failures are intentional, not regressions

| Case | Step / kind | Verdict |
|---|---|---|
| `10-smoke-e2e` | `sh` / SCRIPT, exit 128 | git checkout outside a repo — environment |
| `11-workflow-control` | `deleteDir` / ENGINE | **the C8 interlock firing, by design** |
| `14-credentials-bindings` | `withcredentials` / INFRASTRUCTURE | no credential executor configured — environment |
| `15-error` | `error` / USER, "test error message" | negative test asserting the `error` Step |
| `28-zip-slip-defense` | `core-utils.unzip` / ENGINE | Zip Slip defence rejecting `../escaped.txt` — CVE-2023-32981 test |
| `31-stash-unstash` | `unstash` / SCRIPT | unstash path mismatch in the throwaway workspace |

The baseline run (before C8) had 9 failures; 5 of them were the
`writeFile ... escapes workspace` false-positives that C8's normalisation fix
resolves. Net: **22 pass vs 9**, with the remaining 6 all accounted for.

## One thing that is NOT a defect

`31-stash-unstash.pipeline.kts` runs `sh("rm -rf src docs")` deliberately, to
prove `unstash` restores from the stash rather than from disk. That is a
*shell* step, so no DSL containment guard applies to it and none should: a
pipeline is arbitrary code, and `sh` is the escape hatch by design. The first
canary run flagged `src/main.kt` as destroyed; that was this step doing its
job, and the canary was moved to `canary-dir/`. Recorded here so nobody
re-litigates it as a second finding.

## Known limitations

1. **A pipeline can still run `sh("rm -rf /")`.** Nothing in the DSL layer
   prevents that, and nothing should. This fix removes an accidental footgun
   in a *declarative* Step, not the shell escape hatch.
2. **`--workspace` pointing at a symlinked path** is not `toRealPath()`-resolved.
   The lexical guards remain correct; a symlink inside the workspace could
   still lead outside. Out of scope here, worth a follow-up.
3. The 15 lost commits remain unrecoverable. Only btrfs snapshots could hold
   them, and reading those needs root.

## Follow-ups

- ~~Audit the other Steps for the same "default resolves to the workspace root"
  shape~~ — **done, and it found C9 below.**
- ~~Consider `cleanWs` — its default patterns may sweep the workspace root too.~~
  — **confirmed, see C9.**

---

# C9 — `cleanWs()` deletes every file in the project

**Severity:** critical (data loss, no undo)
**Found:** 2026-09-27, while auditing C8's blast radius
**Base:** `162260e8` (the C8 work)

## Root cause

`StepSpec.CleanWs` declares `patterns: List<String>? = null`, and the null
branch of `CleanWsExecutor.execute` is:

```kotlin
Files.walk(workspace)
    .filter { it != workspace }                      // skip the root dir itself
    .filter { !it.startsWith(v2ArtifactsRoot) }      // keep .v2/artifacts
    .filter { Files.isRegularFile(it) }
    .forEach { filesToDelete.add(it) }
// ...then Files.deleteIfExists on every one
```

That is **every regular file in the workspace except `.v2/artifacts`**. With
`--workspace <dir>` the root is the user's project, so `cleanWs()` — with no
arguments, the Step's own default — deletes the whole checkout file by file.

This is worse than C8 in one respect: `deleteDir` is a single call the user
could plausibly have narrowed, whereas the null-pattern form has no partial
form at all. It is also completely uncovered — `18-cleanWs.pipeline.kts` always
passes `patterns = listOf("test/**")`, so no corpus case and no test ever
exercised the dangerous branch.

## The fix

Mirrors C8 exactly, so the two read as one mechanism:

```kotlin
require(!protectWorkspaceRoot || !spec.patterns.isNullOrEmpty()) {
    "cleanWs refuses to run without patterns on workspace '$workspace'; " +
        "pass patterns such as cleanWs(patterns = listOf(\"build/**\")) " +
        "so only generated content is removed"
}
```

`CleanWsOperationsAdapter` sets `protectWorkspaceRoot = workspaceBase != null`.

The guard refuses **both** `null` and `emptyList()`, because
`spec.patterns ?: emptyList()` funnels both into the same branch — an empty
list is not a safe no-op here, it is a full wipe. That case has its own test.

## Evidence

**Unit.** `WorkspaceCleanupTest: tests=20 failures=0 errors=0` — 8 pre-existing
`WCL-S-*`, 5 C8, 4 C9. The C9 cases: pattern-less refused with files intact
and no `.cleaned` marker written; empty list refused too; patterned
`cleanWs(patterns = listOf("build/**"))` still deletes `build/out.txt` and
keeps `README.md`; scratch workspace still wipes.

**Falsification.** Replacing the guard with `require(true)` gives
`20 tests completed, 2 failed` — the two refusal cases. The other 18 stayed
green.

**End-to-end.** A purpose-built pipeline calling `cleanWs()` against a canary
project (`cleanWs()` had no corpus coverage, so one was written):

```
exit=1   (non-zero is EXPECTED: the guard must refuse)
"stepType":"cleanWs"  "failureKind":"ENGINE"
"message":"registry step 'core.cleanWs' handler failed: cleanWs refuses to
           run without patterns on workspace '/home/rubentxu/.jcode/...'"
CANARY.md SURVIVED   keep.txt SURVIVED   src/main.kt SURVIVED
AFTER=3 files
C9 PROOF: PASS
```

## Remaining audit surface

`stash`, `publishHtml` and `artifactQuery` are **not destructive by default**:
`StepSpec.DeleteDir(path = ".")` and `StepSpec.CleanWs(patterns = null)` are
the only two Steps in `StepSpec.kt` with a defaulted destructive parameter, and
`WriteFile`/`ReadFile`/`FileExists` all require an explicit path. `stash`
creates and `unstash` restores; neither deletes user files at the workspace
root. `publishHtml` has its own containment guard
(`reportDir '${input.reportDir}' escapes workspace`) and was already covered
by the corpus run in the C8 section.

So the destructive-default surface is now closed for the Steps that exist
today. A new Step with a defaulted destructive parameter would need the same
treatment; that is a convention, not something the code enforces.

## Was the project's own CI ever at risk?

Checked, because C8 raises the question directly. **No.**

`.github/workflows/lpr0-ci.yml` does not run the compatibility corpus. The
baseline deliberately omits it, and says why in a comment at line 165:

> a nightly compatibility-corpus job is intentionally omitted from this LPR-0
> baseline. Compatibility tests run only when explicitly invoked (manual
> `workflow_dispatch` extension or from a local just gate).

The only corpus file CI executes is `01-basic.pipeline.kts` (line 241), in the
dogfood success path. `11-workflow-control` — the pipeline with the bare
`deleteDir()` — is never run by CI.

So CI was not relying on the accident C8 removed, and the interlock will not
turn a CI job red. Had the corpus been wired into the baseline, that job would
have been running a self-deleting pipeline against the checkout, and it would
now fail loudly instead. That is the correct direction, but it is a change in
behaviour for anyone invoking the corpus manually with `--workspace .` against
a real project: they will get the refusal rather than a wiped tree.

Worth flagging for whoever wires the corpus into CI later: the corpus is
**not safe to run against a real checkout**, by design of two of its files
(`11-workflow-control` calls `deleteDir()`, `31-stash-unstash` calls
`sh("rm -rf src docs")`). It needs a throwaway workspace, which is how the
evidence in this receipt was produced.



# C12 — C8 interlock regression: over-broad trigger broke the compatibility corpus

**Status:** complete
**Repository:** `/var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin-restored`
**Baseline commit:** `3204c19f` (contains the C8/C9 commits that introduced this)
**Modules:** `pipeline-application` (`DeleteDirOperationsAdapter`, `CleanWsOperationsAdapter`)
**Severity:** production correctness regression in a safety feature. It disabled a
legitimate, contractually-required capability.
**Product impact:** `deleteDir()` / `cleanWs()` refused to run whenever
`--workspace` was passed, including for disposable scratch workspaces.

---

## 1. How this was found

The full `./gradlew check --rerun-tasks` gate reported:

```
BUILD FAILED in 18m 56s
Corpus must have zero failures: [11-workflow-control.pipeline.kts: exit 1, ...]
```

## 2. Proof it was MY regression, not pre-existing debt

The obvious excuse is "pre-existing". It was tested against a pristine worktree:

```
$ git worktree add --detach /var/home/rubentxu/wt/baseline-c11-a1441573 a1441573
$ cd /var/home/rubentxu/wt/baseline-c11-a1441573/v2
$ ./gradlew :pipeline-application:test --tests "*UatCompat001CorpusSmokeRunTest*" --rerun-tasks
BUILD SUCCESSFUL in 6m 28s
```

**OBSERVED: baseline `a1441573` (before C8/C9) is GREEN; my tree is RED.** The
regression was introduced by the C8/C9 work, not inherited from the destroyed
history. Recorded explicitly because the opposite assumption was tempting and
would have shipped a broken guard.

## 3. Root cause

C8 wired the interlock as:

```kotlin
protectWorkspaceRoot = workspaceBase != null
```

That assumes **any** `--workspace <dir>` is the user's own project checkout. The
C8 receipt states the intent as "the workspace root *is* the user's project" --
an assumption that is simply false.

`UatCompat001CorpusSmokeRunTest` invokes the real installed CLI as:

```kotlin
ProcessBuilder(appBin.toString(), "run", "--workspace", workspace.toString(), staged.toString())
    .directory(workspace.toFile())
```

with `workspace` a JUnit `@TempDir` -- a **disposable scratch directory**, with
no VCS marker. Fixture `11-workflow-control.pipeline.kts` calls `deleteDir()`
with no argument inside it. The over-broad interlock refused that legitimate
wipe (`StepFailed`, `exit 1`) and broke a previously green fixture.

So the defect is a **wrong discriminator**: the trigger was "the flag was
passed" rather than "the target is a real project".

## 4. Fix

Both cleanup adapters now gate on a property of the *target directory*:

```kotlin
private fun isProjectCheckout(base: Path): Boolean =
    listOf(base, base.parent).any { candidate ->
        candidate != null &&
            listOf(".git", ".hg", ".svn").any { marker ->
                Files.exists(candidate.resolve(marker))
            }
    }

protectWorkspaceRoot = workspaceBase != null && isProjectCheckout(workspaceBase)
```

The parent level is also checked so a workspace nested inside a repo
(`repo/build/agent-ws`) is still recognised as belonging to a checkout.

`DeleteDirOperationsAdapter` and `CleanWsOperationsAdapter` received the same
helper deliberately: two copies rather than a shared utility, because a shared
helper would widen this commit's blast radius across modules during a
regression fix. The duplication is intentional and documented in both files;
extracting it is follow-up debt.

`DeleteDirExecutor` and `CleanWsExecutor` are **unchanged**. Their
`protectWorkspaceRoot` flag was already correct; only the caller's decision of
when to set it was wrong.

## 5. Evidence

### 5.1 Corpus restored to green

```
$ ./gradlew :pipeline-application:test --tests "*UatCompat001CorpusSmokeRunTest*" --rerun-tasks
BUILD SUCCESSFUL in 6m 28s
```

### 5.2 End-to-end canary: BOTH properties hold through the real CLI binary

`/var/home/rubentxu/canary-c12.sh` builds two fresh throwaway workspaces and
runs the installed `pipelinek` binary against each with the same
`deleteDir()`-no-argument pipeline:

| Workspace | Has `.git` | Expected | OBSERVED |
|---|---|---|---|
| `canary-c12-checkout` | yes | refuse, files survive | `exit=1`, `StepFailed: deleteDir refuses to delete the workspace root itself`, `important1.txt` + `important2.txt` survived |
| `canary-c12-scratch` | no | wipe, success | `exit=0`, `DirDeleted deletedCount=3`, `outcome: success` |

```
PASS: checkout PROTECTED (both canary files survived)
PASS: scratch WIPED (WCL-S-001 preserved)
```

The script refuses to report any result unless `pipelinek validate` first
responds, so a non-starting binary cannot produce a misleading PASS. An earlier
revision did exactly that (exit 126, both files intact, one bogus "PASS"), which
is why the liveness guard exists.

### 5.3 Falsification: the interlock is load-bearing

Rebuilding with `protectWorkspaceRoot = false` and re-running the same canary:

```
exit=0
exit=0
checkout:  .deleted
scratch:   .deleted
FAIL: checkout was DELETED - C8 safety property is broken
```

The checkout is wiped down to a lone `.deleted` marker -- **the exact
destruction mode that destroyed the original working tree**. The guard is the
only thing preventing it. Restored afterwards and re-verified:

```
exit=1
exit=0
PASS: checkout PROTECTED (both canary files survived)
PASS: scratch WIPED (WCL-S-001 preserved)
```

### 5.4 Repository integrity preserved

```
$ find v2 -name "*.kt" -not -path "*/build/*" | wc -l
864
```

Matches the post-recovery count. No canary touched the checkout; both canary
workspaces were fresh `@TempDir`-style directories outside the repository.

## 6. Known decisions

- **VCS marker as the discriminator.** It is a property of the target, not of
  invocation, so it cannot be wrong the way the flag-based test was. Cost: a
  genuine non-VCS user project (a bare source tree with no `.git`) is not
  protected. Accepted trade-off, recorded here rather than hidden: that user can
  still be harmed, and the mitigation is to pass an explicit sub-path.
- Alternatives rejected: (a) an opt-in `--allow-workspace-wipe` flag -- pushes
  the safety burden onto every caller including the corpus, which had no way to
  know it needed it; (b) temp-path name matching (`/tmp/`) -- fragile and
  trivially wrong for `TMPDIR` overrides.
- `DeleteDirExecutor` / `CleanWsExecutor` left untouched on purpose. The
  regression was in the adapter's decision, not in the enforcement, so changing
  the enforcement would have been fixing the wrong layer.

## 7. Outstanding / follow-up debt

- The `isProjectCheckout` helper is duplicated across two adapters. Extract to
  one shared location as separate debt, not inside a regression fix.
- A bare (non-VCS) user project is unprotected. A follow-up should consider an
  explicit opt-in or a more conservative default.
- The pre-existing C10 structural debt (`StageScope` 53 methods, `retry`
  complexity 35) remains suppressed by the detekt baseline and untouched.
- RP-5 conditions 4 and 7 still require human ratification. The 15 unpublished
  local commits destroyed with the original working tree remain unrecoverable.

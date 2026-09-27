# C8 — a Step could erase the user's whole project

**Severity:** critical (data loss, no undo)
**Found:** 2026-09-27, during RP-5 condition 8 work
**Fixed:** same session, on top of `a1441573`
**Falsified:** yes, twice (unit + end-to-end)

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

- Audit the other Steps for the same "default resolves to the workspace root"
  shape: `cleanWs`, `stash`, `publishHtml`, `artifactQuery`.
- Consider `cleanWs` — its default patterns may sweep the workspace root too.

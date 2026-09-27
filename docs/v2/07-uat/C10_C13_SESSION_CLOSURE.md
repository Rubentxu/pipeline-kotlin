# C10-C13 session closure — repository-wide check gate restored

Cycle: p-733fb505b5a6bd2d/train-040-gate-recovery
WorkItem: 4df0976c-316b-4431-8dbb-b62501f302b2 (status: done)
Repository: /var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin-restored
Baseline (remote): a1441573
Local HEAD: ace17d2b
Working tree: clean, 0 modified
Feature freeze: maintained, no push, no tag created or modified

## Outcome

The 25-module repository-wide gate was UNESTABLISHED at session start (it
stopped at `:pipeline-scripting-api:detekt`). It is now ESTABLISHED and
verified on the final committed HEAD:

```
$ ./gradlew check --rerun-tasks
BUILD SUCCESSFUL in 23m 28s
FAILED tasks: 0
TOTAL tests=3194   failures+errors=0
detekt findings across all modules: 0
```

Aggregated from every module's JUnit XML, not from log text. A concurrent
Gradle build in the unrelated worktree /var/home/rubentxu/wt/recover/pk writes
into the same daemon pool and its BUILD line interleaves into captured logs, so
every gate verdict in this session was taken from per-task outcomes and
test-result XML instead.

## Commits (7, all atomic, none pushed)

| SHA | Subject | Files |
|---|---|---|
| d5dcda18 | fix(files): C8 — refuse to delete the workspace root for a user project | 3 |
| 162260e8 | fix(application): C8 — normalise the --workspace override to an absolute path | 3 |
| 3204c19f | fix(files): C9 — refuse the pattern-less cleanWs form on a user project | 4 |
| 5b558aa1 | fix(scripting-api): C10 — repoint detekt baseline after the D-026 StageScope move | 5 |
| 41d31159 | test(credentials-api): C11 — make the throughput probe measure code, not the machine | 2 |
| 7719b273 | test(application): C13 — give the corpus sweeps a real timeout budget | 2 |
| ace17d2b | fix(application): C12 — gate the C8 interlock on a VCS marker, not on --workspace | 4 |

## The four defects, in discovery order

### C10 — detekt ratchet broken by the D-026 extraction (pre-existing)

Commit 4f8a05f1 extracted `StageScope` from `PipelineDsl.kt` into
`PipelineDslStageScope.kt` without regenerating the detekt baseline. detekt keys
suppressions on `file:declaration`, so both entries stopped matching and
`TooManyFunctions` (53 functions, max 25) and `CyclomaticComplexMethod`
(`retry` complexity 35) resurfaced.

Fixed by renaming to `dsl/StageScope.kt` (its single top-level declaration, so
`MatchingDeclarationName` is satisfied by construction rather than by waiver),
repointing the two baseline keys, and updating the two architecture guards that
key their contracts on production filenames.

The FArchLeg1 allowlist was missed on the first pass and surfaced only via the
full gate, because it matches the path suffix `PipelineDsl.kt` rather than the
literal filename.

Falsified: reverting only the two keys reproduces both original errors, BUILD
FAILED.

### C11 — Rp022ThroughputProbe measured the machine, not the code

One timing sample against a 20 MB/s floor. Observed 15.7 MB/s under the full
gate at load 17.54 versus 23.3 MB/s isolated at load 15.81. A concurrent build
in another worktree caused it. `StreamingRedactor` was never modified.

Fixed with best-of-3 samples. Floor UNCHANGED at 20 MB/s. Leak and no-marker
assertions now run on all 3 samples instead of one.

Falsified: floor 99999.0 -> BUILD FAILED; inverted leak assertion -> BUILD
FAILED. Both temporary edits reverted and verified absent.

### C12 — REGRESSION introduced by the C8 work in this same branch

`protectWorkspaceRoot = workspaceBase != null` assumed any `--workspace` is the
user's own project checkout. False: the corpus runs
`pipelinek run --workspace <@TempDir>` with a disposable scratch dir, and
fixture `11-workflow-control.pipeline.kts` calls `deleteDir()` there. The
over-broad interlock refused a legitimate wipe and turned a green fixture into
`exit 1`.

Proven to be MY regression rather than inherited debt by a differential against
a pristine worktree at a1441573: the baseline completed the corpus suite green
while this tree failed.

Fixed by gating on a VCS marker (`.git`/`.hg`/`.svn`, also checking the parent)
present in the target directory, applied identically to
`DeleteDirOperationsAdapter` and `CleanWsOperationsAdapter`.
`DeleteDirExecutor` and `CleanWsExecutor` were deliberately left untouched: the
defect was in the adapters' decision, not in the enforcement.

### C13 — corpus sweep timeout had no margin

`@Timeout(180)` per test against two ~170s sweeps of 30 fixtures each. The
baseline completed the 2-test suite in 337.9s with 0 failures, clearing its
budget by under 2%. C12's legitimate extra ~13s tipped it.

Both sweeps raised to `@Timeout(600)`, roughly 3x the observed requirement. No
assertion changed, no fixture skipped, no product code touched. After the fix
the suite runs in 344.1s, within 2% of baseline, confirming only the budget
changed.

## End-to-end canary for C12

`/var/home/rubentxu/canary-c12.sh` runs the installed `pipelinek` binary with
`deleteDir()` and no argument against two fresh throwaway workspaces outside the
repository:

| Workspace | Has .git | Expected | Observed |
|---|---|---|---|
| canary-c12-checkout | yes | refuse | exit=1, StepFailed "deleteDir refuses to delete the workspace root itself", both canary files survived |
| canary-c12-scratch | no | wipe | exit=0, DirDeleted deletedCount=3, outcome success |

Falsified by rebuilding with `protectWorkspaceRoot = false`: the checkout was
wiped to a lone `.deleted` marker, the exact destruction mode that destroyed
the original working tree. Restored and re-verified.

The script refuses to report any result unless `pipelinek validate` responds
first, because an earlier revision reported a PASS while the binary had not
even started (asdf could not resolve Java outside the repo).

## Repository integrity

`find v2 -name "*.kt" -not -path "*/build/*" | wc -l` = 864, matching the
post-recovery count, re-verified after every destructive canary. No canary ever
targeted a checkout.

## SDDK infrastructure defect found and fixed

`sddk cycle start` failed with `ENGINE_STORAGE: FOREIGN KEY constraint failed`.
`cycles` carries a composite FK `(project_id, workspace_id) REFERENCES
workspaces`, and no `workspaces` row existed for this restored clone
(`w-023be42ea29a9aabd311b290`) because it was never adopted.
`sddk adopt status` reported `ledger_only`; `sddk adopt apply` returned
`status: complete` and the cycle then started normally.

Same defect class as the previously recorded `sddk cycle pause` CHECK-constraint
failure: the ledger rejects writes the CLI advertises. The general class remains
unfixed upstream and is recorded as follow-up.

## SDDK routing caveat learned this session

`sddk plan roadmap next` resolves to work item `1a681dea-…` ("Framework defect:
sddk cycle pause violates its own cycle-status CHECK constraint"). That item has
zero rows in `work_item_dependencies_v1`, so the selection rule
`first_non_terminal_all_deps_terminal` matches it vacuously. It is therefore not
a reliable owner for product work, and the pre-commit gate rejects an
acknowledgement naming any other work item
(`WorkItem mismatch: agent=… SDDK=1a681dea-…`).

Workaround used, and its honest limits: the C10-C13 product work was recorded
against a real work item (`4df0976c`, now `done`) in the new cycle, while the
Git gate was acknowledged to `1a681dea` with an explicit statement that only the
SDDK storage finding advances that item and that the product changes are
tracked elsewhere. Decision `91b6cd11` on `1a681dea` records the storage
defect. A real fix would populate the dependency edges or retire the stub.

## Outstanding for the next session

Follow-up debt, all deliberately left out of this freeze work:

1. `isProjectCheckout` is duplicated across `DeleteDirOperationsAdapter` and
   `CleanWsOperationsAdapter`. Extract to one shared location. Duplication was
   intentional to keep the blast radius of a regression fix small.
2. A bare non-VCS project tree is not protected by the C12 interlock. A
   follow-up should consider an explicit opt-in or a more conservative default.
3. Pre-existing `StageScope` structural debt is still suppressed by the detekt
   baseline: 53 methods (max 25) and `retry` complexity 35 (max 25). C10 made
   this correctly tracked, not smaller. It is real debt to repay.
4. The SDDK ledger defect class (FK and CHECK constraint failures on
   advertised operations) is unfixed upstream. The specific case that this
   recovered repository hit is fixed by adoption; the general one is not.
5. `sddk plan roadmap next` selecting `1a681dea` vacuously because
   `work_item_dependencies_v1` is empty should be addressed so the pre-commit
   gate resolves to genuine work.

Still open, requires human action, not agent work:

6. RP-5 conditions 4 and 7 require human ratification.
7. The 15 unpublished local commits destroyed with the original working tree
   remain unrecoverable from GitHub. Only privileged btrfs snapshots could
   recover them.
8. Nothing has been pushed. `origin/main` is still a1441573. Seven local
   commits await explicit human authorization to push.
9. The destroyed original checkout at
   /var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin still exists and must not
   be deleted without authorization.

## Session-execution notes worth keeping

- Two of my own `check --rerun-tasks` builds ran concurrently for ~20 minutes
  because killing a `timeout` wrapper left the `setsid`-detached build alive.
  Both fought over the same daemon and build directories. Ensure exactly one
  build before trusting a gate result.
- `nohup setsid ... > log` is the reliable way to detach a build from the tool
  harness; piping to `tail` buffers all output until exit and defeats progress
  detection.
- Verify gate results from JUnit XML aggregates, never from a `BUILD` line in a
  log that may contain another build's output.
- A `pgrep -f "check --rerun-tasks"` match can return the `timeout` wrapper
  rather than the Gradle client. Check `%CPU` and elapsed CPU time to identify
  the live process.

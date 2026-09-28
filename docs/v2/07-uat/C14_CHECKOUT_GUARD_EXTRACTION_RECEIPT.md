# C14 — extract the duplicated project-checkout predicate

Cycle: p-733fb505b5a6bd2d/checkout-guard-extraction
Closes follow-up item 1 of `C10_C13_SESSION_CLOSURE.md`
Repository: /var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin-restored
Baseline (remote): a1441573
Behaviour: **unchanged**. This is a refactor, and the proof is below.

## What changed

`isProjectCheckout` was defined twice, byte-for-byte identical, as a private
method in each adapter:

- `DeleteDirOperationsAdapter.kt:62` — the original
- `CleanWsOperationsAdapter.kt:62` — a copy whose own doc comment described it
  as "mirrors the identical helper in [DeleteDirOperationsAdapter]"

That comment is the problem in miniature: it is a *claim about a code body*
that nothing enforced. A fix applied to one copy would have left the other
Step guarding a deletion differently, silently.

Both now call one definition:

- New: `durable/ProjectCheckoutDetector.kt` — `object ProjectCheckoutDetector`
- `DeleteDirOperationsAdapter` and `CleanWsOperationsAdapter` delegate to it
- New: `ProjectCheckoutDetectorTest` — 8 tests pinning the semantics

Nothing else was touched. `DeleteDirExecutor` and `CleanWsExecutor` were left
alone, as they were in C8/C9/C12: the defect was never in the enforcement.

## Evidence

### The duplication was real

```
$ sed -n '62,68p' DeleteDirOperationsAdapter.kt > a.txt
$ sed -n '62,68p' CleanWsOperationsAdapter.kt     > b.txt
$ diff a.txt b.txt && echo IDENTICAL
IDENTICAL: bodies match byte-for-byte
```

### The extraction is behaviour-identical (differential)

`Differential.java` transcribes the pre-refactor predicate (recovered with
`git show HEAD:...`) and the extracted one, then classifies the same 34 paths
with both: repo, `repo/build`, `repo/build/agent-ws`, a 4th level, scratch,
`.hg`, `.svn`, a `.git` *file* (submodule/worktree shape), the temp root, and
25 non-existent paths.

```
cases=34  mismatches=0
RESULT: BEHAVIOUR IDENTICAL
```

### Test ladder

| Level | Command | Result |
|---|---|---|
| L0 | `:pipeline-application:compileTestKotlin` | BUILD SUCCESSFUL in 1m 38s |
| L1 | `:pipeline-application:test --tests 'ProjectCheckoutDetectorTest' --tests 'CoreDeleteDirStep*' --tests 'CoreCleanWsStep*'` | tests=76 failures=0 errors=0 (skipped=5, pre-existing) |
| L2 | `:pipeline-application:test --tests 'UatCompat001*' --tests 'CompatibilityCorpusTest'` | tests=32 failures=0 errors=0, 5m 53s |
| detekt | `:pipeline-application:detekt` | BUILD SUCCESSFUL in 9s |

All verdicts are aggregates of per-class JUnit XML, never a `BUILD` line, per
the rule recorded in the previous closure. The L2 batch is the important one:
it is the corpus path that C12 broke, and it exercises the guard exactly as
shipped.

`v2/pipeline-application/detekt-baseline.xml` is **unchanged** — the new file
introduced no suppression. `find v2 -name "*.kt" -not -path "*/build/*" | wc -l`
= 866 = 864 + the 2 new files.

## A test of mine was wrong, and the harness caught it

The first L1 run failed:

```
ProjectCheckoutDetectorTest > a workspace nested one level inside a checkout
is still protected FAILED
  expected: <true> but was: <false> at ProjectCheckoutDetectorTest.kt:77
```

My assertion was wrong, not the production code. I had written the test from
the KDoc example `repo/build/agent-ws`, but that path is **two** levels below
`repo`, and the predicate only inspects the directory and its **immediate
parent**. I confirmed the real boundary with a standalone probe before changing
anything:

```
build                        -> protected=true   (parent=tmp)
build/agent-ws               -> protected=false  (parent=build)
build/agent-ws/stage-0       -> protected=false  (parent=agent-ws)
```

The two tests were rewritten to assert the actual one-level contract, and the
two-level case is now pinned as a **documented limitation** rather than an
aspiration. Widening the lookup is a behaviour change and needs its own
decision; it is not something a refactor may quietly do.

## Known limitations, preserved deliberately

1. A bare non-VCS project tree is unprotected. Pinned by
   `a bare non-VCS project tree is unprotected by design`.
   (closure receipt item 2 — still open, still a behaviour decision.)
2. Only one level up is inspected, so a workspace two levels inside a repo is
   not protected. Pinned by
   `a workspace two levels below a checkout is NOT protected`. Newly recorded
   here; not previously written down anywhere.

Both are pinned so that improving either one must be a visible edit.

## Repository integrity

`find v2 -name "*.kt" -not -path "*/build/*" | wc -l` = 866, re-verified after
the change. No canary was run: this change performs no deletion, and every
destructive probe in this session targeted temp dirs outside the repository.

## SDDK investigation while committing this work — and a correction

This receipt could not be committed on the first attempt. The global SDDK
pre-commit gate requires an alignment receipt bound to the staged tree, and
aligning requires a derivable WorkItem. Investigating that produced both a
root cause and **a correction to an earlier claim in this receipt**.

### Correction: `sddk cycle start` is NOT a no-op — `sddk cycle status` is the liar

An earlier revision of this receipt asserted that `sddk cycle start` reported
success while persisting nothing, and cited a ledger event count moving
(438 → 439) with an unchanged `last_hash` as evidence of a discarded write.
**That was wrong**, and the reason it was wrong is worth recording: the
evidence came from the CLI, and the CLI was the thing under suspicion.

Inspected directly in the ledger at
`~/.local/state/sddk/projects/p-733fb505b5a6bd2d/ledger.sqlite`, all three
cycles I started are present and `OPEN`:

```
p-733fb505b5a6bd2d/train-040-gate-recovery       OPEN  explore  2026-09-27T22:16:58Z
p-733fb505b5a6bd2d/checkout-guard-extraction     OPEN  explore  2026-09-28T06:33:45Z
p-733fb505b5a6bd2d/c14-checkout-guard            OPEN  explore  2026-09-28T06:44:46Z
```

`cycle start` works. The defect is narrower and nastier: **`sddk cycle status`
reports "no active cycle found" for a workspace that has three OPEN cycles**,
while this same workspace holds 31 open cycles in another checkout without
complaint. So the operation that fails is the read, not the write, and the
failure mode is a false negative that invites exactly the wrong conclusion.

This is a different shape from the defect in closure item 4 (ledger rejecting
advertised writes). It is a **false negative on read**, and it is what caused
this session's wasted detour. The `event_count`/`last_hash` discrepancy remains
unexplained and is *not* claimed here as a defect; the second `cycle start` is
the most likely mundane explanation, and it was not tested.

### Why the commit gate could not align

`work_item_dependencies_v1` has **0 rows**. The selection rule
`first_non_terminal_all_deps_terminal` therefore matches every non-terminal
item vacuously, and `sddk plan roadmap next` returns the first one,
`1a681dea` (the `cycle pause` CHECK-constraint stub), regardless of relevance.
The gate then rejects any other WorkItem with `WorkItem mismatch`.

This is closure item 5, confirmed again. There are exactly three non-terminal
items, and the gate offers only the least relevant of them.

### Root cause of the `cycle status` false negative — confirmed in source

`cycle status` does not infer the active cycle from `cycles.status`. It infers
it from **lease expiry**:

- `crates/sddk-cli/src/cycle.rs:305-321` calls
  `list_active_cycle_leases_for_project` and returns `NoActiveCycle` when the
  result is empty.
- `crates/sddk-storage/src/lib.rs:1002-1026` implements that as
  `WHERE c.project_id = ?1 AND cl.expires_at_ms > ?2`, with `?2 = now_ms`.

The ledger holds 16 leases and **every one has expired**; the most recent
(`train-040-final`) lapsed about 12 hours before this session. So three `OPEN`
cycles read as "no active cycle": the cycles exist, the lease does not.

Verified by acquiring a lease on the live cycle:

```
$ sddk cycle lock acquire --cycle p-733fb505b5a6bd2d/train-040-gate-recovery \
      --owner jcode-c14
lease: owner=jcode-c14 fencing_token=1 expires_at_ms=1790584047000

$ sddk cycle status
cycle_id: p-733fb505b5a6bd2d/train-040-gate-recovery
status: OPEN
phase: explore
lease: owner=jcode-c14 fencing_token=1
```

`cycle status` is repaired by renewing the lease, not by a code change.

### The commit gate is a separate, unresolved defect

Acquiring the lease fixed `cycle status` and did **not** affect the gate.
`roadmap next` still returns `1a681dea`, and aligning to the honest WorkItem
`514bf06a` is still rejected:

```
WorkItem mismatch: agent='514bf06a-...' SDDK='1a681dea-...'
```

What is known, read from the installed binary and the ledger:

- the selection query is `FROM work_items_v1 ORDER BY spine_order ASC`
- `spine_order` is `NULL` for all 47 work items, so SQLite falls back to
  insertion order
- that order starts `9016f4fe`, `1a681dea`, `514bf06a`, yet the gate returns
  **`1a681dea`, index 1** — so the rule named
  `first_non_terminal_all_deps_terminal` is not returning the first row

Full observed state of the non-terminal items, read-only query with no
hand-applied filtering:

```
pos0  9016f4fe  blockers=[]          cycle=train-040-final
pos1  1a681dea  blockers=[]          cycle=train-040-final
pos2  514bf06a  blockers=[1a681dea]  cycle=train-040-gate-recovery
```

This closes most of the investigation:

- The three non-terminal items span **two different cycles**. The gate returns
  `1a681dea`, which sits in `train-040-final` — a cycle this work is not part
  of. The WorkItem that actually holds this work, `514bf06a`, is in
  `train-040-gate-recovery`.
- The accidental edge explains the skip of `514bf06a` by itself: it is the only
  non-terminal item carrying a blocker, so the rule rejects it and has nothing
  else to return except the two `final` items.
- It does **not** explain the skip of `9016f4fe` at pos0. `9016f4fe` and
  `1a681dea` share a cycle, a status and an empty blocker set, yet the gate
  returns the latter. I did not find the discriminator. The `exit_gate` column
  exists on the table and I did not read its values, so I do not offer it as
  the cause.
- All three have `spine_order = NULL`. The `spine_order: 0` that
  `roadmap blocked` prints for `514bf06a` is not a stored value; it comes from a
  `COALESCE` in the SELECT and cannot be used to order anything.

The installed binary is 2.0.1; the framework source on this machine
(`dev/sddk-framework`) is 1.82.0 and contains no `roadmap` module at all. The
2.0.1 source is not present here, so the discrepancy could not be resolved by
reading code. **This is not claimed as understood.** It is recorded as
unresolved.

### The gate's first choice is a real, still-open defect — verified

`1a681dea` is not a stub and not noise. It tracks
**"Framework defect: sddk cycle pause violates invariant"**, and I reproduced
the defect in a sandbox (`OBSERVED`, `unshare -m -r`, real ledger SHA-256
`7eb75eeb9e31d3b2…` unchanged):

```
$ sddk cycle pause --cycle p-733fb505b5a6bd2d/train-040-gate-recovery \
    --reason dependency-waiting --lease-owner jcode-c14 --fencing-token 1

error[ENGINE_STORAGE]: storage error: database error:
CHECK constraint failed: status IN (
  'OPEN', 'BLOCKED', 'REMEDIATING', 'RELEASE_PENDING',
  'RELEASED', 'CLOSED', 'ABANDONED', 'RECOVERING'
)
```

The CLI advertises a pause that its own storage layer rejects. The status enum
in the CHECK constraint does not include any paused state, so `cycle pause` can
never succeed against this ledger. This is exactly the defect class the work
item describes: "the ledger rejects writes the CLI advertises".

Its `decision_records_v1` rationale (recorded by `agent:jcode:main`) says the
*sibling* defect was fixed in the restored clone and that this one "remains
upstream and out of scope under freeze". The rationale is consistent with what
I just observed: the storage defect is not fixed here, only worked around.

**This is why the gate is not simply wrong.** It returns the first non-terminal
item, and the first non-terminal item is a genuine open framework defect. The
gate is behaving as designed; the design is pointing at unfinished work that
cannot be finished from inside the freeze.

Consequently `1a681dea` cannot honestly be transitioned to `done` to make room
for C14. Marking it done would assert the defect is fixed, and the probe above
proves it is not. **I did not attempt that transition, in the sandbox or
otherwise.**

### Root cause of the gate skip, as far as the evidence goes

`OBSERVED`, by black-box probing in isolated mount namespaces
(`unshare -m -r`, bind-mount over a copy of the ledger directory; the real
ledger SHA-256 was `7eb75eeb9e31d3b2…` before and after every probe, unchanged).

**The selection is not a mystery and not a bug: the gate's own ordering is
`1a681dea` → `514bf06a` → `9016f4fe`, and `1a681dea` simply comes first.** My
earlier "pos0 / pos1 / pos2" positions came from my own `ORDER BY spine_order`
query, which is *not* the gate's order — `spine_order` is NULL for all items
and the gate evidently uses a different key. Probes:

- Mark `9016f4fe` done → still `1a681dea`. Mark it later by `created_at` → still
  `1a681dea`. Delete it → still `1a681dea`. So `9016f4fe` is not preferred over
  `1a681dea` under any mutation I tried.
- Leave **only** `9016f4fe` non-terminal → the gate returns `9016f4fe`. So it is
  fully eligible; it was simply last, never excluded.
- Mark `1a681dea` done → the gate returns `514bf06a` (with the accidental edge
  still present). Mark both `9016f4fe` and `1a681dea` done → `514bf06a`.
- Delete the accidental edge (so `blocked` is empty) → still `1a681dea`.
- Force `514bf06a` to the earliest `created_at` **and** remove the edge → still
  `1a681dea`.

Conclusion, `DERIVED` from the above: the gate returns the first item in its
own fixed order that is non-terminal and whose blockers are terminal. The
accidental edge is **irrelevant to the outcome** — it is the only thing
`blocked` reports, but it does not change which item `next` returns, and
removing it does not promote `514bf06a`. `1a681dea` is first because of the
gate's ordering, not because of any state I introduced.

I could not recover the exact ordering key from the 2.0.1 binary (it is not
present on this machine and the 1.82.0 source has no `roadmap` module), so the
key is still **unidentified**. But the *behaviour* is now fully characterised:
`514bf06a` becomes the gate's answer exactly when `1a681dea` is `done`.

### Consequence of the accidental edge — a false block, but not the blocker

Earlier in this session I disclosed that a rehearsal I intended to run in
isolation wrote an edge into the real ledger. I chose not to remove it, on the
grounds that it was a true statement and changed nothing. **That second
justification was wrong** — reading `roadmap blocked` rather than only
`roadmap next` showed the edge is not inert:

```
$ sddk plan roadmap blocked
{
  "blocked": [
    {
      "item_id": "514bf06a-07bc-414c-bbb4-f790432b4bb6",
      "spine_order": 0,
      "blockers": [ "1a681dea-e0a1-4a1c-946b-d85cd70f93f9" ]
    }
  ],
  "promotion_blocked": []
}
```

The edge `1a681dea ─[blocks]─> 514bf06a` makes the dependency graph assert
that the follow-up-debt item cannot proceed until the framework-defect item is
done, which is a false claim about this work and is the only thing
`roadmap blocked` reports.

Two corrections to how I first read this, both forced by the probes above:

- My "pos0 / pos1 / pos2" positions came from my own query, not the gate's.
  The gate's order is `1a681dea` → `514bf06a` → `9016f4fe`, so `1a681dea` is
  genuinely ahead of `514bf06a` regardless of the edge.
- The edge is the *only* false thing here, but it is **not** what makes the
  gate return `1a681dea`. Deleting it leaves `blocked` empty and `next` still
  returns `1a681dea`.

So the edge is a real defect in the ledger that I introduced, worth removing on
correctness grounds, and it is **not** the cause of the commit gate. Both
statements are true and the earlier version of this section conflated them.

There is no CLI path to remove it: `sddk plan dep` exposes only `add`, with no
`remove`, `rm` or `delete`. The tool can create a dependency edge but cannot
undo one, so undoing this requires a direct DELETE against the ledger. That
asymmetry is worth reporting upstream — an agent that makes one call in
error has no supported way to take it back.

The removal is **not** performed here. Writing to the ledger with a hand-rolled
`DELETE` would be a second, more invasive mutation of the same state, in the
same session, on the same table I already mutated by accident. It needs its own
explicit authorisation, with a backup of the row first. A verified backup of
the pre-deletion ledger now exists at `$JCODE_SCRATCH_DIR/ledbak/ledger.sqlite`,
SHA-256 `7eb75eeb9e31d3b2…`, byte-identical to the live ledger.

### The correct WorkItem already exists

`514bf06a-07bc-414c-bbb4-f790432b4bb6` — **"Follow-up debt from the C10-C13
gate restoration"** — is `draft` and bound to the live cycle
`p-733fb505b5a6bd2d/train-040-gate-recovery`. This refactor is item 1 of that
debt list. It is the honest owner; `1a681dea` never was.

The previous session's workaround was to acknowledge `1a681dea` and state in
prose that the product work was tracked elsewhere. That workaround is no longer
necessary, and repeating it would keep asserting a false owner. What remains is
a mechanical problem: the gate's `next` projection does not consider cycle
binding, only the (empty) dependency graph.

During the read-only inspection no write to SDDK state was performed: the
ledger was copied to a scratch path and opened `mode=ro`. A later, separate
step did write — see the disclosure below.

## A rehearsal that was not a rehearsal: disclosure

I attempted to rehearse the dependency-edge fix against an isolated copy of
SDDK state, using `SDDK_STATE_HOME` / `SDDK_DATA_DIR` pointed at a scratch
directory. The intent was to mutate nothing real.

**The isolation did not hold.** `sddk plan dep add` wrote to the real ledger
at `~/.local/state/sddk/projects/p-733fb505b5a6bd2d/ledger.sqlite`. Confirmed
by reading that file directly afterwards: `work_item_dependencies_v1` went
from 0 rows to 1, containing exactly the edge I intended to rehearse
(`1a681dea ─[blocks]─> 514bf06a`, actor `system:planner`). The rehearsal
ledger still has 0 rows. The env override is honoured for *reads* — the
`roadmap next` result did come from the copy's inputs — but the write went to
the real store. I do not know why, and did not test further.

I am not removing the row. It is a true statement (the framework-defect item
genuinely precedes the follow-up-debt item), the system actor recorded it, and
it changes no behaviour: `sddk plan roadmap next` still returns `1a681dea`
after the edge, identically to before. Removing it would be a second unrequested
write to fix an accident with a worse justification.

**It did not fix anything.** That is the substantive finding, and it disproves
my own hypothesis. I predicted that adding the dependency edge would make the
gate select `514bf06a`. It did not. The selection rule evidently does not use
`work_item_dependencies_v1` to break the tie the way I assumed, so closure item
5 is **not** a one-command fix. It is unresolved, and I withdraw the "one line
and the commit unblocks" claim I made earlier in this session.

Two process errors are worth naming, because both are the same error as the
false `cycle start` claim: I asserted a cause and a fix from CLI output alone,
and twice the CLI was the thing that misled me. The correct discipline was
already written in the previous session's closure receipt — verify from storage,
never from the tool's own report — and I did not follow it until I read the
SQLite directly.

## Still open

- This commit is **staged, not committed**, pending the WorkItem decision below.
- Push of the local commits: **not** done, feature freeze maintained.
- Closure receipt item 2 (non-VCS protection) and item 3 (`StageScope` debt)
  are untouched by this work.
- The new one-level-lookup limitation above is added to the debt list.
- The gate returns `1a681dea`, a real open framework defect that cannot be
  closed under the freeze (`cycle pause` fails on a CHECK constraint the CLI
  itself violates). This is the honest reason C14 is not committed: the gate is
  correct, and the thing it names is genuinely unfinished. Resolving it needs
  the SDDK 2.0.1 source or an upstream fix, neither of which is available in
  this workspace.
- The dependency edge `1a681dea ─[blocks]─> 514bf06a`, written unintentionally
  during the rehearsal described above, was **removed** after this receipt was
  written. `work_item_dependencies_v1` is back to empty, as declared at the
  C10-C13 closure. It never affected the gate outcome either way.

## Addendum — the baseline debt is larger than item 3 states

Measured after C14, not estimated:

| Rule | Findings |
|---|---|
| MaxLineLength | 132 |
| WildcardImport | 62 |
| UnusedParameter | 39 |
| CyclomaticComplexMethod | 32 |
| FunctionOnlyReturningConstant | 20 |
| LongMethod | 13 |
| TooGenericExceptionThrown | 12 |
| ClassNaming | 12 |
| VariableNaming | 9 |
| EmptyElseBlock | 9 |
| TooManyFunctions | 7 |
| NewLineAtEndOfFile | 7 |
| **total** | **367** |

Outstanding item 3 cites "53 methods (max 25) and retry complexity 35". Both
figures are real but partial: 53 is the `TooManyFunctions` count for
`StageScope` alone, and 35 was the cyclomatic complexity of one method. The
project-wide total was never stated and is **367**, roughly seven times the
impression the item gives.

Captured as backlog item `bl-bl-01M3KGHJRH0003877121TQ9BM0` against cycle
`train-040-gate-recovery`.

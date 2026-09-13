# Merge Train Law — parallel development, serialized global merges

> Operative law for landing gates on `origin/main`. Executable oracle:
> `scripts/train/drift.sh` (`just train`). Nothing in this document is a substitute
> for a fresh canary; it defines **when** a lane is allowed to be called a merge
> candidate, not **what** evidence it must carry.

## 1. The property being protected

```text
parallel development  +  serialized global merges
                      +  every gate proven against the REAL main that will receive it
```

A gate proven against a stale fork-point has not been proven. Drift is therefore a
first-class, continuously recomputed fact, not a one-off check at PR-open time.

## 2. READY_TO_MERGE

```text
READY_TO_MERGE iff

    PR.base                    == main
    fork-point(branch)         == current origin/main     (behind == 0)
    required fresh canaries    == GREEN
    new regressions            == 0
    receipt/provenance         == current HEAD
    global writer collision    == false                   (at most one [WRITER])
```

- `behind == 0` means the branch's merge-base **is** `origin/main`. A branch that
  carries `behind > 0` must rebase and regenerate its evidence; merged-but-unrebased
  is not READY.
- The last three clauses are **declared, never inferred**. `drift.sh` prints them as
  a checklist because no script can read a canary that was never run.
- Receipt provenance follows the established precedent for the certification chain:
  a gate receipt may record `pending (this receipt)` at its own gate (cleanWs G6,
  `S2_A10_CORE_CLEANWS_G6_*`, and archiveArtifacts G6 both do), and the **next**
  gate's receipt records the resolved SHA. "pending" is legal only while the receipt
  is inside the commit it describes.
- **A gate has two identities, and they are not interchangeable:**

  ```text
  code-under-test          = the SHA whose behaviour the gate observed
  evidence/receipt commit  = the SHA that carries the receipt
  ```

  For a docs-only evidence PR these deliberately differ: the gate ran against the
  code that was already on `main`, and the receipt landed on top of it as a separate
  commit. A receipt naming its own commit as `code-under-test` would be wrong; a
  receipt naming the code SHA is **not** "pending" and must not be rewritten as such.
  The next gate cites both, e.g. `code 2271fb1e / evidence e7ea54ba`.

## 3. Lane states (visible in PR title or label)

```text
[PREP]      local work; no merge authority
[STACKED]   depends on another PR; base is not main
[READY]     mechanically and evidentially verifiable against current main
[WRITER]    the ONE PR authorized to mutate global authority
```

**At most one `[WRITER]` may exist at any moment.** The oracle enforces this: more
than one `[WRITER]` is reported as a LAW VIOLATION, not a warning.

## 4. Post-merge recalculation

After every merge, without exception:

```text
git fetch origin
        |
   main moves
        |
recompute drift across ALL lanes
        |
   READY     -> stays ready
   BEHIND    -> rebase + regenerate evidence
   CONFLICT  -> STOP
   STACKED   -> remains on its parent; retarget when the parent lands
```

`just train` performs the fetch and the full recalculation in one step.

## 5. Stacked lanes

A lane may be **worked** before its parent lands; it may not be **merged** before
its parent lands.

```text
main
 └─ bodyinvoker                    [WRITER when active]  base: main
      ├─ waituntil-bodyinvoker     [STACKED]             base: cycle/...-bodyinvoker
      └─ load-bodyinvoker          [STACKED]             base: cycle/...-bodyinvoker
```

Stacked PRs stay `Draft` and point at their parent branch. When the parent lands, the
child is rebased onto the new `origin/main`, its evidence is regenerated, and only
then does it become a merge candidate. `drift.sh` classifies any PR whose `base` is
not `main` as `STACKED` automatically.

## 6. Applying the law to a gate chain

A certification chain (`G4 → G5 → G6 → G7 → G8`) is a **merge train**: one gate per
merge, one writer at a time, STOP between gates.

```text
main@BASE
    │
    ├── g6   coverage/provenance    PR -> merge -> STOP
    ├── g7   installed acceptance   PR -> merge -> STOP
    └── g8   CERTIFIED              PR -> merge -> STOP
```

Each gate's branch is cut from the `origin/main` produced by the previous gate's
merge. Never stack a gate on the previous gate's unmerged branch and call it a merge
candidate: it is `[PREP]`/`[STACKED]` until its parent lands.

## 7. Serialization: one canonical ancestor per gate

```text
No gate opens before its prerequisite is in origin/main.
```

Even when the prerequisite is docs-only and cannot change an executable byte. The
reason is ancestry, not behaviour: each gate must have exactly one canonical
ancestor, so that "what did this gate prove?" has one answer. A lane branched from a
prerequisite that is merged only in a local worktree is not ready to open; it is
ready once that prerequisite is reachable from `origin/main`.

Worked example: archiveArtifacts G8 does not open until the G7 evidence PR is in
`origin/main`, even though G7 changes no executable byte.

## 8. Shared-identifier claims (ADR numbers and similar)

Two lanes can independently claim the same global identifier. This hazard is created
by parallel development, produces **no merge conflict**, and is invisible to
`drift.sh`.

```text
Two ADRs may not share one number. Neither may two migrations, two schema
versions, two event-type ids, or two step keys.
```

Resolution, in order:

```text
1. The existing corpus is the tiebreaker. If the rest of the tree already cites the
   identifier with one meaning, that meaning stands.
2. Otherwise the prerequisite/earlier lane keeps it and the newcomer renumbers.
3. The renumbering lane fixes its own index entry itself, including placement: an
   appended entry must preserve the file's existing order.
```

Never resolve a collision by editing the other lane, and never by leaving both in
place.

Provenance of the rule: the `bodyinvoker` and `r2-runtime-return` lanes both claimed
ADR-0081. SPIKE-018, the step-ecosystem status doc and the session checkpoints all
already cited "ADR-0081" meaning the BodyInvoker ADR, so the newcomer renumbered to
0082 (`b7685b97`).

Identifiers are not the only shared resource. Any single-valued field of a document
or manifest that two lanes both append to is a collision site (indexes, changelogs,
counters, ordering tables). Treat a clean textual rebase as **no** evidence that two
lanes are semantically compatible: `git range-diff` proving `=` only proves the
rebase was content-preserving, not that the content is still consistent with what
landed underneath it.

## 9. Non-goals

- This law does not replace the V2 TESTING RULES (validation ladder, timeout budgets,
  XML canary truth). It sits above them: those rules define how a canary is produced,
  this law defines whether that canary still applies to the main that will receive it.
- This law does not authorize rewriting `origin/main`. Trunk hygiene stays
  fast-forward-preferred; main is not linearized to make a lane READY.

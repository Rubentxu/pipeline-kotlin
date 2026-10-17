# Gate-1 @Disabled triage — 2026-10-17

**Scope:** HITO 4 (BQ5) — classify the disabled UATs by guarantee / defect / cost /
owner. **Base:** `main@c602e6b4`. **Machine authority:**
`scripts/gen-disabled-inventory.py` → `WU_LPR_061_DISABLED_INVENTORY.md`.

## What was actually true

`disabled_uat_review_2026-10-17.md` recorded "0 deletions, review complete" and never
ran the generator. Running it produced the fact the prose review had missed:

```text
UNKNOWN = 136        mandatory-disabled (Gate-1) = 136
Gate-1 @Disabled state = OPEN
exit 1
```

Gate-1 was **OPEN**, blocked by 136 untriaged entries, and no receipt said so.

## The bulk were one fact, not 136 decisions

Nearly all of them are **legacy burn-down counter snapshots** — assertions about how
many legacy keys *remained* at a named gate:

```text
`counters are 7-7-7 and legacy dispatcher source is physically removed`
`G4 flip post-S2-A6-G4 - 6 residual legacy keys remain`
`LEGACY_PLUGIN_IDS post-S2-A9-G5 - 4 residual keys remain`
```

A counter snapshot is a claim about a migration **in progress**. It is closed history
exactly when the migration is finished, and that is checkable rather than assumed:

```text
LEGACY_PLUGIN_IDS  ->  EMPTY (size 0)
```

So the classification is anchored to a fact **read from source at generation time**,
not to a method name.

## Why a rule and not a table

`CLASSIFICATION` matches exact file + exact method. Filing these as ~100 hand-written
rows would rebuild, one layer up, the same fragility removed from the JGit fitness this
same session: a hand-maintained list that silently stops matching. One narrow rule plus
a fact gate is both smaller and self-invalidating.

The rule is **fail-closed by construction**: if the burn-down reopens and the set is
non-empty, the rule stops firing and every covered entry falls back to UNKNOWN,
re-blocking Gate-1. A reopened migration has live invariants, and hiding them behind a
green gate is the failure this whole mechanism exists to prevent.

## What it did NOT classify, and why that matters

The first pass closed 41. Eleven of those were wrong, and reviewing them is the reason
this section exists:

```text
`G5 milestone - core dot pwd stays absent from LEGACY_PLUGIN_IDS post-S2-A9-G5`
`no legacy resurrection — irreversible G5 state holds post-S2-A6-G4`
```

These assert a property that is **still true today** — legacy must not resurrect. They
were written at a gate, and that is the only thing about them that is historical.
Filing them HISTORICAL would have buried a live guard behind a non-gating category:
worse than leaving them UNKNOWN, because the inventory would have said they were
accounted for.

`LIVE_INVARIANT_RE` now excludes them, and the generator returns a reason that says what
a human has to decide. Those 11 are the genuinely interesting remainder of this family.

## Result

```text
                  before    after
UNKNOWN             136      106
HISTORICAL            0       30
COMPATIBILITY         2        2
Gate-1              OPEN     OPEN   (still blocked, by the right entries)
exit                  1        1
```

Gate-1 stays OPEN. That is correct, not a failure: the remaining 106 are the
`migrated-pending` UATs awaiting the harness verdict plus the live-invariant guards, and
none of them may be closed by inference. **The change is that 30 are now correctly
accounted for and the rest are named.**

## Verification

- `scripts/test_gen_disabled_inventory.py`: 9 → **14 tests**, all executing. (The first
  version of the new class landed *after* `unittest.main()` and silently ran 9; the
  count is how that was caught.)
- Mutation 1 — delete the rule branch → **1 failure**.
- Mutation 2 — keep the name match, drop the fact gate → **1 failure**.
- Mutation 3 — delete the `LIVE_INVARIANT_RE` guard → **2 failures** (both subTests).
- All three restored byte-identical (`diff -q`).

## What this receipt does NOT establish

- Nothing was deleted and nothing was re-enabled. No UAT changed state.
- Gate-1 is not GREEN.
- The 106 are not triaged. They are counted, named, and blocked.

## Leads

- The 11 live-invariant guards are the highest-value remainder: each asserts something
  true today and could be re-enabled with an updated assertion instead of pinning to a
  gate number that will never be true again.
- `SegmentOutputStoreTest` OUT-01/OUT-02 are disabled and describe runId safe-prefix
  **collisions** — a plausible live defect, not history. They are in the 106 and should
  not be archived.
- `UatRunConcurrencyCharacterisationTest` characterises two concurrent resume owners
  re-executing. That is a known-defect characterisation, deliberately parked.
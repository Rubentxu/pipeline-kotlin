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

---

# Part 2 — the remaining 106, and what they actually were

## The generator could not read a word of the author's reason

`classify(rel, method)` was never passed the `@Disabled` reason. So the 106 broke
down, once measured, as:

| declared disposition | count |
| --- | ---: |
| `migrated-pending` | 53 |
| `Historical` / `Archived` | 34 |
| `producer-internal:` | 9 |
| **no declaration at all** | **10** |

96 entries carried a deliberate, policy-cited disposition in the only field a
reader opens, and the generator saw none of it. That is the same failure the
fail-closed default was built to prevent, arriving from the other direction: the
gate was not too permissive, it was **blind**.

Two supporting defects fell out of the same fix. The reason was truncated to 160
characters *before* classification, so a disposition stated in the tail of a long
string was invisible; truncation now applies to the rendered table only. And the
`OBSOLETE` category ("must name its live replacement") had never been able to name
one, for the same reason.

## A token is a human decision, and it is not a bypass

Each token is matched at the **start** of the reason, from a closed vocabulary, and
is still subject to the live-invariant guard. `historical` buried mid-sentence is
not a declaration — there is a test that says so.

Two categories were added because the existing five cannot express states that
genuinely exist, and collapsing them would be the flag-bag defect the project law
names:

| category | blocks Gate-1 | meaning |
| --- | --- | --- |
| `PENDING_EXTERNAL` | yes | owner is `pipelinek-release-harness`; awaits its verdict |
| `KNOWN_DEFECT` | no | a reproduced open defect kept as a witness |

`PENDING_EXTERNAL` also fixes an owner lie: 53 rows were attributed to
"pipeline-kotlin maintainers", who are not the party who can retire them.

## The 16 "live guards", and a hypothesis that was wrong

Sixteen rows assert a property that is **still true**: `core.pwd stays absent from
LEGACY_PLUGIN_IDS`. They are `@Disabled`, so none of them fires, and the obvious
conclusion is that sixteen guarantees are unowned.

**That was wrong, and checking cost one grep.** `LegacyResidualSnapshot.physicalResidual`
is `emptySet()` and `LegacyResidualConvergenceFitnessTest` is enabled and asserts the
live set equals that expected empty snapshot. The guarantee *is* owned. The sixteen
are **disabled duplicates of a running guard**.

So their retirement is authorised by **equivalence**, not by age — which is exactly
what the mandate requires before retiring a legacy test, and exactly what the
`OBSOLETE` category is for. The equivalence is machine-checked rather than asserted:
`convergence_fitness_is_live()` requires the successor to exist and carry no
`@Disabled`. Delete or disable the successor and all sixteen fall back to blocking.

Verified, not read:

```text
:pipeline-architecture-tests:test --tests '*LegacyResidualConvergenceFitnessTest*'
BUILD SUCCESSFUL in 9s
tests="3" skipped="0" failures="0" errors="0"     (canary XML deleted first)
```

## Eight rows the rule refuses to guess about

Eight siblings say `core dot pwd stays absent post-S2-A10-G4` **without naming the
set** — their referent is implied by neighbouring rows, not stated. The rule
requires `LEGACY_PLUGIN_IDS` in the text and refuses to inherit it. Inferring the
referent from a sibling is precisely the silent inference this mechanism exists to
prevent, and eight rows are still cheaper to answer by hand than to guess wrong.

## Result

```text
                     part 1      part 2
UNKNOWN                  106          16
HISTORICAL                30          50
MANDATORY_SUPPORTED        0           9
PENDING_EXTERNAL           0          53
KNOWN_DEFECT               0           2
OBSOLETE                   0           6
COMPATIBILITY              2           2
mandatory-disabled       106          25
Gate-1                OPEN(106)    OPEN(25)   exit 1
```

The blocker shrank from 106 to 25 and every remaining row is named with a reason.
**Gate-1 is still OPEN and the generator still exits 1**, which is correct: 53 rows
await a harness verdict and 16 await a human. Nothing here retires a test by
silence.

## Verification

`scripts/test_gen_disabled_inventory.py`: 14 → **19 tests**, all executing.

| mutation | killed |
| --- | --- |
| delete the declared-token branch | `failures=6, errors=1` |
| anchor weakened from start-of-reason to anywhere-in-reason | `failures=2` |
| drop the equivalence precondition on the successor guard | `failures=2` |

All three restored to sha256 `cdc5873d…` (verified identical).

## What this receipt does NOT establish

- No test was deleted and no `@Disabled` was removed. `OBSOLETE` records that a row
  *may* be retired; it does not retire it.
- The 16 equivalent rows were not rewritten to assert the live property. They are
  classified, and the classification is reversible.
- 30 of the 53 `PENDING_EXTERNAL` rows carry no prose beyond the bare token, so
  the harness issue they wait on is not recorded next to them. That is a real gap
  in 30 files and is not addressed here.
- `SegmentOutputStoreTest` OUT-01/OUT-02 are now `KNOWN_DEFECT` and non-gating.
  That is a claim about **the test**, not about the defect: the prefix-collision
  defect is real, reproduced, and open.
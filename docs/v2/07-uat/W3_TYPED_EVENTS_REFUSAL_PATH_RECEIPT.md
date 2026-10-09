# W3 — Typed-event refusal path: receipt

**Cycle:** `p-733fb505b5a6bd2d/rp7-sem-s6-plugin-sdk` (OPEN, phase `build`, path A-full)
**Base SHA:** `4d01acbd1a9cdc4b145620d36f2d01f50eeb0556` (`feat(events): add pipelinek events --typed`)
**State:** `IMPLEMENTED_UNCERTIFIED` — W3 evidence and the round gate are green on the base
SHA plus these rows. Certification still requires a new round gate on the candidate SHA.

Reference implementation consulted: none applicable. This slice is a read/observability
contract over an existing store, not a Step, so there is no Jenkins or equivalent
ecosystem behaviour to adopt. The contract was derived from the typed requirement that a
row which cannot be decoded must be named rather than dropped.

---

## What changed

| File | Change |
|---|---|
| `MainEventsCli.kt` | `reportTyped` routes its status through the new `typedExitCodeFor` instead of a literal `0`; usage line split to satisfy detekt's 160-char limit |
| `TypedEventsCapabilityRedTest.kt` | +228 lines: UAT-TYPED-008, -009, -010; `TS` const became `ts()` |

No storage contract changed. No store API was exposed. Envelope output is unchanged.

---

## Rows

| Row | Claim |
|---|---|
| UAT-TYPED-008 | A version-skew row (`HttpRequestFinished`, written raw through SQLite) is refused by name — `evt-refusal-v1:<runId>:3` — and never printed as an event. Exit status 0, count `evt-refusals-v1:<runId>:1`. |
| UAT-TYPED-009 | An unreadable row occupies its page position: `--limit 2` returns rows 1 and 2, and the continuation rests on 2. A resumed read does not re-report it. |
| UAT-TYPED-010 | `typedExitCodeFor` and `exitCodeFor` state one exit-code contract; a divergence fails the row. |

## Measured: the first version of UAT-TYPED-008 did not have its claimed property

The KDoc named the mutation `for (record in slice.records)` → `slice.decoded`. It was applied.
**The suite stayed 8/8.** The reason is a defect in the ROW, not the product:
`refusals.addAll(slice.refusals)` sits OUTSIDE the loop, so the refusal line is emitted
correctly whether the loop walks `records` or `decoded`. The row proved accumulation, not
iteration, and would have kept passing under exactly the `mapNotNull` it claims to forbid.

UAT-TYPED-009 carries the weight instead. Recorded in the test KDoc rather than removed.

## Measured: `slice.decoded` and `slice.records` are equivalent here

`SqliteEventStore.readRecords` computes `nextCursor` from `page`, and `page` holds
`Undecodable` rows too. A `mapNotNull` over the page cannot move the cursor, so it cannot be
the kill for UAT-TYPED-009. The kill is recomputing the cursor from emitted events.

## Mutation → killed rows (1:1)

| Mutation | Result |
|---|---|
| `nextCursor = continuation` → recomputed from `events.lastOrNull()?.sequence` | **UAT-TYPED-009 FAIL** (`evt-cursor-v1:w1-typed-red:1`, expected 2). 9 others pass. |
| `typedExitCodeFor` `Stalled` cell `0` → `3` | **UAT-TYPED-010 FAIL** (`expected: <0> but was: <3>`). 9 others pass. |

Both mutations were reverted and verified with `git diff --quiet HEAD` on production sources.
`EventPageDrain.kt` is byte-identical to `4d01acbd`.

## The exit-code change, and why it is not cosmetic

`reportTyped` returned a bare `0`. That agreed with the envelope contract by coincidence,
not by inheritance — and a `return 0` has no forcing function when a case is added, while a
function exhaustive over a closed ADT does. Two overloads rather than a conversion between
the ADTs: converting a typed page into an envelope-shaped one would fabricate envelopes the
caller never reads.

## Two test-authority corrections

Both were the TEST being wrong, not the product:

1. `--limit 1` cannot yield cursor 2. `readRecords` counts ROWS (`LIMIT limit+1`, break at
   `page.size == limit`), so a page of 1 has cursor 1. Corrected to `--limit 2`.
2. The resumed read cannot re-report a refusal the previous page already delivered. The row
   now pins the no-stranding half instead.

## Detekt was red BEFORE this slice

Both issues were reproduced at base SHA `4d01acbd` with the W3 changes stashed
(`detekt_at_HEAD_exit=1`, "Analysis failed with 2 issues"): the 173-char usage line (from W2)
and the `TS` field name (pre-existing). Fixed here. `detekt_exit=0` after.

---

## Evidence

Round gate, run 2026-10-09 09:42:00, budget 1700s (raised from 1270 and the reason recorded:
the previous attempt was starved by three concurrent `--rerun-tasks` builds in sibling repos):

```text
argv:   timeout 1700 ./gradlew check        (cwd v2)
exit:   0
verdict: BUILD SUCCESSFUL in 26m 2s
canary: 353 -> 801 TEST-*.xml; 450 written after 09:42:00
FRESH over new XML only: classes=450 tests=3058 skipped=133 failures=0 errors=0
TypedEventsCapabilityRedTest: tests=10 skipped=0 failures=0 errors=0
```

The 133 skips carry pre-existing `@Disabled` reasons (historical S2-A6/G1 traceability
snapshots, harness-migrated UATs). Zero `@Disabled` in the changed test file.

Directed runs during the slice, all with XML deleted first (canary, rule 25):

```text
--tests '*TypedEventsCapabilityRedTest*'   10 tests, 0 failures
--tests '*Events*' '*EventPage*' '*Typed*' 63 tests, 0 failures, 1 skip
:pipeline-application:detekt               exit 0
```

## Gates

| Gate | State |
|---|---|
| STEP-CERT (W3 matrix) | **PASS** on `4d01acbd` + these rows |
| Round gate `check` | **PASS** (fresh XML above) |
| PRODUCT-GATE | **`BLOCKED_EXTERNAL`** — unchanged; requires remote CI, which does not exist in this repository since `754ddda0` |

A receipt is evidence for its own SHA and inherits nothing. The candidate built from this
commit requires a new round gate on that exact SHA before any `CERTIFIED` claim.

## Not done

- No push, no tag movement, no remote mutation, no GitHub prerelease, no Maven publication.
- External certification remains the harness's authority.
- `PRODUCT-GATE` stays `BLOCKED_EXTERNAL` and is not affected by this slice.
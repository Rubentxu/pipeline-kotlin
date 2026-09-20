# WU-LPR-051 — Inspect/filter/cursor (closure receipt)

**Status:** CLOSED. Pure agent-efficient inspection surface — `EventInspection`
+ `EventField` sealed interface + 4 pure transforms
(`projectFields` / `contextWindow` / `tail` / `follow`). No query language,
no arbitrary predicates; agents pass typed values and get typed results.

The CLI flag wiring (`--fields`, `--tail`, `--context`, `--after-sequence`)
is **not** part of this WU — that fold is WU-LPR-051-FOLLOWUP (consume the
inspection in `MainEventsCli.main`).

---

## 1. Scope

> Agent-efficient filters, fields, tail/context/follow; no query language.

Per `docs/v2/05-roadmap/LPR_WORK_UNITS.md` (WU-LPR-051, L68-70).

**This WU produces**:
- The pure inspection (`EventInspection`).
- The closed `EventField` whitelist (5 cases: `Sequence`, `Kind`, `EventId`,
  `Subject`, `Source`).
- 22 tests pinning field projection, context window, tail, follow, and
  input validation.

**This WU does NOT produce** (deferred to follow-up):
- CLI flag wiring in `MainEventsCli`.
- Streaming `--follow` that re-queries the store on a poll loop (the
  follow helper here is purely a sequence-greater-than filter; a
  streaming follow is a separate CLI surface).

## 2. Decision table

| Helper              | Signature                                              |
|---------------------|--------------------------------------------------------|
| `projectFields`     | `(envelopes, fields) -> List<String>` (header + rows)  |
| `contextWindow`     | `(envelopes, kind, before, after) -> List<envelope>`   |
| `tail`              | `(envelopes, count) -> List<envelope>` (last `count`)  |
| `follow`            | `(envelopes, afterSequence) -> List<envelope>`         |

The `EventField` whitelist (closed ADT, 5 cases) is the typed field set
agents may request. Adding a new field forces every consumer of
`EventInspection.projectFields` to be revisited (compile-time
exhaustiveness check).

## 3. Test surface (22 tests, 0 failures, 0 errors)

`v2/pipeline-events/src/test/kotlin/dev/rubentxu/pipeline/v2/events/identity/WULpr051EventInspectionTest.kt`

| Nested group | Tests |
|--------------|-------|
| `FieldSelection` (header, sequence, kind, eventId, subject, empty) | 6 |
| `ContextWindow` (basic match, multi-window, no-hit, zero-window, before-clamp, after-clamp, dedupe) | 7 |
| `Tail` (basic, oversized, zero) | 3 |
| `Follow` (basic, end-of-run, before-start) | 3 |
| `Invariants` (negative before, negative after, negative count) | 3 |

Total = 22 tests, 5 nested groups, 0 failures, 0 errors, 0 skipped.

## 4. Build evidence

```text
L0: ./gradlew -p v2 :pipeline-events:compileKotlin
    BUILD SUCCESSFUL in 12s

L1: ./gradlew -p v2 :pipeline-events:test --tests 'WULpr051EventInspectionTest'
    BUILD SUCCESSFUL in 13s (22 tests, 0/0/0)

L2: ./gradlew -p v2 :pipeline-events:test
    BUILD SUCCESSFUL in 16s (178 tests, 0 failures, 0 errors, 0 skipped)
```

## 5. Production code impact

**Zero production CLI modification in this WU.** `MainEventsCli` continues
to consume the historical `--kind` / `--subject` / `--limit` /
`--after-cursor` flags and emit raw JSONL envelopes. The inspection is the
**migration target**: a follow-up WU that wires `--fields`, `--tail`,
`--context`, `--after-sequence` flags will consume `EventInspection` to
drive stdout emission.

## 6. Findings for follow-up

| ID | Finding | Suggested follow-up | Severity |
|----|---------|---------------------|----------|
| F1 | `MainEventsCli` does not consume the inspection surface; CLI flags absent | **WU-LPR-051-FOLLOWUP-A** (CLI flag wiring) | `POST_LPR` |
| F2 | Streaming `--follow` (poll loop) is a separate surface that re-queries the store; not part of the pure inspection | **WU-LPR-051-FOLLOWUP-B** (CLI streaming) | `POST_LPR` |

## 7. Auto-continue

LPR-5 train per the roadmap: `WU-LPR-050 → WU-LPR-051 → ...`.
LPR-5 is **now structurally closed**. Both WUs are PROJECTION-/INSPECTION-
ONLY; the CLI flag wiring is deferred to FOLLOWUP slices. The next LPR
milestone is **LPR-6** (Certification ledger + Real projects). Receipts
already exist for WU-LPR-060 (certification ledger), WU-LPR-061
(`@Disabled` classification), WU-LPR-062 (Gradle demo), WU-LPR-063 (Maven
demo), WU-LPR-064 (Node demo); LPR-6 is already closed.

The natural next slice is **LPR-7** (Distribution + Release workflow —
WU-LPR-070/071). Both receipts already exist; LPR-7 is closed.

---

**CLOSED — 2026-09-20.**

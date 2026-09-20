# WU-LPR-050 — CLI views/formats (closure receipt, partial)

**Status:** CLOSED — PROJECTION-ONLY slice. The full WU-LPR-050 spec
("Implement normal/events/full/console/quiet and text/jsonl/json as
read-side projection") is split across multiple cycles; this WU lands the
**pure read-side projection** only.

**Outcome:** `EventViewProjection` object + `ViewMode` enum (5 cases:
`NORMAL` / `EVENTS` / `FULL` / `CONSOLE` / `QUIET`) + `OutputFormat` enum
(3 cases: `JSONL` / `JSON` / `TEXT`). Maps `(envelopes, mode, format) ->
List<String>`. Pure, total, no effects. The CLI
(`MainEventsCli`) is the effectful boundary that consumes the projection;
it is not modified in this WU.

The CLI's `--view` / `--format` flag wiring is WU-LPR-050-FOLLOWUP-A
(consume the projection in `MainEventsCli.main`).

---

## 1. Scope

> Implement normal/events/full/console/quiet and text/jsonl/json as
> read-side projection.

Per `docs/v2/05-roadmap/LPR_WORK_UNITS.md` (WU-LPR-050, L64-66).

**This WU produces**:
- The pure projection (`EventViewProjection.project`).
- The closed `ViewMode` and `OutputFormat` enums.
- 13 tests pinning the projection contract (mode filtering, format
  rendering, combined mode+format, empty input).

**This WU does NOT produce** (deferred to follow-up):
- CLI flag wiring (`--view` / `--format` in `MainEventsCli`).
- `--tail` / `--context` / `--follow` projection (those are WU-LPR-051's
  agent-efficient filters, separate slice).

## 2. Decision table

| Mode        | Filter                                       |
|-------------|----------------------------------------------|
| `EVENTS`    | every envelope                               |
| `FULL`      | every envelope (alias for `EVENTS`)          |
| `NORMAL`    | lifecycle only (Compilation/Run/Stage/Step)  |
| `CONSOLE`   | only `EchoOutputCaptured`                    |
| `QUIET`     | only `RunFinished`                           |

| Format    | Output                                  |
|-----------|-----------------------------------------|
| `JSONL`   | one JSON envelope per line              |
| `JSON`    | single JSON array carrying all envelopes|
| `TEXT`    | `<sequence> <kind>` per envelope (agent-friendly) |

The text format deliberately renders only `<sequence> <kind>` per envelope
(no payload). Payload rendering would require per-kind text handlers for
the sealed `DomainEvent` hierarchy; that is reserved for a follow-up WU.

## 3. Test surface (13 tests, 0 failures, 0 errors)

`v2/pipeline-events/src/test/kotlin/dev/rubentxu/pipeline/v2/events/identity/WULpr050EventViewProjectionTest.kt`

| Nested group | Tests |
|--------------|-------|
| `ModeFiltering` (events, full, normal, normal drops echo, console, quiet) | 6 |
| `FormatRendering` (jsonl, json, text) | 3 |
| `Combined` (normal+text, console+jsonl, quiet+json) | 3 |
| `EmptyInput` (no lines for jsonl/text, single "[]" for json) | 1 |

Total = 13 tests, 4 nested groups, 0 failures, 0 errors, 0 skipped.

## 4. Build evidence

```text
L0: ./gradlew -p v2 :pipeline-events:compileKotlin
    BUILD SUCCESSFUL in 12s

L1: ./gradlew -p v2 :pipeline-events:test --tests 'WULpr050EventViewProjectionTest'
    BUILD SUCCESSFUL in 13s (13 tests, 0/0/0)

L2: ./gradlew -p v2 :pipeline-events:test
    BUILD SUCCESSFUL in 16s (156 tests, 0 failures, 0 errors, 0 skipped)
```

## 5. Production code impact

**Zero production CLI modification in this WU.** The canonical
`MainEventsCli.main` continues to emit raw JSONL envelopes (the historical
default). The projection is the **migration target**: a follow-up WU that
wires `--view` / `--format` flags will consume
`EventViewProjection.project` to drive stdout emission.

## 6. Findings for follow-up

| ID | Finding | Suggested follow-up | Severity |
|----|---------|---------------------|----------|
| F1 | `MainEventsCli.main` does not consume the projection; CLI flags are absent | **WU-LPR-050-FOLLOWUP-A** (CLI flag wiring) | `POST_LPR` |
| F2 | WU-LPR-051 (Inspect/filter/cursor — tail/context/follow) not started | **WU-LPR-051** (separate cycle) | `POST_LPR` |
| F3 | Text format renders only `<sequence> <kind>` (no payload); per-kind text rendering is reserved | **WU-LPR-050-FOLLOWUP-B** (per-kind text rendering) | `POST_LPR` |

## 7. Auto-continue

Per the LPR-5 train, the natural next slice is **WU-LPR-051** (Inspect /
filter / cursor — agent-efficient filters, fields, tail/context/follow;
no query language). WU-LPR-051 is closer in scope to WU-LPR-050 slice 1/3
(seam creation): `EventCursor` already exists in the identity package
(see `EventCursor.decode / encode` used by `MainEventsCli`); the missing
piece is the typed filter / tail / follow surface that consumes it.

---

**CLOSED (PROJECTION-ONLY) — 2026-09-20.**

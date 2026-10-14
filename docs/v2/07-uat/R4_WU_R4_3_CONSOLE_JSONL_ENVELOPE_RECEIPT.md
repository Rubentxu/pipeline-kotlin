# R4 / WU-R4.3 — Console JSONL envelope (`--format text|jsonl`)

- **Status:** CERTIFIED · 2026-10-14
- **Train:** 0.60.0-rc4 · **Source of truth:** this receipt + `git rev-parse HEAD`
- **Provenance:** PR #117 (branch `r4/wu-r4-3-console-jsonl`) → merge commit (TBD on merge)

## What

Adds `--format text|jsonl` to `pipelinek console`. The default is `text` (raw bytes to
stdout, unchanged from M1-P3). `--format jsonl` switches the emitter to
[ObservationJsonLines] and emits one record per line in the canonical envelope
`{type:"output", channel, operation, ordinal, from, to, text/base64}` that
`pipelinek observe --view console --format jsonl` already produces.

Stdout stays the protocol surface (one record per line, newline-terminated, flushed).
The continuation token still goes to stderr so a script can resume without parsing
the jsonl stream for control bytes.

## Why (CON-2.1)

`R4_CLI_GAP_ANALYSIS §2` named CON-2.1 ("No JSONL envelope") as a BLOCKER for R4 §4
and MAJOR for spec consumers. The closed-typed `OutputRefusal` shape the verb already
emits on stderr (`console-refused: ...`) is preserved: a refusal short-circuits the
format dispatch and the same prefix is on stderr regardless of `--format`, so a script
that switches to jsonl does not also have to switch its error-grep prefix.

## Where

- **Modified:** `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/MainConsoleCli.kt`
  - New `MainConsoleCli.ConsoleFormat` sealed enum (2 cases: `TEXT`, `JSONL`).
  - `ConsoleReadRequest.Paged` and `ConsoleReadRequest.Range` carry `format: ConsoleFormat`.
  - `ConsoleCliTokens` carries `formatToken: String?` (validated in `validate`).
  - New `ConsoleCliRefusal.UnknownFormat(value, accepted)` — distinct from
    `UnknownOption` (the verb did not recognise the flag) and `MissingOptionValue`
    (the flag had no value). Names the offending value AND the closed-typed accepted
    list so a caller edits one token.
  - `MainConsoleCli.emit(result, request, out, err)` dispatches on `request.format`
    over the closed `ConsoleFormat` ADT. `JSONL` builds a synthetic
    `ObservationRecord.Output` (one record per merged page) and routes through the
    existing `ObservationJsonLines.writeTo`. `TEXT` keeps the legacy raw-bytes path
    verbatim.
- **New:** `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/MainConsoleFormatTest.kt`
  - 9 tests: HF0 closed ADT pin, parser happy/refusal, usage line surface,
    refusal parity (text vs jsonl) on a missing run, and a fixed shape contract on
    `ObservationJsonLines.encodeOne` (the same writer `observe` uses).

## What this slice is NOT

- NOT a wholesale fold of the console parser into `CliParser.parseObservation` (CC-1).
  The local `MainConsoleCliAdmission` stays; a future slice can promote the option
  list when the four verbs share a typed option table. The console-only flags
  (`--max-bytes`, `--after-cursor`, `--range`) are deliberately NOT in `CliParser`,
  and `--format` follows the same path.
- NOT per-frame granularity. The JSONL emitter produces ONE record per merged page
  (the page is the existing reader-side projection; per-frame records through
  `FrameIndexedObservationOutputReader` are CON-2.1/2.2 follow-up, once the
  per-operation index is available).
- NOT a wakeup-based follow (CON-2.3). `--follow` is not in this slice; the existing
  `readTail` path is what `observe` already uses.
- NOT a typed `EventReadRefusal` ADT (CC-7). Refusals stay on stderr as
  `console-refused: ...`; the existing `OutputRefusal` ADT is the typed carrier.
- NOT a `--stage / --step / --kind` filter (CON-2.2). The current verb takes
  `<runId> <opId>` and the filters need a per-operation index; future slice.
- NOT an installed-distribution (HF2) test. Cross-CLI installDist canary is
  WU-R4.5; this slice is HF0 + HF1.

## Strategy

The console takes `<runId> <opId>` and merges stdout+stderr into one page. The
JSONL emitter re-uses the existing `ObservationJsonLines.encodeOne` writer — it
constructs a synthetic `ObservationRecord.Output` from the page so the same
encoder `pipelinek observe --view console --format jsonl` already uses stays the
single authority over the wire shape. A future refactor that switches to per-frame
records will route through the same encoder and the wire format does not change.

## Closed ADT pin

```text
ConsoleFormat
  TEXT    (--format text, default — raw bytes to stdout)
  JSONL   (--format jsonl — one ObservationJsonLines record per line)
```

Adding a third case is a compile-time break for every caller of
`ConsoleFormat.parse` and for the `when (request.format)` dispatch in `emit`. The
HF0 row in the test suite enumerates the two and asserts `toSet().size == size` to
catch silent case collapse.

## Refusal contract parity

| `--format` | Path that produces a refusal | stderr prefix | exit code |
|---|---|---|---|
| `text` | `ConsoleReadService.Result.Refused` | `console-refused: ...` | 1 |
| `jsonl` | same | same | 1 |

The dispatch short-circuits on `Refused` BEFORE the format switch, so a refusal
path never reaches the JSONL encoder. This is pinned in two test rows: the
`--format text` refusal contract and the `--format jsonl` refusal contract are
identical except for the flag the caller typed.

## Fitness

### Targeted tests (L1 + L2 = change-scoped verification)

| Class | Tests | Result |
|---|---|---|
| `MainConsoleFormatTest` (new) | 9 | green |
| `MainConsoleCliArgumentContractTest` (existing B1c) | 5 | green |
| `MainConsoleCliArgumentStrictnessTest` (existing AUD-04) | 4 | green |
| `MainConsoleCliRequestStrictnessTest` (existing) | 10 | green |
| `ConsoleReadServiceTest` (existing) | 12 | green |

Canary XMLs under `v2/pipeline-application/build/test-results/test/`:
- `TEST-...MainConsoleFormatTest.xml` — `tests=9 skipped=0 failures=0 errors=0`
- `TEST-...MainConsoleCliArgumentContractTest.xml` — `tests=5 skipped=0 failures=0 errors=0`
- `TEST-...MainConsoleCliArgumentStrictnessTest.xml` — `tests=4 skipped=0 failures=0 errors=0`
- `TEST-...MainConsoleCliRequestStrictnessTest.xml` — `tests=10 skipped=0 failures=0 errors=0`
- `TEST-...ConsoleReadServiceTest.xml` — `tests=12 skipped=0 failures=0 errors=0`

### Pre-existing detekt baseline (out of scope)

`v2/config/detekt/detekt.yml` already documents two allowed over-25 functions. The
pre-existing 4 detekt errors in `:pipeline-application` (`OutputProtocol.kt:98`,
`LaunchReceiptModesTest.kt:276`, `OutputProtocolNegotiationTest.kt:150`,
`ObsPc2ProducerSurvivalSpikeTest.kt:107`) were verified out of scope at base
`347470d4` and remain at the same count after this slice.

The new code added **0 new detekt issues** (verified by `:pipeline-application:detekt`
against this branch's tree).

## Test rows (HF0 + HF1)

| # | Shape | Verdict |
|---|---|---|
| 1 | Closed ADT pin (2 cases, distinct tokens) | PASS |
| 2 | parse round-trip on documented tokens (text, jsonl) | PASS |
| 3 | Unknown --format refused with accepted list on stderr | PASS |
| 4 | --format with no value refused (not silent default) | PASS |
| 5 | Usage line lists the new --format option | PASS |
| 6 | Usage line includes both --format cases | PASS |
| 7 | --format text refusal contract on missing run | PASS |
| 8 | --format jsonl refusal parity (same prefix) on missing run | PASS |
| 9 | ObservationJsonLines envelope shape contract (same writer as observe) | PASS |

## Reference implementation

Jenkinsfile `LogStorage` (jenkinsci/workflow-job-plugin) is the closest analogue:
it persists build logs as a sequence of byte chunks and lets consumers iterate
without re-encoding. The JSONL envelope is a typed layer on top: each chunk becomes
a `{type:"output", channel, ordinal, from, to, text/base64}` record, the same shape
the `output.txt` ingestion path uses (`docs/v2/04-adrs/ADR-OBS-004`). The
`console-refused: ...` stderr line is the same typed-refusal-on-stderr discipline
the events verb already uses (`evt-refusal-v1: ...`).

## End-of-WU closure

| Check | Result |
|---|---|
| Targeted tests green (canary XML) | YES (40/40 across the five classes) |
| New detekt issues introduced | 0 |
| AGENTS.md V2 DEVELOPMENT PRIME DIRECTIVE preserved | YES (hexagonal boundary: `ObservationJsonLines` is in `pipeline-application`; `MainConsoleCli` is over the public SDK; no new public surface) |
| Reference implementation consulted | Jenkins `LogStorage` and the `output.txt` ingestion path; `ObservationJsonLines` is the typed projection of an existing byte stream, not a new wire format |
| Behaviour adopted | `--format jsonl` switches the emitter to the canonical `ObservationJsonLines` writer (one record per line, same shape as `observe --view console --format jsonl`) |
| Intentional deviations | None from the contract; per-frame granularity is a follow-up once the per-operation index is available |
| Security implications | n/a (no credential, network, or process surface) |
| Tests demonstrating the contract | `MainConsoleFormatTest` (9 rows) + 31/31 existing console CLI tests still green |

# Specification — CLI, Views, Streaming and Agentic Inspection

Status: PROPOSED

## 1. View and format are orthogonal

`view` answers **what information** is projected. `format` answers **how it is rendered**.

### Views

- `normal` — default human view; lifecycle + failures + bounded failure transcript context.
- `events` — semantic DomainEvents only.
- `full` — events + console live.
- `console` — console transcript only.
- `quiet` — final outcome/minimal summary.

`-v` aliases `--view full`.

### Formats

- `text` — human readable;
- `jsonl` — streaming machine format, one observation per line;
- `json` — bounded/post-run document; for live `run`, bufferless JSONL is preferred and help must state this.

View/format NEVER alter execution/journal/fingerprint/event production.

## 2. Default behavior

`pipelinek run` on a success does not stream all child output in `normal` mode. It shows important lifecycle and final summary.

On failure, `normal` reads an acotado tail of the failing operation transcript after receiving typed failure. This is a read-side action and cannot block child execution.

## 3. Machine stdout discipline

When `--format jsonl|json` is selected:

- stdout contains only the declared machine payload;
- usage/internal CLI diagnostics go to stderr;
- no banners/color escape sequences pollute stdout;
- exit code remains authoritative.

## 4. Observation records

A live JSONL stream may contain tagged records:

```json
{"type":"event","sequence":42,"kind":"StepStarted","subject":"..."}
{"type":"console","operation":"...","offset":8192,"channel":"merged","text":"..."}
{"type":"diagnostic","severity":"warning","message":"..."}
```

This wire is a CLI observation format, not DomainEvent persistence schema.

## 5. Filters

Minimum filters for history/inspect:

- run;
- stage;
- step;
- event kind;
- outcome/status;
- channel;
- text contains (read-side only);
- sequence/cursor;
- limit/tail/context.

Semantics:

- different dimensions combine with AND;
- repeated values of the same dimension combine with OR;
- no arbitrary query language in LPR.

## 6. Agent-efficient projection

`--fields` restricts output projection after selection:

```bash
pipelinek inspect RUN \
  --failed \
  --fields stage,step,outcome,message,consoleTail \
  --format json
```

It never changes persisted records.

## 7. Cursor/follow

Events continue by `EventCursor`; console continues by operation byte offset. `--follow` uses wakeups for low latency and durable read-after for recovery. Timestamps are display/filter metadata, not continuation authority.

Examples:

```bash
pipelinek events RUN --after-cursor TOKEN --format jsonl
pipelinek events RUN --follow --kind StepFailed
pipelinek logs RUN --step core.sh --tail 100
pipelinek inspect RUN --failed --context 5 --log-tail 100 --format json
```

## 8. Tail behavior

Tail must be bounded. Implement reverse/seek-based reading instead of loading the entire transcript. Default maximum bytes scanned for a line tail should be capped/configurable to protect pathological single-line output.

## 9. Secret handling

Events, transcript and diagnostics are redacted **before durable persistence or public presentation**. A renderer is not a security boundary.

## 10. Event/output separation migration

High-volume `sh` stdout/stderr must stop being duplicated as `EchoOutputCaptured(content)` once compatibility tests prove transcript-based observation. `echo` semantic text may remain an event because it is a semantic Step action, not an arbitrary process stream.

## 11. No total ordering lie

`full` interleaving is a presentation stream. Only event sequence and console offset have authoritative local orders. CLI must not claim that a console line and a DomainEvent share one durable global sequence unless a future ADR introduces such a protocol.

## 12. Exit-code contract (observation commands)

The observation commands (`pipelinek events`, `pipelinek console`) return a status the caller can
branch on without parsing stderr. The status is a decision, not an accident of the code path; it is
pinned by tests (`MainEventsCliArgumentContractTest`, `MainConsoleCliArgumentContractTest`).

Common vocabulary:

- `0` — the command ran and reported what it observed. A read-only observation whose page carries
  refusals, whose run/filter matches nothing, or whose continuation stalls is **still `0`**: the
  refusal or stall is a structured token on **stderr**, not a command failure. `0` never asserts
  that the query matched anything.
- `1` — reserved for a read this command performed and that the plane refused (e.g. an unknown
  stream), when the command's own result type distinguishes "refused" from "answered".
- `2` — the command was **not run**: a usage/argument error. This includes a missing required
  argument, an unknown `--option`, an extra positional argument, and any option value that does not
  parse or is outside its accepted range (`--limit`, `--max-bytes`, `--range`, cursor tokens).
- any other status — an unhandled exception escaped `main`; the JVM produced that status and the
  command did not choose it. A defect, never a designed outcome.

Per command:

| command | statuses |
|---|---|
| `pipelinek events` | `0` (drain `Answered` or `Stalled`), `2` (usage/argument error) |
| `pipelinek console` | `0` (page emitted), `1` (plane refused the read), `2` (usage/argument error) |

A `Stalled` events page is reported on stderr as `evt-stalled-v1:<runId>:<sequence>: ...` and
returns `0`: the store claimed more rows than the continuation could reach, the command reported
exactly that, and a non-zero status would misread a reported stall as a command failure.

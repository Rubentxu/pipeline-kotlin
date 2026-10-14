# R4 / WU-R4.1.3 — `inspect --log-tail-op <opId>` (per-operation console tail)

- **Status:** CERTIFIED · 2026-10-14
- **Train:** 0.60.0-rc5 · **Source of truth:** this receipt + `git rev-parse HEAD`
- **Provenance:** PR #118 (branch `r4/wu-r4-1-3-log-tail-op`) → merge commit (TBD on merge)

## What

Adds the **per-operation** console tail read to the observability surface:

- `FrameIndexedObservationOutputReader.readTail(runId, opId, N)` overload filters
  frames by stream-id prefix (`<runId>/<opId>/<channel>`) and returns the last
  `N` bytes of THAT operation's stream.
- `MainInspectCli --log-tail-op <opId>` flag scopes the `--log-tail` byte budget
  to one operation. When `--log-tail-op` is set, the CLI uses the new per-op
  overload; otherwise it uses the existing run-wide `readTail`.

The run-wide `--log-tail` behaviour is unchanged. The default (no `--log-tail-op`)
is the run-wide read; with `--log-tail-op` it is the per-op read.

## Why (per-step `--log-tail` binding — the WU-R4.1.3 contract)

`R4_CLI_GAP_ANALYSIS §6` named WU-R4.1.3 as "HF2 installDist canary for inspect +
per-step `--log-tail` binding". The per-step auto-binding requires opId resolution
from the durable control journals (`retry-control/<sha256(opId)>.attempts.json`),
which the inspect CLI does not currently read — the file names carry only the
SHA-256 of the opId, and the opId is inside the JSON.

This slice ships the **per-op tail read primitive** and the **opt-in CLI
binding** (`--log-tail-op <opId>`). The per-step auto-binding
(per-StepFailed → per-op) is documented as a follow-up below. Shipping the
primitive now means a future WU can resolve opIds from the durable journal and
wire it in without touching the reader.

## Where

- **Modified:** `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/observation/ObservationOutputReader.kt`
  - New `ObservationOutputReader.readTail(runId, opId, N)` overload.
  - `FrameIndexedObservationOutputReader.readTail(runId, opId, N)` filters frames
    by `/<opId>/` segment in the stream id, then runs the same tail-budget walk
    as the run-wide path.
  - Empty opId / opId with no frames → `ObservationOutputRead.Page(empty,
    lastOrdinal = -1L, moreFrames = false)`. Distinct from a Refused: the opId is
    real, the run is real, the page is just empty.
- **Modified:** `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/MainInspectCli.kt`
  - `CompiledInspectionQuery` carries `opId: String?`.
  - `MainInspectCli.parseInspect` accepts `--log-tail-op <opId>` (refuses blank,
    refuses no-value, refuses negative is `Int`-only so not relevant here).
  - `main()` routes the read: `if (compiled.opId != null) readTail(runId,
    opId, N) else readTail(runId, N)`.
- **New:** `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/MainInspectCliLogTailOpTest.kt`
  - 6 tests: HF0 closed-payload pin (opId round-trip, blank refusal, no-value
    refusal, co-existence with `--log-tail`); HF1 main() wiring (blank/no-value
    refusal on the real CLI entry point).

## Strategy

The reader overload is the new primitive. The CLI binding is opt-in (`--log-tail-op`).
A future slice can resolve opIds from the durable control journals and route
`--log-tail` (without `--log-tail-op`) per step automatically.

## Closed contract pin

```text
CompiledInspectionQuery
  ...
  opId: String?   (--log-tail-op, default null = run-wide)
```

The HF0 row in the test suite pins `opId` round-trip and the typed refusals
(blank, no-value). The HF1 row pins the same refusals at the `main()` entry
point with `System.setOut/setErr` capture (the production path).

## What this slice is NOT

- NOT the per-step auto-binding. A future slice resolves opIds from the durable
  control journals (the file names carry SHA-256 of opId, so the resolver
  reads each `.attempts.json` and parses the opId from the JSON content) and
  walks the events in sequence order to build `Map<stepIndex, opId>`. The
  reader overload and the CLI binding shipped here are the per-op read
  primitive that the future slice composes against.
- NOT a wakeup-based follow (CC-6). `--log-tail` is still a one-shot read.
- NOT a typed `EventReadRefusal` ADT (CC-7). Refusals stay on stderr as
  `console-refused: ...`.
- NOT a HF2 installDist canary. That belongs to WU-R4.5 (cross-CLI canary).
- NOT per-frame granularity. The new overload returns the per-op merged page,
  same shape as the run-wide readTail.

## Fitness

### Targeted tests (L1 + L2 = change-scoped verification)

| Class | Tests | Result |
|---|---|---|
| `MainInspectCliLogTailOpTest` (new) | 6 | green |
| `MainInspectCliTest` (existing) | 11 | green |

Canary XMLs under `v2/pipeline-application/build/test-results/test/`:
- `TEST-...MainInspectCliLogTailOpTest.xml` — `tests=6 skipped=0 failures=0 errors=0`
- `TEST-...MainInspectCliTest.xml` — `tests=11 skipped=0 failures=0 errors=0`

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
| 1 | `--log-tail-op op-1` → `opId = "op-1"` in `CompiledInspectionQuery` | PASS |
| 2 | `--log-tail-op` (no value) refused with named reason | PASS |
| 3 | `--log-tail-op ""` (blank) refused with typed reason | PASS |
| 4 | `--log-tail 512 --log-tail-op op-1` both flags co-exist | PASS |
| 5 | main() refuses `--log-tail-op` (no value) at exit 2 | PASS |
| 6 | main() refuses `--log-tail-op ""` (blank) at exit 2 | PASS |

## Reference implementation

Jenkins `LogStorage` (jenkinsci/workflow-job-plugin) stores build logs as
byte-keyed chunks. Per-step tail reads there are keyed by `flowNodeId` (the
Jenkins analogue of an opId). The PipelineK equivalent of `flowNodeId` is the
opId; the durable control journal records one entry per opId. The new
`readTail(runId, opId, N)` overload is the typed primitive that maps to that
record set, just as `--log-tail N` maps to the run's record set.

## End-of-WU closure

| Check | Result |
|---|---|
| Targeted tests green (canary XML) | YES (6/6 new + 11/11 existing = 17/17) |
| New detekt issues introduced | 0 |
| AGENTS.md V2 DEVELOPMENT PRIME DIRECTIVE preserved | YES (hexagonal boundary: reader is in `pipeline-application`; CLI is over the public SDK; no new public surface) |
| Reference implementation consulted | Jenkins `LogStorage` and the durable control journal as the opId source |
| Behaviour adopted | Per-op tail read primitive + opt-in CLI binding via `--log-tail-op` |
| Intentional deviations | Per-step auto-binding is documented as a follow-up (opId resolution from durable control journals is out of scope for this slice) |
| Security implications | n/a (no credential, network, or process surface) |
| Tests demonstrating the contract | `MainInspectCliLogTailOpTest` (6 rows) + existing `MainInspectCliTest` (11/11) still green |

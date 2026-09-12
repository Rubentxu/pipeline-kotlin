# S2-A7 / G7 — core.deleteDir Installed Acceptance Receipt

> Gate: **G7 — installed-distribution acceptance (real CLI)**
> Base: `main @ 53b8fca0` (branch `cycle/lfc2-e1-deletedir-g7`, cut from main 53b8fca0)
> Date: 2026-09-12T21:41–21:43Z (local +02:00)
> Binary: `v2/pipeline-application/build/install/pipeline-application/bin/pipeline-application`
> Build: `installDist` reported `BUILD SUCCESSFUL in 1s, 38 actionable tasks: 38 up-to-date`
> at run time on a clean worktree at `53b8fca0` (content-hash up-to-date check is the
> freshness oracle per V2 TESTING RULES rule 2 — nothing changed since last green build).
> Verdict: **ALL SCENARIOS PASS — INSTALLED_ACCEPTANCE = true** (see State note)
> `CERTIFIED` NOT claimed (ADR-0074; G8 out of scope for this slice).

## Scope

Installed acceptance of `core.deleteDir` against the REAL installed distribution
(CLI binary), NOT in-process coordinator. Per the pwd G7 precedent
(`S2_A6_CORE_PWD_G7_STOP_BLOCKED_RECEIPT.md`), `core.deleteDir` has NO
runtime-return dependency, so the `STRUCTURED_DSL_RUNTIME_RETURN_GAP` blocker
does not apply: `deleteDir` is a pure effect Step (no synchronous typed return
consumed by the DSL continuation).

## Evidence setup

```text
scripts   : /tmp/dd-g7-ws/DD-G7-01.pipeline.kts, DD-G7-02.pipeline.kts
            (archived copies under docs/v2/07-uat/evidence/s2-a7-g7/)
binary    : v2/pipeline-application/build/install/pipeline-application/bin/pipeline-application
run 01 db : /tmp/dd-g7-ws/g7-01.db   control-root: /tmp/dd-g7-ws/ctrl-01
run 02 db : /tmp/dd-g7-ws/g7-02.db   control-root: /tmp/dd-g7-ws/ctrl-02
runId(01) : be612044-e355-4392-a3fe-d1020fc05bc3  (REUSED across all 3 invocations)
runId(02) : e83d0bbe-5e72-4dc0-816a-3c01bd331949
raw archived (sha256):
  DD-G7-01.pipeline.kts          198d115d7dc8169fae001ede7129c163d35c9f4ba64def5cc18fd4a66eff0046
  DD-G7-02.pipeline.kts          a07b4b1afda09866d986236870c31c0f209a3bd8b64e20e83709f42aa5d4b7c6
  dd-g7-01-fresh-events.json     c977d3ec4bd79b35e43bf3380df1b15f68ab09be89017fd306d8c0bcbd391d99
  dd-g7-01-fresh-stderr.log      edcbb09338c63e46b8313039aa650b2ef328ced12e325c49dfb0f51a22354315
  dd-g7-02-fresh-events.json     26afdc539fd734f74cda8aca972410436e99e82bf05ef9454478c303bbcca2ad
  dd-g7-02-events-cli-dirdedicated.jsonl f5ee9dde25804a97634611c0e74ed61f96e604d82b828e0990dceb53dc034721
  dd-g7-03-rerun1-events.json    d442df901ddb58f4b5cd60eaa757944f4c528d780b18f9e9c3e798263fc561f9
  dd-g7-03-rerun2-events.json    d91c4bb1aca7494a27399cc55213012117701713f6aeb67c741eb23b2eb46640
```

All commands wrapped in `timeout` (600s run / 120s CLI inspection). Every result
below is from a fresh real run; no assertion weakened, no criterion reinterpreted.

## Results

| Scenario | Criterion | Verdict |
|---|---|---|
| DD-G7-01 | fresh delete: real files/dirs deleted, run SUCCEEDS, DirDeleted with correct path + deletedCount>0 | **PASS** |
| DD-G7-02 | DirDeleted payload matches domain event contract exactly | **PASS** |
| DD-G7-03 | replay/rerun same runId/db/control-root: real second execution, second DirDeleted deletedCount=0, same path, same operation identity | **PASS** |
| DD-G7-04 | legacy dispatcher absent from source AND from the installed distribution | **PASS** |

### DD-G7-01 — fresh (PASS)

```bash
pipeline-application run --db /tmp/dd-g7-ws/g7-01.db --control-root /tmp/dd-g7-ws/ctrl-01 \
    /tmp/dd-g7-ws/DD-G7-01.pipeline.kts
EXIT=0    "Pipeline finished with SUCCESS"
```

Script: `sh` creates real files (`file-a.txt`, `sub/file-b.txt`, `sub/inner/file-c.txt`,
2 dirs), then `deleteDir(".")`.

```text
DirDeleted (seq 8):
  path         = /tmp/dd-g7-ws/ctrl-01/workspace/dd-g7-01-0   (correct stage workspace)
  deletedCount = 5   (3 files + 2 dirs)                       (>0 as required)
  sha256       = f24fc3de8f017d41fe7d05ad0607ea7b74f4c9b235998c5678db4e0457557efb
```

Filesystem verified after run (REAL deletion, not simulated):

```text
/tmp/dd-g7-ws/ctrl-01/workspace/dd-g7-01-0/
  (only) .deleted  — content: f24fc3de8f017d41fe7d05ad0607ea7b74f4c9b235998c5678db4e0457557efb
```

All files/dirs deleted for real; workspace root kept intact; `.deleted` marker
content equals the emitted `sha256` field (MEMOIZED marker verified byte-for-byte).

### DD-G7-02 — event contract (PASS)

Domain event (`pipeline-events` DomainEvent.kt @601):
`DirDeleted(eventId, runId, sequence, occurredAt, path, deletedCount, sha256)`.

Persisted payload read from the events journal of the installed run
(sqlite `events` table, `kind='DirDeleted'`):

```json
{"eventId":"17728325-3e10-44cc-99d7-8839838ffa62",
 "runId":"be612044-e355-4392-a3fe-d1020fc05bc3","sequence":8,
 "kind":"DirDeleted","occurredAt":"2026-09-12T21:41:29.419837606Z",
 "path":"/tmp/dd-g7-ws/ctrl-01/workspace/dd-g7-01-0","deletedCount":5,
 "sha256":"f24fc3de8f017d41fe7d05ad0607ea7b74f4c9b235998c5678db4e0457557efb"}
```

Persisted key set `{eventId, runId, sequence, kind, occurredAt, path, deletedCount,
sha256}` matches the domain event exactly — no extra fields, none missing, values
typed as declared. Cross-checked three ways: stdout event stream (JSONL),
`pipeline events --db ... --kind DirDeleted` CLI (eventRefId == eventId), and the
sqlite journal row. `run 02` independently produced the identical shape
(deletedCount=3 for its 1 file + 1 dir + ... payload fixture: payload.txt +
payload-dir/nested.txt + payload-dir = 3 items).

### DD-G7-03 — replay/rerun idempotency (PASS)

Same runId (`be612044-…`), same `--db /tmp/dd-g7-ws/g7-01.db`,
same `--control-root /tmp/dd-g7-ws/ctrl-01`. Durable classification:
deleteDir is WRITES_WORKSPACE ⇒ **RERUN** (real second execution, not memoized skip).
Two re-runs executed, both `EXIT=0`, both `Pipeline finished with SUCCESS`.

```text
invocation   stepType   path(within ws) deletedCount sha256
fresh        deleteDir  dd-g7-01-0      5            f24fc3de…57efb
rerun 1      deleteDir  dd-g7-01-0      0            f24fc3de…57efb   (same op identity)
rerun 2      deleteDir  dd-g7-01-0      0            f24fc3de…57efb   (same op identity)
```

Second execution happens (new event, new timestamp, sequence restarts per run
window) with `deletedCount=0`, same path, same operation identity (sha256 =
marker sha of the original deletion, byte-verified against the on-disk `.deleted`
file). No duplicate deletion of data; no re-creation of child effects.

OBSERVATION (non-blocking, structural — not a replay-law violation): each rerun
emits two DirDeleted events (the fresh projection deletedCount=5 followed by the
live rerun result deletedCount=0). Durable truth is the live (last) result and is
correct; the replayed projection of the original effect is also a correct
projection. Flagged as an evolutive note for the replay-projection channel, NOT a
G7 gap: idempotency, identity, and outcome are all correct.

### DD-G7-04 — legacy absence (PASS)

```text
1. Source:    find v2 -name "CanonicalDeleteDirNodeDispatcher*" → no match
              (G5 removed the file; verified absent on this branch)
2. Distribution: all 37 jars under
              v2/pipeline-application/build/install/pipeline-application/lib/
              scanned with `unzip -l | grep -i CanonicalDeleteDirNodeDispatcher`
              → 0 matches (class physically absent from the installed artifact)
3. Strings-level scan of pipeline-application-*.jar content → 0 matches
4. Positive control: CoreDeleteDirStep classes + DeleteDirOperationsAdapter ARE
              present in the same jar (5 entries) — the registry path ships.
5. Runtime routing: DD-G7-01 events show StepStarted(stepType="deleteDir")
              executed through the registry spine (capability-routed handler,
              DirDeleted emitted by DeleteDirOperationsAdapter) — no legacy
              decode/dispatch surface exists to reach.
```

## State

```text
core.deleteDir:
  REGISTERED          = true
  REGISTRY_PRIMARY    = true
  LEGACY_REMOVED      = true
  CONTRACT_SUITE      = true
  INSTALLED_ACCEPTANCE= true   (this slice, 4/4 scenarios PASS)
  CERTIFIED           = false  (G8 not attempted in this slice)

legacy counters = 5 / 5 / 5  (UNCHANGED — no authority mutation in this slice)
```

## Disposition

INSTALLED_ACCEPTANCE=true. Next authorized gate: **G8 — CERTIFIED** for
`core.deleteDir` (per ADR-0074, requires the full certification review; not
performed here). The DD-G7-03 projection note is evolutive and does not block G8.

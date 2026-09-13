# S2-A10 / G7 — core.cleanWs Installed Acceptance Receipt

> Gate: **G7 — installed-distribution acceptance (real CLI)**
> Base: `cycle/lfc2-e1-cleanws-g6 @ b554c4924c14f64594514fa48e2970c47dfe5bc3` (commit sha256 canary)
> Date: 2026-09-13T10:57–11:01Z (local +02:00)
> Binary: `v2/pipeline-application/build/install/pipeline-application/bin/pipeline-application`
> Build: `installDist` reported `BUILD SUCCESSFUL in 1s, 38 actionable tasks: 38 up-to-date`
> at run time on a clean worktree at `b554c492` (content-hash up-to-date check is the
> freshness oracle per V2 TESTING RULES rule 2). G6 was a doc-only gate (counter tests
> + receipt only — 4 files / +440 / -37), so no rebuild required; binary ships the
> G4+G5 production changes.
> **Verdict: ALL SCENARIOS PASS — `INSTALLED_ACCEPTANCE = true`** (see State note)
> `CERTIFIED` NOT claimed (ADR-0074; G8 out of scope for this slice).

## Scope

Installed acceptance of `core.cleanWs` against the REAL installed distribution
(CLI binary), NOT in-process coordinator. Per the pwd G7 precedent and the
deleteDir G7 precedent (`S2_A7_CORE_DELETEDIR_G7_INSTALLED_ACCEPTANCE_RECEIPT.md`),
`core.cleanWs` has NO runtime-return dependency on synchronous typed values
consumed by the DSL continuation — `cleanWs` is a pure effect Step (the `WsCleaned`
event is observable but no downstream Step depends on a typed value from the
handler). Therefore the `STRUCTURED_DSL_RUNTIME_RETURN_GAP` blocker does not apply,
matching the `core.deleteDir` precedent.

The G5 cleanup (precedent `S2_A10_CORE_CLEANWS_G5_LEGACY_REMOVED_RECEIPT.md`):
1. `data class CanonicalCoreStepCommand.CleanWs` removed from `CanonicalCoreStepDecoder.kt`.
2. `CanonicalCoreStepMetadata` row `core.cleanWs` removed.
3. `cleanWsDispatcher` field removed from `CanonicalNodeDispatcher.kt` (zero concrete-Step branches).
4. `canonical/.../CanonicalCleanWsNodeDispatcher.kt` file DELETED entirely.
5. DSL `cleanWs(...)` producer migrated to `StepSpec.RegistryStepSpec` direct.
6. `CoreCleanWsDifferentialContractTest.kt` DELETED (the LEGACY_REMOVED leg closed).

Therefore the G7 scenarios are the first installed acceptance to exercise the
**registry path end-to-end** (no legacy fallback, no compat shim) — `core.cleanWs`
was burned down through G4/G5/G6/G7 in this slice.

## Evidence setup

```text
scripts   : /tmp/cw-g7-ws/CW-G7-01.pipeline.kts, CW-G7-03.pipeline.kts
            (archived copies under docs/v2/07-uat/evidence/s2-a10-g7/)
binary    : v2/pipeline-application/build/install/pipeline-application/bin/pipeline-application
binary jar: pipeline-application-0.1.0-SNAPSHOT.jar  sha256=2c0b24847846f563e1d1f8031b757e73c81dbd52375067ccb0578b144b8b6e57
run 01 db : /tmp/cw-g7-ws/g7-01.db   control-root: /tmp/cw-g7-ws/ctrl-01   runId=f130d70b-ffd7-499d-843c-0045bd5bb66f
run 02 db : /tmp/cw-g7-ws/g7-02.db   control-root: /tmp/cw-g7-ws/ctrl-02   runId=30841d27-5db1-42b9-965d-2d27acd1b27b
run 03 db : /tmp/cw-g7-ws/g7-03.db   control-root: /tmp/cw-g7-ws/ctrl-03   runId=eff6d847-0907-45f6-926c-0ff5c7a067a1
raw archived (sha256):
  CW-G7-01.pipeline.kts                       199703c0bba11f1a357b2b3d261fc64138ecca248002ce30da90e182cc79e75f
  CW-G7-03.pipeline.kts                       c84b3f6f9488b2f376b9d135da3d66ed3b0efec376e4559830a370b6e1076577
  cw-g7-01-fresh-events.json                  690b71d0af735d12153c338d5975943ba8df4607a1c3833c31095f74b493a1ba
  cw-g7-01-fresh-stderr.log                   ad1e8158cdffc79d42bc2512649292de4c88cf7d8f286d29b96e2ca7ee94cf17
  cw-g7-01-runid.txt                         c99507bdefa298879679ca8c56cd4d6e2b2dc379589ff395e32ab5252651f924
  cw-g7-02-events-cli-wscleaned.jsonl         4435114a835fcf958ae85a515e7d2f22e7340b813fcf0b249c32858aaae939e8
  cw-g7-02-fresh-events.json                  87c227927bc9f28311031803a2597570e83bc7fcf3befd7a8b6754a30798d2f7
  cw-g7-03-rerun1-events.json                 aa1ff71cca55a8aa4ec4d59e16132591d8be0d69bf85e2d3e36ca5c11bade6db
  cw-g7-03-rerun1-stderr.log                  3c8786efad2500cdad720038c9bc833db23408975d26a2b492ddacbf09015b47
  cw-g7-03-rerun2-events.json                 0dfe7cff72853e7383b55e00cae6683922e90fd9f576e23f02969236163d86e7
  cw-g7-03-rerun2-stderr.log                  0e81ba6a83771780449ff750559e51af21bcade905f4f0d6a77c4d7f822b1227
```

All commands wrapped in `timeout` (600s budget per V2 rule 4 for inner-loop runs).
Every result below is from a fresh real run; no assertion weakened, no criterion
reinterpreted.

## Results

| Scenario | Criterion | Verdict |
|---|---|---|
| CW-G7-01 | fresh `cleanWs(patterns = listOf("test/**"))`: real files/dirs deleted on disk; WsCleaned event emitted with deletedFiles>0 and deletedDirs>0; `.cleaned` marker sha equals event sha256 | **PASS** |
| CW-G7-02 | WsCleaned persisted payload key-set matches domain event exactly; CLI cross-reference via `pipeline events --kind WsCleaned` returns matching eventRefId | **PASS** |
| CW-G7-03 | rerun deleteAll same runId/db/control-root: live second execution, real WsCleaned with deletedFiles=0, deletedDirs=0, **same sha256 as fresh** (idempotency by memoized marker) | **PASS** |
| CW-G7-04 | legacy dispatcher absent from source AND from all 37 distribution jars; positive control: registry classes present in same jar; runtime stepType/stepName confirms registry path | **PASS** |

### CW-G7-01 — fresh (PASS)

```bash
pipeline-application run --db /tmp/cw-g7-ws/g7-01.db --control-root /tmp/cw-g7-ws/ctrl-01 \
    /tmp/cw-g7-ws/CW-G7-01.pipeline.kts
EXIT=0    "Pipeline finished with SUCCESS"
```

Script:

```kotlin
pipeline {
    stages {
        stage("cw-g7-01") {
            sh("mkdir -p test/sub && echo alpha > test/a.txt && echo beta > test/b.txt && echo gamma > test/sub/c.txt && echo keep > keep.txt")
            cleanWs(patterns = listOf("test/**"))
        }
    }
}
```

**Real deletion** (filesystem verified post-run):

```text
/tmp/cw-g7-ws/ctrl-01/workspace/cw-g7-01-0/
  .cleaned    — content: 57e6a9d2df5ae2720e24a4c48206289578b5407ff55bb7b145ea51d6178d9924
  keep.txt    — content: keep   (preserved — did not match the pattern)

(test/a.txt, test/b.txt, test/sub/c.txt all REMOVED from disk;
 test/sub/ and test/ directories REMOVED — empty parents deleted by deleteDirs=true default)
```

Emitted event (delivered through stdout JSONL AND persisted in sqlite events
table for run `f130d70b-…`):

```json
{
  "eventId": "f77e78ac-488e-4720-a064-86aff7e0c045",
  "runId":   "f130d70b-ffd7-499d-843c-0045bd5bb66f",
  "sequence": 8,
  "kind":    "WsCleaned",
  "occurredAt": "2026-09-13T10:59:33.153218065Z",
  "deletedFiles": 3,
  "deletedDirs":  2,
  "patterns":     ["test/**"],
  "sha256":       "57e6a9d2df5ae2720e24a4c48206289578b5407ff55bb7b145ea51d6178d9924"
}
```

deletedFiles=3 (a.txt, b.txt, sub/c.txt), deletedDirs=2 (`test/sub/`, `test/`),
patterns=["test/**"], sha256=`57e6a9d2…`. The on-disk `.cleaned` marker content
is **byte-identical** to the emitted `sha256`. `keep.txt` survived because it
does not match `test/**`.

Routing observation (consistent with deleteDir G7):

```text
stepName=cw-g7-01/registrystep-0   stepType=cleanWs
```

`registrystep-0` is the structural step-name emitted by the `StepSpec.RegistryStepSpec`
lowering (G5 migration replaced `stepType=cleanWs-legacy` with this canonical name);
the runtime reached the registry spine, NOT the legacy `CanonicalCoreStepCommand.CleanWs`
dispatcher that was physically removed in G5.

### CW-G7-02 — event contract (PASS)

Two independent runs of the same script (`CW-G7-01.pipeline.kts`) on fresh
dbs/control-roots. Persisted `WsCleaned` key set is identical:

| Field | Run 01 (`f130d70b-…`) | Run 02 (`30841d27-…`) |
|---|---|---|
| eventId | `f77e78ac-…` | `fddee830-…` |
| runId | `f130d70b-…` | `30841d27-…` |
| sequence | 8 | 8 |
| kind | `WsCleaned` | `WsCleaned` |
| occurredAt | `2026-09-13T10:59:33.153218065Z` | `2026-09-13T11:00:01.085855378Z` |
| deletedFiles | 3 | 3 |
| deletedDirs | 2 | 2 |
| patterns | `["test/**"]` | `["test/**"]` |
| sha256 | `57e6a9d2…` | `a231f749…` (different — marker timestamp varies between runs) |

The persisted key set `{eventId, runId, sequence, kind, occurredAt, deletedFiles,
deletedDirs, patterns, sha256}` matches the domain event declaration
(`pipeline-events DomainEvent.kt` line 621) exactly — no extra fields, none missing,
values typed as declared. `sha256` differs across runs because the `.cleaned`
marker content embeds `System.currentTimeMillis()`; that's a runtime stamp, not
a contract drift.

**CLI cross-validation** (read-back via the canonical `pipeline events` filter):

```bash
pipeline-application events --db /tmp/cw-g7-ws/g7-01.db \
  f130d70b-ffd7-499d-843c-0045bd5bb66f --kind WsCleaned
```

Returned:

```json
{"version":1,
 "eventRefSource":{"kind":"RUN","segments":["pipeline","run","f130d70b-ffd7-499d-843c-0045bd5bb66f"]},
 "eventRefId":"f77e78ac-488e-4720-a064-86aff7e0c045",
 "kind":"WsCleaned",
 "occurredAt":"2026-09-13T10:59:33.153218065Z",
 "sequence":8,
 "subject":{"kind":"RUN","segments":["pipeline","run","f130d70b-ffd7-499d-843c-0045bd5bb66f"]},
 "causationSource":null,"causationId":null,"correlationSource":null,"correlationId":null}
```

CLI `eventRefId` = `f77e78ac-…` matches journal `eventId` exactly. Three-way
cross-check (stdout JSONL, sqlite journal, `pipeline events` CLI) all agree.

### CW-G7-03 — replay/rerun idempotency (PASS)

Script: `cleanWs()` with default `deleteDirs=true` and `patterns=null` →
`deleteDirs=true`, effectivePatterns=`[]` (delete all non-`.v2/artifacts`).

Memorialization law (LB-02 §RecoveryPolicy): the existing `.cleaned` marker
contains the sha256 of the **first** execution; on replay with
`effectivePatterns.isEmpty()`, the executor reads the marker, detects the
already-cleaned workspace, and returns the **same sha256** with deletedFiles=0
and deletedDirs=0 — no second physical deletion, no data loss, no marker drift.

```bash
pipeline-application run --db /tmp/cw-g7-ws/g7-03.db --control-root /tmp/cw-g7-ws/ctrl-03 \
    /tmp/cw-g7-ws/CW-G7-03.pipeline.kts
EXIT=0    "Pipeline finished with SUCCESS"   # fresh execution
pipeline-application run --db /tmp/cw-g7-ws/g7-03.db --control-root /tmp/cw-g7-ws/ctrl-03 \
    /tmp/cw-g7-ws/CW-G7-03.pipeline.kts
EXIT=0    "Pipeline finished with SUCCESS"   # rerun (same --db, same --control-root, same script)
```

Same runId (`eff6d847-…`), same `--db`, same `--control-root`:

| invocation | stepType | deletedFiles | deletedDirs | sha256 |
|---|---|---|---|---|
| fresh | cleanWs | 3 | 2 | `58a13f90691e50deb3d17d675fb29e7a5d2d46cfd84943a18479210e3c826aa9` |
| rerun 2 (replayed projection) | cleanWs | 3 | 2 | `58a13f90691e50deb3d17d675fb29e7a5d2d46cfd84943a18479210e3c826aa9` |
| rerun 2 (live execution) | cleanWs | 0 | 0 | `58a13f90691e50deb3d17d675fb29e7a5d2d46cfd84943a18479210e3c826aa9` |

The rerun 2 invocation emits TWO WsCleaned events (same as the deleteDir G7
precedent): seq=8 is the durable **projection replayed** from the original
execution (deletedFiles=3, deletedDirs=2), seq=6 is the **live rerun result**
(deletedFiles=0, deletedDirs=0). Both events share the **same sha256**
(marker reuse is the only authority, and the on-disk `.cleaned` file content
byte-matches `58a13f90…`). The marker file is the single writer per workspace
(SDK law in `CleanWsExecutor.execute()` — lines 132–138).

Marker on disk:

```text
/tmp/cw-g7-ws/ctrl-03/workspace/cw-g7-03-0/.cleaned
  content: 58a13f90691e50deb3d17d675fb29e7a5d2d46cfd84943a18479210e3c826aa9
```

Idempotency holds: same workspace, same memoized sha256, no double-deletion,
no silent data loss, no marker drift.

OBSERVATION (non-blocking, structural — same shape as deleteDir G7 DD-G7-03 note):
each rerun emits two WsCleaned events (the replayed projection followed by the
live rerun result). The replay is a correct projection of the original effect;
the live run is the correct idempotent outcome. Durable truth is the live (last)
result and is correct; this is a known replay-projection channel property, not a
G7 gap: idempotency, identity, and outcome are all correct.

### CW-G7-04 — legacy absence (PASS)

```text
1. Source:    find v2 -name "*CleanWsNodeDispatcher*" → no match
              (G5 deleted the file; verified absent on this branch)
              find v2 -name "CanonicalCleanWsNodeDispatcher*" → no match
2. Distribution: all 37 jars under
              v2/pipeline-application/build/install/pipeline-application/lib/
              scanned with `unzip -l | grep -iE "CleanWsNodeDispatcher|CleanWsDispatchContext"`
              → 0 matches (class physically absent from the installed artifact)
3. Positive control: pipeline-application-0.1.0-SNAPSHOT.jar contains
              dev/rubentxu/pipeline/v2/application/CoreCleanWsStep.class
              dev/rubentxu/pipeline/v2/application/CoreCleanWsStep$inputCodec$1.class
              dev/rubentxu/pipeline/v2/application/CoreCleanWsStep$outputCodec$1.class
              dev/rubentxu/pipeline/v2/application/CoreCleanWsStep$capabilityRoutedHandler$1.class
              dev/rubentxu/pipeline/v2/application/CoreCleanWsStep$definition$1.class
              dev/rubentxu/pipeline/v2/application/durable/CleanWsOperationsAdapter.class
              dev/rubentxu/pipeline/v2/application/durable/CleanWsOperations.class
              dev/rubentxu/pipeline/v2/application/CleanWsInput.class
              dev/rubentxu/pipeline/v2/application/CleanWsResult.class
              dev/rubentxu/pipeline/v2/application/CleanWsOutput.class
              + files-0.1.0-SNAPSHOT.jar: CleanWsExecutor.class (SDK substrate)
              (registry path ships)
4. Strings-level scan: zero matches for "CleanWsNodeDispatcher" in jar contents.
5. Runtime routing: CW-G7-01 events show
   stepType=cleanWs, stepName=cw-g7-01/registrystep-0
   (registry spine path; no canonical decoder/dispatcher path exists to reach).
```

G5 consequence (`S2_A10_CORE_CLEANWS_G5_LEGACY_REMOVED_RECEIPT.md`): the legacy
`CanonicalCoreStepCommand.CleanWs` sealed subtype is gone; `CanonicalCoreStepDecoder`
no longer emits `CleanWs`; `CanonicalNodeDispatcher` has no `cleanWsDispatcher`
field and no `CleanWs` when-branch (it has 3 remaining branches for the registry-
primary core steps that the G5 burn-down reclassified as `REGISTRY_PRIMARY`/not-yet-
burned-down).

## State

```text
core.cleanWs:
  REGISTERED          = true   (G1)
  REGISTRY_PRIMARY    = true   (G4)
  LEGACY_REMOVED      = true   (G5)
  CONTRACT_SUITE      = true   (G6)
  INSTALLED_ACCEPTANCE= true   (this slice, 4/4 scenarios PASS)
  CERTIFIED           = false  (G8 not attempted in this slice)

legacy counters = 3 / 3 / 3  (UNCHANGED — no authority mutation in this slice;
                               the G5 counter 3/3/3 state is preserved exactly).
```

## Disposition

`INSTALLED_ACCEPTANCE=true`. Next authorized gate: **G8 — CERTIFIED** for
`core.cleanWs` (per ADR-0074, requires the full certification review; not
performed here). The CW-G7-03 replay/projection observation is evolutive and
does not block G8.

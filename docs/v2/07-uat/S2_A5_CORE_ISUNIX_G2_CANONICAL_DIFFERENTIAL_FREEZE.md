# S2-A5 / G2 — CANONICAL DIFFERENTIAL + CONTRACT FREEZE: `core.isUnix`

**Gate:** G2 — differential + canonical contract freeze. NO authority flip, NO legacy removal, NO DSL change. **STOP after this gate.**
**Base:** `0c0f2f4c` (G1).

## Normative decisions

```text
D1: Execution-target PlatformIdentity is the future authoritative environment
    observation for core.isUnix. (Jenkins reference: isUnix answers about the node
    where the step runs, not the controller that built the pipeline.)

D2: IsUnixOutput(Boolean) is an APPROVED durable typed output
    (TYPED_RUNTIME_OUTPUT = APPROVED_ARCHITECTURAL_DELTA).
    Resume reproduces the persisted observation; it does not re-evaluate the platform.

D3 (DEBT, OPEN): PipelineDsl.isUnix(): Boolean is currently eager construction-time
    compatibility behavior and is NOT yet backed by the durable runtime output.
    This contradiction MUST be resolved or explicitly bounded before S2-A5 final
    certification. BLOCKS_FINAL_CERTIFICATION = true. BLOCKS_G4 = decision required
    after a G2R runtime-return spike (echo("is unix: ${isUnix()}") must never be
    able to contradict UnixDetected.isUnix). NOT designed in this gate.
```

## Authorities after G2

```text
AUTHORITY OF OBSERVATION:        execution target PlatformIdentity (capability bridge)
AUTHORITY OF CLASSIFICATION:     ONE canonical pure classifier:
                                 UnixPlatformClassifier.classifyUnix (contract C2)
AUTHORITY OF DURABLE RETURN:     IsUnixOutput(Boolean) — approved, persisted as
                                 encodedOutput, replay-governed by MEMOIZED
PATH_A (PipelineDsl.isUnix):     compatibility surface, NOT runtime authority, UNTOUCHED
```

G2 solves the **classification split** (two policies -> one). The **observation-time
split** (construction vs execution) remains open as D3 and is a separate later gate.

## Canonical contract C2 (frozen)

```text
normalize = trim().lowercase(); membership EXACT on:

linux  macos  mac os x  darwin  sunos  aix  hp-ux  freebsd  openbsd  netbsd

C2 = intent(PATH_A) + JVM-real alias from PATH_B ("mac os x")
     - PATH_A's fake empty placeholder - PATH_B's substring heuristics

Unknown platforms -> false. Extension (Solaris, UnixWare, ...) requires a real case.
```

## Full differential matrix (UNKNOWN_DIFFERENTIALS = 0)

Columns: A = `PipelineDsl.isUnix` exact membership ("" placeholder), B = legacy
dispatcher substring, C-G1 = candidate at `0c0f2f4c` (PATH_B verbatim), TARGET = C2.

| os.name        | A | B | C-G1 | TARGET | verdict |
| ---            | - | - | ---- | ------ | ------- |
| Linux / linux  | T | T | T    | T      | AGREEMENT |
| macos          | T | T | T    | T      | AGREEMENT |
| Mac OS X       | F | T | T    | T      | APPROVED_FIX (B discovered the real JVM alias) |
| mac os x       | F | F | F    | T      | APPROVED_FIX (normalized alias) |
| Darwin         | T | T | T    | T      | AGREEMENT |
| SunOS          | T | F | F    | T      | APPROVED_CONTRACT_DELTA |
| AIX            | T | F | F    | T      | APPROVED_CONTRACT_DELTA |
| HP-UX / hp-ux  | T | F | F    | T      | APPROVED_CONTRACT_DELTA (G2 addition per review) |
| FreeBSD        | T | T | T    | T      | AGREEMENT |
| OpenBSD        | T | F | F    | T      | APPROVED_CONTRACT_DELTA |
| NetBSD         | T | F | F    | T      | APPROVED_CONTRACT_DELTA (G2 addition per review) |
| ""             | T | F | F    | F      | APPROVED_FIX (placeholder retired) |
| "   "          | — | — | —    | F      | APPROVED_FIX (trim normalization) |
| Windows 11 / windows | F | F | F | F   | AGREEMENT |
| unknown        | F | F | F    | F      | AGREEMENT |
| OS/2           | F | F | F    | F      | AGREEMENT |
| Smacos         | F | T | T    | F      | APPROVED_FIX (substring "mac" retired) |

Deltas over G1: `SunOS, AIX, HP-UX, OpenBSD, NetBSD, mac os x, Smacos` reclassified;
`""` stays false. All rows are machine-asserted in `CoreIsUnixStepUnitTest`
(18/0, fresh XML): TARGET match per row, UNKNOWN=0, every diverging row labeled
`APPROVED_*`, G1 semantics explicitly retired, purity/idempotence pinned.

## Durable law (D2, tested)

```text
fresh / rerun : PlatformIdentity observed -> classifyUnix -> IsUnixOutput(Boolean)
                -> encodedOutput persisted; UnixDetected carries the SAME Boolean
resume / reuse: handler NOT executed; platform NOT re-observed; persisted output
                and the original UnixDetected are reused (MEMOIZED)
```

A "stale" observation after an OS change under `--resume` is correct durable
semantics: resume reproduces previous execution truth; it never silently
re-evaluates environmental truth. `--rerun` is a new execution and re-observes.
Tests pin: encoded-output persistence round-trip (journal consumes the encoded
form, never the typed object) and MEMOIZED as the reuse authority.

## Untouched invariants (asserted)

```text
LEGACY_PLUGIN_IDS = 8, metadata = 8, dispatchers = 8   (counters 8/8/8)
StructuralFamily(core.isUnix) = LegacyCore             (no authority flip)
PipelineDsl.isUnix() / runtimeConfig.osName()          (unchanged)
CanonicalIsUnixNodeDispatcher                          (unchanged)
```

Regression evidence: L3 suites (CoreSleep/CoreEmitEvent/CoreError fitness + unit)
green; `pipeline-architecture-tests` module green after the classifier extraction.

## Status

```text
core.isUnix:
  REGISTERED         = true
  REGISTRY_PRIMARY   = false
  LEGACY_UNREACHABLE = false
  LEGACY_REMOVED     = false
  CONTRACT_SUITE     = false
  CERTIFIED          = false
```

**G2 COMPLETE. STOP.** Next: G2R runtime-return spike (D3 scoping) before G3, per the
review decision — registry-primary must not be reached while the script-visible value
can contradict `UnixDetected`.

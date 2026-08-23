# UAT_EVT_001 Evidence — Event Replay

## Test Commands

### Run UAT Evt Tests
```bash
./gradlew -p v2 :pipeline-application:test --tests '*UatEvt*'
```

### Run Full V2 Check
```bash
./gradlew -p v2 clean check
```

### Run Architecture Tests
```bash
./gradlew -p v2 :pipeline-architecture-tests:test
```

## Last-Run Verification (M1-R2)

- `./gradlew -p v2 :pipeline-application:test` → 3 tests (UatEvt001ReplayTest: cli run emits parseable JSON array, re-parsed timeline equals original with correct kinds, two cli invocations yield structurally equal timelines), 0 failures, 0 ignored.
- `./gradlew -p v2 clean check` → BUILD SUCCESSFUL across all V2 modules.
- FArch003 containment pass: `kotlin.script.experimental` only in `pipeline-scripting-kotlin24` (adapter) + `pipeline-architecture-tests` (test fixtures) + `pipeline-events` (own events, no experimental imports).
- `grep -rn "wholeClasspath" v2/` → only comment-only matches in `Kotlin24ScriptingHost.kt` design rationale. No runtime `wholeClasspath = true`.
- `grep -rn "pipeline-steps-system" v2/` → only in architecture test fixtures (FArch004).
- `grep -rn "kotlin.script.experimental" v2/pipeline-events/src/main/` → 0 (F-ARCH-003 allowlist extended).

## Expected Outputs

### UatEvt001 — CLI emits JSON event log

- **Expected**: `pipeline run hello.pipeline.kts` produces stdout that starts with `[` and ends with `]`, parses as a JSON array.
- **Key assertions**:
  - `stdout.first() == '['` and `stdout.last() == ']'`
  - `JsonEventLog.decode(stdout).size >= 4`
  - `events[0].kind == "RunStarted"`
  - `events[1].kind == "CompilationStarted"`
  - `events[2].kind == "CompilationFinished"`
  - `events[3].kind == "RunFinished"`
  - `events[2].cacheKey.version == "v1"`
  - `events[2].cacheKey.value` is 64-char hex

### UatEvt001 — Replay equality

- **Expected**: Two invocations of the same script produce structurally equal event timelines (same kinds, runId, sequence, cacheKey fields).
- **Key assertions**: Two runs yield equal `kind`, `runId`, `sequence`, `cacheKey.version`, `cacheKey.value`, `outcome` fields.

## PASS Criteria

1. CLI run produces valid JSON array (parseable, ≥ 4 events).
2. `events[0].kind == "RunStarted"`, `events[3].kind == "RunFinished"`.
3. `events[2].cacheKey.version == "v1"` and `cacheKey.value` is 64-char hex.
4. Two invocations yield structurally equal timelines.
5. Architecture test FArch003 passes (allowlist includes `/pipeline-events/`).
6. `grep -rn "wholeClasspath" v2/` returns only comment references.
7. `grep -rn "kotlin.script.experimental" v2/pipeline-events/src/main/` → 0.

## JSON Wire Shape

```json
[{"eventId":"...","runId":"...","sequence":1,"kind":"RunStarted","occurredAt":"2026-08-23T10:00:00Z","scriptPath":"hello.pipeline.kts"},
 {"eventId":"...","runId":"...","sequence":2,"kind":"CompilationStarted","occurredAt":"..."},
 {"eventId":"...","runId":"...","sequence":3,"kind":"CompilationFinished","occurredAt":"...","cacheKey":{"value":"<64hex>","version":"v1"},"diagnostics":[]},
 {"eventId":"...","runId":"...","sequence":4,"kind":"RunFinished","occurredAt":"...","outcome":"success","diagnostics":[]}]
```

## Verification Status

- [x] UatEvt001 passes (JSON array, correct kinds, v1 cache key, replay equality).
- [x] FArch003 allowlist includes `/pipeline-events/`.
- [x] `wholeClasspath` grep clean in production path.
- [x] `kotlin.script.experimental` containment for `pipeline-events` as specified.

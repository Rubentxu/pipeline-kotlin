# LFC-2R / R3 — Compiler-backed Runtime-Step Mapping (isUnix)

Status: **COMPLETE (R3 exit contract satisfied, 10/0 new + full regression green)**
Scope: source mapping of `isUnix()` call sites in scripted sources to typed,
registry-routed runtime calls via compiler (PSI) analysis. **No Main/CLI wiring,
no authority flip, no legacy removal** (explicit user scope).

## What was built

### 1. ADT generalization (`pipeline-scripting-api/ScriptedExecutionApi.kt`)

- `enum class ScriptedCallKind { Shell, IsUnix }`
- `data class ScriptedMappedCall(kind: ScriptedCallKind, location: ScriptedSourceLocation)`
- `ScriptedSourceMapping.Mapped(calls: List<ScriptedMappedCall>)` with a
  back-compat `shellCalls` view (Shell rows only). Existing shell-only
  consumers keep compiling and behaving unchanged.

### 2. Mapper extension (`pipeline-scripting-kotlin24/KotlinScriptedSourceMapper.kt`)

- PSI-backed detection of argument-less **unqualified** `isUnix()` calls.
- Qualified receiver calls (`facade.isUnix()`) and occurrences inside
  comments/string literals are ignored (PSI element types, no regex).
- Emits `ScriptedMappedCall(ScriptedCallKind.IsUnix, location)` rows; location
  carries line/column/source text for call-site identity derivation.

### 3. Deterministic lowering (`pipeline-scripting-kotlin24/ScriptedSourceLowering.kt`, NEW)

- Input: mapped calls from the real mapper. Output: rewritten script source
  where each `isUnix()` becomes
  `steps.isUnix(ScriptedCallSiteId("<sourceId>:<line>:<col>:isUnix"))`
  (reverse-offset walk so earlier edits never shift later positions).
- Body wrapped in a generated `object : CompiledScriptedEntryPoint`.
- `FACADE_SCHEMA_VERSION = "facade-r3-isUnix-v1"` feeds `facadeSchemaDigest` —
  a lowering change invalidates previously persisted artifacts per the
  compatibility law.
- **No eager evaluation, no `RuntimeConfig.osName`, no `System.getProperty`,
  no `UnixPlatformClassifier`, no hand-written output decoder** anywhere in
  the generated/lowering path (arch-fitness-scanned, see §Proof).

### 4. Compiler mapping proof (`pipeline-application/.../ScriptedIsUnixCompilerMappingTest.kt`, NEW — 10/0)

Fixture: real-shaped script (`val unix = isUnix()` driving a genuine Kotlin
`if/else` branch appending to a script-local `recorded` list), compiled and
evaluated **once** via `Kotlin24ScriptingHost`. Results read through the
compiled script instance by reflection.

| # | Row | Result |
|---|-----|--------|
| 1 | Real `isUnix()` detected by mapper in real source | PASS |
| 2 | Commented `// isUnix()` ignored | PASS |
| 3 | `isUnix()` inside string literal ignored | PASS |
| 4 | Qualified `facade.isUnix()` ignored | PASS |
| 5 | Call-site identity stable: source-derived `<src>:<line>:<col>:isUnix` | PASS |
| 6 | Call-site identity independent of checkout-path / cwd | PASS |
| 7 | RUNTIME Windows target on Linux host → `false` → branch `windows` | PASS |
| 8 | RUNTIME SunOS target on Linux host → `true` → branch `unix` | PASS |
| 9 | Boolean controls a genuine Kotlin branch (recorded value) | PASS |
| 10 | COMPATIBILITY: facade==persisted==UnixDetected on reuse; fresh schema identity changes with version | PASS |

Single script evaluation per run (no script-repeat design), per R3 exit
contract.

## Exit-contract compliance

- Real `isUnix` detected, comments/strings/qualified ignored: rows 1–4.
- Stable source-derived callSite, checkout-path independent: rows 5–6.
- Real source compiles via `Kotlin24ScriptingHost`: fixture path.
- No eager `RuntimeConfig`: lowering scans + arch tests.
- Single script evaluation: host evaluated once per test.
- Windows→windows, SunOS→unix, Boolean drives branch: rows 7–9.
- facade==persisted==UnixDetected; fresh/reuse; artifact identity changes with
  schema/compiler incompatibility: row 10.
- No regex mapping: detection is PSI element-type based; lowering is
  AST-position based.

## Verification evidence

All counts are fresh JUnit XML results (canary: XMLs deleted before run).

| Suite | Result |
|---|---|
| `ScriptedIsUnixCompilerMappingTest` (new) | 10/0 |
| `ScriptedIsUnixRuntimeTest` (R2, regression) | 13/0 |
| `ScriptedRegistryInvokerTest` (R1-era, regression) | 10/0 |
| `KotlinScriptedSourceMapperTest` | 1/0 |
| `CompiledScriptedEntryPointHostTest` | 1/0 |
| `pipeline-architecture-tests` (all 53 classes, `--rerun-tasks` canary) | 0 failures |

Pre-existing reds (NOT regressions, base-vs-head proven at `39472d1e` in a
separate worktree — identical failure counts):

- `ScriptTextEscaperTest` 15/3 (base 15/3)
- `WithCredentialsCompileIntegrationTest` 6/4 (base 6/4)

Out of R3 scope (unchanged by construction): `Main.kt`, CLI run-mode, authority
flip, S2-A5 counters (remain 8/8/8 REGISTERED-only), legacy catalogue.

## Durable laws honored

- `--resume` reproduces the persisted observation (never re-observes platform):
  reuse path returns persisted value with 0 platform reads (R2 runtime suite
  continues to hold; R3 lowering emits the same registry-routed call).
- Reuse needs no capabilities; fail-closed on absent capability/invoker.
- Durable decision happens before admission; no fabricated Boolean anywhere.

## Blocked-remaining

D3 remains OPEN; after R3 the only blocker is production wiring (Main/CLI),
i.e. R4.

Counters (S2-A5): Certified 8 / Legacy-executable 8 / Registry-primary 8 —
REGISTERED-only, unchanged in R3.

# INC-R10-ARC-001 — Fail-Open on Script Compilation Failure

## Classification

- **Type**: Production Bug (INC)
- **Cycle**: `p-733fb505b5a6bd2d/ml-r10-credentials-parity`
- **Branch**: `feat/ml-r10-credentials-parity`
- **Status**: Fixed in cycle
- **Filed**: 2026-08-30

## Summary

The pipeline runner was returning exit code 0 (success) when a script failed to compile, making `CompatibilityCorpusTest.fixture14` and `UatCompat001` pass vacuously. This voided the L5 green for the credentials capability.

## Evidence — Vacuous Green

**Before fix** (exit 0 despite compilation failure):

```bash
$ pipeline run v2/compatibility/14-credentials-bindings.pipeline.kts
[{"kind":"RunFinished","outcome":"failure","diagnostics":[...]}]
EXIT CODE: 0  # ← BUG: should be non-zero
```

The `RunFinished` event correctly showed `outcome: "failure"` with diagnostics, but the CLI exited 0, causing tests to pass without actually exercising the credential binding functionality.

## Root Cause

In `Main.kt` (non-durable path, lines 186-193), the `execute()` function was called and events were printed, but the `RunFinished.outcome` was never checked to set the exit code:

```kotlin
// BEFORE (buggy)
if (config.dbPath == null) {
    val rawStore = InMemoryEventStore()
    val store = RedactingEventSink(rawStore, secretPatternRegistry)
    val events = execute(scriptPath, store)
    println(JsonEventLog.encode(events))
    return  // ← exits 0 regardless of outcome
}
```

## Fix — Fail-Closed Exit Code Mapping

The non-durable path now checks the `RunFinished.outcome` and propagates failure to exit code:

```kotlin
// AFTER (fixed)
if (config.dbPath == null) {
    val rawStore = InMemoryEventStore()
    val store = RedactingEventSink(rawStore, secretPatternRegistry)
    val events = execute(scriptPath, store)
    println(JsonEventLog.encode(events))
    val lastEvent = events.lastOrNull()
    val runOutcome = if (lastEvent is RunFinished) lastEvent.outcome else "success"
    when (runOutcome) {
        "success", "unstable" -> {
            System.err.println("Pipeline finished with ${runOutcome.uppercase()}")
        }
        else -> {
            System.err.println("Pipeline finished with FAILURE")
            System.exit(1)  // ← non-zero on failure
        }
    }
    return
}
```

## Test Receipts

### RED evidence (before fix)

```bash
$ pipeline run v2/compatibility/99-broken-compilation.pipeline.kts 2>&1 >/dev/null
$ echo $?
0  # ← VACUOUS GREEN - compilation fails but exit is 0
```

### GREEN evidence (after fix)

```bash
$ pipeline run v2/compatibility/99-broken-compilation.pipeline.kts 2>&1 >/dev/null
Pipeline finished with FAILURE
$ echo $?
1  # ← CORRECT - non-zero on failure
```

### New test: `CompatibilityCorpusTest.fixture99BrokenCompilationExitsNonZero`

```kotlin
@Test
fun fixture99BrokenCompilationExitsNonZero() {
    val path = fixture("99-broken-compilation.pipeline.kts")
    val appBin = AppBinSupport.discover()
    val pb = ProcessBuilder(appBin.toString(), "run", path.toString())
        .redirectOutput(ProcessBuilder.Redirect.PIPE)
        .redirectError(ProcessBuilder.Redirect.PIPE)
    val process = pb.start()
    val exitCode = process.waitFor()
    assertEquals(1, exitCode) { "Broken script must exit with code 1" }
}
```

## Secondary Fix — DSL String-Based Surface (DEFECT 2)

The fixture 14 was using `dev.rubentxu.pipeline.v2.domain.CredentialsId(...)` which is not accessible from DSL scripts (domain types not on script classpath). The factory functions were changed to accept `String` and construct `CredentialsId` internally.

### Changed factory signatures

All 7 `CredentialsBinding` factory functions now accept `String` for credentialsId:

```kotlin
// BEFORE
fun string(credentialsId: CredentialsId, variable: String): CredentialsBinding

// AFTER
fun string(credentialsId: String, variable: String): CredentialsBinding
    = CredentialsBinding(Kind.STRING, CredentialsId(credentialsId), variable = variable)
```

### Updated fixture 14 — pure DSL syntax

```kotlin
withCredentials(listOf(
    StepSpec.CredentialsBinding.string("string-creds", "API_KEY"),
    StepSpec.CredentialsBinding.usernamePassword("userpass-creds", "DB_USER", "DB_PASS"),
    StepSpec.CredentialsBinding.sshUserPrivateKey("ssh-creds", "SSH_KEY_FILE"),
    StepSpec.CredentialsBinding.file("file-creds", "SECRET_FILE"),
    StepSpec.CredentialsBinding.certificate("cert-creds", "KEYSTORE_PATH"),
    StepSpec.CredentialsBinding.zip("zip-creds", "ZIP_PATH"),
    StepSpec.CredentialsBinding.usernameColonPassword("ucp-creds", "U_P")
)) { ... }
```

## Enabled Tests

18 @Disabled CR-BD tests were enabled by:
1. Removing `@Disabled` annotations
2. Updating script strings to use string-based factory calls

## Related Files Changed

| File | Change |
|------|--------|
| `Main.kt` | Exit code mapping for non-durable path |
| `PipelineDsl.kt` | String-based factory signatures |
| `14-credentials-bindings.pipeline.kts` | Pure DSL syntax |
| `UatLocal008CredentialsTest.kt` | Enabled 18 tests |
| `CompatibilityCorpusTest.kt` | Added `fixture99BrokenCompilationExitsNonZero` test |
| `99-broken-compilation.pipeline.kts` | New broken fixture for testing |

## Findings

1. **Exit code fix verified**: Broken scripts now exit with code 1
2. **Fixture 14 verified**: Compiles and runs with pure DSL syntax
3. **Enabled tests**: 18 @Disabled tests are now enabled and compiling
4. **Pre-existing fixture issues**: Fixtures 04, 07, 02, 08 have Groovy-isms (def, array literals) that were masked by exit code bug - these are separate issues requiring separate fixes

## Risks

- Pre-existing fixture issues will cause L5 to fail - these are not related to the two defects fixed
- The 18 enabled CR-BD tests fail at runtime due to credential store access issues - these tests require further investigation

## Next Recommended

1. Investigate and fix pre-existing fixture issues (Groovy syntax in fixtures)
2. Debug credential store access in CR-BD tests
3. Re-run full L5 gate after above fixes

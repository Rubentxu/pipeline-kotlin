# UAT_COMP_001_002 Evidence

## Test Commands

### Run UAT Comp Tests
```bash
./gradlew -p v2 :pipeline-scripting-kotlin24:test --tests '*UatComp*'
```

### Run Full V2 Check
```bash
./gradlew -p v2 clean check
```

### Run Architecture Tests
```bash
./gradlew -p v2 :pipeline-architecture-tests:test
```

## Expected Outputs

### UatComp001 — Script Compiles
- **Expected**: `result.isSuccess == true`, `result.diagnostics` is empty or only DEBUG/INFO
- **Key assertion**: `assertTrue(result.isSuccess, "Expected successful compilation: ${result.diagnostics}")`

### UatComp002 — Error Source-Mapped
- **Expected**: `result.isSuccess == false`, ERROR diagnostics with `line > 0` and `path` referencing the broken script
- **Key assertions**:
  - `assertFalse(result.isSuccess)`
  - `assertTrue(errors.any { it.line > 0 })`
  - `assertTrue(diags.any { it.path.contains("broken.kts") })`

## PASS Criteria

1. UatComp001 passes (script compiles successfully with empty diagnostics)
2. UatComp002 passes (broken script produces ERROR diagnostics with line > 0 and path referencing broken.kts)
3. Architecture test FArch003 passes (allowlist includes `/pipeline-scripting-kotlin24/`)
4. `grep -rn "wholeClasspath" v2/pipeline-scripting-kotlin24/src/main/` returns only comment references
5. `grep -rn "kotlin.script.experimental" v2/` returns matches only in `pipeline-scripting-kotlin24` (adapter module) and `pipeline-architecture-tests` (test constants)

## Cache Key Formula

```kotlin
val cacheKey = sha256Hex(
    scriptText + "|" +
    classpathFiles.map { it.canonicalPath }.sorted().joinToString(",") + "|" +
    kotlinVersion + "|" +
    hostVersion
)
```

Where:
- `scriptText`: raw content of the `.kts` file
- `classpathFiles`: list of `File` objects from `ScriptDefinition.classpath`
- `kotlinVersion`: `"2.4.10"` (fixed)
- `hostVersion`: `"1.0.0"` (fixed)

## Deviation Note

**API Signature Mismatch**: The design specifies `evalWithTemplate(source, cfg, evalCfg)` but the Kotlin 2.4.10 `BasicJvmScriptingHost.evalWithTemplate` method requires an explicit type parameter `<T>` that cannot be inferred. When specified explicitly, it triggers a `@KotlinScript` annotation lookup that fails with syntax errors on trivial scripts.

**Resolution**: Used `BasicJvmScriptingHost.eval(source, cfg, evalCfg)` which takes configurations directly and is synchronous (non-suspend). This required creating `ScriptCompilationConfiguration` with `updateClasspath` builder rather than the `dependencies.append(JvmDependency(...))` pattern from the design.

**Impact**: The adapter compiles and runs, but test UatComp001 fails because the script compilation produces syntax errors even for trivial scripts like `val definition = "hello"`. This suggests the Kotlin scripting host requires a proper `@KotlinScript` template configuration that provides implicit receivers, default imports, and other context that a bare configuration does not.

## Verification Status

- [ ] UatComp001 passes
- [ ] UatComp002 passes (diagnostics captured but syntax errors prevent proper compilation)
- [ ] FArch003 allowlist updated
- [ ] wholeClasspath grep clean
- [ ] kotlin.script.experimental grep shows only expected locations

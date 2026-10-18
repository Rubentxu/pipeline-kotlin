# `scripting-kotlin24-vertical` Specification

## Purpose

Observable contract for the V2 Kotlin 2.4.10 scripting adapter
(`v2/pipeline-scripting-kotlin24`). Public API stays
`kotlin.script.experimental`-free.

## Domain Language

- `ScriptDefinition`: source text/path + `List<String>` classpath + properties.
- `CacheKey`: versioned data class `CacheKey(value, version)`. Version `v2` is active
  (length-framed pre-image plus the compiler options as a dimension);
  `v1` is retained unchanged so values already written under its label stay
  interpretable. `sha256Hex(parts...)` joins parts with `|`, then SHA-256s UTF-8
  bytes; it is `v1`'s pre-image and new algorithms do not use it.
- `ScriptCompilationResult`: success value or diagnostics, plus stable `CacheKey`.
- `ScriptingDiagnostic`: severity, message, line, column, source path.

## Requirements

### Requirement: Script compiles successfully

The system SHALL compile and evaluate a minimal `.pipeline.kts` via
`Kotlin24ScriptingHost`, returning a successful
`ScriptCompilationResult` whose `value` carries the fixture's last
expression; `diagnostics` is empty.

#### Scenario: hello pipeline compiles

- GIVEN `hello.pipeline.kts` (seed-level val or expression)
- AND an explicit classpath containing V2 DSL API + stdlib only
- WHEN `Kotlin24ScriptingHost.compile(ScriptDefinition(...))` runs
- THEN the result is `Success`
- AND `diagnostics` is empty
- AND `value` carries the fixture's last expression

#### Scenario: seed DSL does not require compiler plugin

- GIVEN the same `hello.pipeline.kts`
- WHEN the adapter runs
- THEN no FIR/IR hook is required

### Requirement: Compile error carries source mapping

The system SHALL return at least one `ScriptingDiagnostic` with
`severity == ERROR`, non-empty `message`, `line > 0`, and `path`
mentioning the fixture file when the fixture has a type error.

#### Scenario: type error reports line and path

- GIVEN `broken.pipeline.kts` with an unresolved identifier
- WHEN the adapter compiles it
- THEN the result is a `Failure`
- AND `diagnostics.size >= 1`
- AND for each ERROR diagnostic: `line > 0` AND `path` mentions the fixture
- AND `message` is non-empty

### Requirement: CacheKey versioned contract

`CacheKey` SHALL be a `data class CacheKey(value: String, version: String)` in
`:pipeline-scripting-api`. Version tags are `V1 = "v1"` and `V2 = "v2"` as
companion constants. `CacheKey.Companion.v2.compute(identity)` is the active
algorithm: it digests a **length-framed** pre-image over the dimensions named by
`SCRIPTING_COMPILER_SPEC.md` §7, with the compiler options each contributing
their own framed part. `CacheKey.Companion.v1.compute(...)` is retained
byte-identical so a value already written under the `v1` label still decodes with
the recipe that produced it.

#### Scenario: v1.compute returns CacheKey with version v1

- GIVEN `CacheKey.Companion.v1.compute(scriptText, classpath, kotlinVersion, hostVersion)`
- THEN the returned `CacheKey.value` is a 64-char hex string
- AND `CacheKey.version == "v1"`

#### Scenario: v2.compute returns CacheKey with version v2 and is injective on the assignment

- GIVEN two `CompilationIdentity` values differing only in how a separator falls
  across two dimensions (a `|` inside `approvedClasspath` vs. at the boundary
  between `approvedClasspath` and the first `compilerOptions` entry)
- WHEN `CacheKey.Companion.v2.compute(...)` is called for each
- THEN the two `CacheKey.value` differ AND each is 64-char hex
- AND `CacheKey.version == "v2"`

#### Scenario: v2.compute treats the compiler options as an ordered set

- GIVEN two `CompilationIdentity` values that differ only in the compiler
  options passed, or only in their order
- WHEN `CacheKey.Companion.v2.compute(...)` is called for each
- THEN the two `CacheKey.value` differ

### Requirement: Stable compiler cache key (versioned contract)

The system SHALL expose `cacheKey: CacheKey` in every `ScriptCompilationResult`.
The host computes it with `CacheKey.Companion.v2.compute(...)`, so the value is
64-char hex under the `v2` label. Identical across two evaluations of the same
input. (`CacheKey.Companion.v1.compute(...)` still returns
`CacheKey(sha256Hex(...), "v1")` — it is retained, not removed.)

#### Scenario: cache key stable across two evals

- GIVEN the same `ScriptDefinition`
- WHEN `compile(...)` runs twice
- THEN both `cacheKey.value` are equal AND `cacheKey.version == "v2"`

#### Scenario: cache key varies when classpath changes

- GIVEN the same text but different `classpath`
- WHEN the adapter runs each
- THEN the `cacheKey.value` values differ

### Requirement: No whole-classpath in production path

Production sources SHALL NOT reference `wholeClasspath = true` nor
call `dependenciesFromCurrentContext` / `dependenciesFromClassContext`
/ `dependenciesFromClassloader`. Only classpath injection SHALL be
`JvmDependency(files)` on `dependencies`, or
`updateClasspath(builder, files)`.

#### Scenario: source scan finds no wholeClasspath

- GIVEN `v2/pipeline-scripting-kotlin24/src/main/kotlin`
- WHEN a textual scan for `wholeClasspath` and the three forbidden
  helpers runs
- THEN no matches are found

### Requirement: Explicit classpath via `ScriptDefinition`

The adapter SHALL build its `ScriptCompilationConfiguration` so the
classpath on the `dependencies` key equals exactly
`ScriptDefinition.classpath` (as `java.io.File` list), plus nothing else.

#### Scenario: dependencies match the script definition exactly

- GIVEN `ScriptDefinition.classpath = ["a.jar", "b.jar"]`
- WHEN the adapter constructs the configuration
- THEN `configuration.dependencies` contains one `JvmDependency` whose
  `classpath` files equal `["a.jar", "b.jar"]`

### Requirement: Containment — `kotlin.script.experimental` ONLY in adapter

The `kotlin.script.experimental.*` import SHALL appear only in
`v2/pipeline-scripting-kotlin24/`. F-ARCH-003 SHALL pass with
`/pipeline-scripting-kotlin24/` allowlisted.

#### Scenario: F-ARCH-003 happy path passes

- GIVEN the post-R1 V2 tree
- WHEN F-ARCH-003 runs with the updated allowlist
- THEN the test passes

### Requirement: Script source identity survives the adapter

When `ScriptDefinition.sourcePath` is non-empty, diagnostics SHALL
carry `path` equal to that path (or canonical form). When only
`sourceText` is provided, diagnostics SHALL carry a synthetic `path`
(`inline:<sha256[:8]>`).

#### Scenario: file-backed script keeps its path

- GIVEN `ScriptDefinition(sourcePath = "hello.pipeline.kts", ...)`
- WHEN the fixture compiles with a type error
- THEN every diagnostic's `path` ends with `hello.pipeline.kts`

### Requirement: Result contract exposes diagnostics and cache key

`ScriptCompilationResult` SHALL carry `isSuccess`, `value`,
`diagnostics: List<ScriptingDiagnostic>`, `cacheKey: CacheKey`. No
`kotlin.script.experimental` in the public type.

#### Scenario: success shape

- GIVEN a successful compile
- WHEN the result is inspected
- THEN `isSuccess == true` AND `value != null` AND
  `diagnostics.isEmpty()` AND `cacheKey.value` is 64-char hex AND
  `cacheKey.version == "v2"`

#### Scenario: failure shape

- GIVEN a compile that fails
- WHEN the result is inspected
- THEN `isSuccess == false` AND `value == null` AND
  `diagnostics.isNotEmpty()` AND `cacheKey.value` is 64-char hex AND
  `cacheKey.version == "v2"`

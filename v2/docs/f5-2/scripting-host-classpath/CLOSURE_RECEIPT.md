# SH-classpath WU — Closure Receipt

**Status**: SH-classpath WU CLOSED_GREEN.
**Authority extends**: `CHARACTERISATION.md` (S0 + S1 + S2A), `WC_SCM_CLOSURE_RECEIPT.md` (commit 9be1cdbf).
**Closes**: sibling gap of WU-LPR-WC. The Single Runtime Spine now
honours "ONE classpath feeds BOTH the script compiler AND the runtime
ServiceLoader discovery — no split" (Main.kt:217-219) for both
bundled plugins (`install/lib/*` jar with
`StepDefinitionContributor`) and user-supplied `--plugin-jar` plugins.

---

## 1. Root cause (certified)

The scripting-host classpath composition had **one** bug and **one**
necessary-but-not-sufficient invariant. They were tangled in the same
diagnostic message, which is what made S2 minimal narrow to the wrong
hypothesis.

**Bug**: bundled `OFFICIAL_PLUGIN` JARs (those shipped in
`pipelinek/lib/` and registered through their
`META-INF/services/dev.rubentxu.pipeline.v2.domain.step.StepDefinitionContributor`)
were *not* exposed to the Kotlin scripting compiler.
`Kotlin24ScriptingHost` builds the compile classpath via
`jvm { dependenciesFromCurrentContext(); updateClasspath(files) }` with
`wholeClasspath = false` (the documented default). Whatever the user
added via `--plugin-jar` was the *only* source of plugin JARs the
compiler saw. ServiceLoader in `ExternalStepPluginDiscovery.registerInto`
discovers the bundled plugins at runtime via TCCL, so the same JARs
were visible to the runtime but blind to the compiler. Thus a script
that imports `dev.rubentxu.pipeline.v2.sdk.scm.git.step.scmGitCheckout`
emitted `Unresolved reference 'sdk'`, even though the handler is
present and registered for execution.

**Invariant**: `pipeline-domain` types (`PluginStepId`, `EncodedStepValue`,
…) are not part of the kotlin-scripting template's
`dependenciesFromCurrentContext` defaults. They MUST be added
explicitly. The test precedent
`pipeline-scripting-kotlin24/src/test/kotlin/dev/rubentxu/pipeline/v2/scripting/WithCredentialsCompileIntegrationTest.kt`
already enforces the same pattern in production test code. S2-A
preserved it.

**Syntactic side effect**: the consumer-visible diagnostic
`Too many arguments for 'fun steps(): List<StepSpec>'` from `stage("X") { steps { scmGitCheckout(...) } }`
is unrelated to classpath; the canonical DSL never declares a `steps` block
(see `v2/pipeline-application/src/test/resources/grammar-full.pipeline.kts`,
`multi-step.pipeline.kts`). Steps live inside `stage("…") { … }` directly.

---

## 2. Change applied

### 2.1 New file

`v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/BundledPluginClasspathPlan.kt`

A pure resolver that, given a classpath string, returns a
`BundledPluginResolution` ADT:

```
sealed interface BundledPluginResolution {
    data class Resolved(val artifacts: List<ArtifactIdentity>) : BundledPluginResolution
    data object Empty : BundledPluginResolution
    data class Conflicting(val reason: String, val conflicts: List<ConflictingArtifacts>) : BundledPluginResolution
    data class Rejected(val reason: String, val cause: Throwable? = null) : BundledPluginResolution
}
```

`BundledPluginClasspathPlan.fromClasspathString(...)` and
`BundledPluginClasspathPlan.compose(entries: List<String>)` are the
public entry points.

Detection rule: a JAR is a bundled plugin iff
`META-INF/services/dev.rubentxu.pipeline.v2.domain.step.StepDefinitionContributor`
exists and is non-empty inside the archive. This is the same marker
the runtime's `ServiceLoader.load(StepDefinitionContributor::class.java)`
uses (`ExternalStepPluginDiscovery` at Main.kt:804), so compile and
runtime see the same identity set without parallel configuration.

Dedup: by `(baseName, version)`. Two JARs sharing the same identity is
always a conflict (fail-closed) regardless of whether their canonical
paths collide — having two physical copies of a plugin on the
classpath causes the JVM to load two independent registries and the
Kotlin compiler to resolve plugin symbols to two physically different
classes.

### 2.2 New private helpers in `Main.kt`

```kotlin
private fun computeScriptClasspath(pluginJars: List<String>): List<String> {
    val bundledPlugins = computeBundledPlugins()
    return buildList {
        ScriptDefinition.domainJar()?.let(::add)   // SDK public types
        ScriptDefinition.dslApiJar()?.let(::add)  // DSL façade API
        addAll(bundledPlugins)                      // OFFICIAL_PLUGIN distribution
        addAll(pluginJars)                          // user --plugin-jar
    }
}

private fun computeBundledPlugins(): List<String> { /* … */ }
```

`computeBundledPlugins` translates the `BundledPluginResolution`
sealed shape into a `List<String>` or exits with code `2` (CLI
contract for invocation/compile-classpath errors) on
`Conflicting`/`Rejected`.

### 2.3 Replacements in Main.kt (4 sites)

Before: each of the validate / in-memory-run / durable-run /
scripted-inline flows opened a 5-line `buildList` block
with `domainJar?.let(::add); dslJar?.let(::add); addAll(pluginJars)`
hard-coded per site.

After: each site reduces to one line:

```kotlin
val dslClasspath = computeScriptClasspath(config.pluginJars)
```

The pre-existing comment in Main.kt that recorded the invariant
(`"...— no split runtimePluginJars/scriptPluginJars …"`) is now
true for both bundled and external plugins, not just for the user
flag.

### 2.4 What did NOT change

- `:pipeline-domain`, `:pipeline-scripting-api`, `:pipeline-step-sdk`
  modules: untouched. The boundary between domain and application
  is preserved.
- `ExternalStepPluginDiscovery`, `StepRegistry.register`,
  `StepDefinitionContributor` SPI: untouched. The discriminator
  re-uses META-INF/services, not new metadata.
- Coordinator (`CanonicalDurableRunCoordinator`), handlers
  (`core.sh`, `core.echo`, scm-git `GitCheckoutStepDefinition`,
  junit `JUnitResultsStepDefinition`), capability contracts
  (`WORKSPACE_IDENTITY_CAPABILITY`): untouched. S3 closes the
  scripting-host visibility gap without touching the durable
  spine or its handlers.
- `wholeClasspath = false` in `Kotlin24ScriptingHost.compile`:
  unchanged. The user explicitly required not to relax it
  (`"Mantén wholeClasspath=false. No utilices imports por defecto
  para encubrir tipos ausentes."`).
- The `pipelinek` POSIX launcher script: untouched. `APP_HOME`
  detection in `ScriptDefinition.classpathJar` falls back to
  `java.class.path` (set by the launcher) which is what the new
  resolver reads.
- The decision to keep S2-A's `domainJar()` addition. Rationale:
  see root cause (invariant).

### 2.5 Files modified

```
v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/Main.kt
v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/BundledPluginClasspathPlan.kt   (new)
v2/docs/f5-2/scripting-host-classpath/CHARACTERISATION.md                                                       (new)
v2/docs/f5-2/scripting-host-classpath/CLOSURE_RECEIPT.md                                                       (new — this file)
```

---

## 3. Tests / evidence

### 3.1 Acceptance gate (real binary, fresh installDist)

Tests executed against
`/home/rubentxu/Proyectos/kotlin/pipeline-kotlin/v2/pipeline-application/build/install/pipelinek/lib/*`
after `:pipeline-application:jar :pipeline-application:installDist` on
JDK `temurin-24.0.2+12`.

| # | Scenario                                                 | Expected                              | Observed | Notes |
|---|----------------------------------------------------------|---------------------------------------|----------|-------|
| 1 | uppercase + `--plugin-jar`                                | SUCCESS                               | SUCCESS (`outcome=success`, `stepType=example`) | Baseline preserved |
| 2 | scm-git + **no** `--plugin-jar` (bundled)                 | SUCCESS                               | SUCCESS (`outcome=success`, `stepType=scm-git`, `changelog.txt` written) | Core fix |
| 3 | junit + **no** `--plugin-jar` (bundled)                   | SUCCESS once `workspaceRoot` matches the report path | SUCCESS (`outcome=success`, `stepType=junit`) | Core fix |
| 4 | joint `checkout → sh → junit.results` (no flags)         | SUCCESS                               | SUCCESS (sequence shows `stepType=scm-git` then `stepType=sh` then `stepType=junit`) | End-to-end canonical |
| 5 | uppercase without `--plugin-jar` (external missing)       | compile failure with Unresolved       | `diagnostics=[Unresolved 'example', Unresolved 'uppercase']`, exit=1 | Negative path |
| 6 | `validate` command on joint.pipeline.kts                  | Same composition as run               | `cacheKey` identical to run #4, `VALIDATION SUCCESSFUL` | Same classpath invariant |
| 7 | duplicate scm-git-0.36.0.jar on two distinct classpath entries | fail-closed `exit=2` with diagnostic | `Bundled plugin classpath conflict: 1 group(s): scm-git (duplicate copy of the same plugin (same identity, 2 JARs)): …`, exit=2 | Negative path |
| 8 | `import org.fictional.Nope.thingy`                         | fail-closed compile diagnostic        | `Unresolved reference 'fictional'` | Negative path |

The cache key agreement between `validate` and `run` for the same
script (row 6) is the cleanest observable proof of the "ONE
classpath feeds BOTH" invariant: both code paths derive their
classpath through `computeScriptClasspath`, so the script-level
sha256 cache key matches.

### 3.2 Test classpath evidence

`./gradlew -p v2 :pipeline-application:test --tests 'UatComp*'` →
`BUILD SUCCESSFUL in 3m 51s`, exit=0. The relevant fitness rows
remain green; no regression in the existing compiled-script
harness.

### 3.3 Raw log pointers

```
/tmp/sh-classpath-uat/u3.log              – row 1
/tmp/sh-classpath-uat/scm-bundled.log      – row 2
/tmp/sh-classpath-uat/junit-bundled2.log   – row 3
/tmp/sh-classpath-uat/joint.log            – row 4
/tmp/sh-classpath-uat/m1.log               – row 5
/tmp/sh-classpath-uat/m2.log               – row 5 (--plugin-jar path)
/tmp/sh-classpath-uat/validate.log         – row 6
/tmp/sh-classpath-uat/dup-confirm.log      – row 7
/tmp/sh-classpath-uat/m4.log               – row 8
```

---

## 4. Where the design sits in the architecture

| Concern                                  | Before this WU        | After this WU                                       |
|------------------------------------------|------------------------|-----------------------------------------------------|
| Bundled plugin classpath (compile)        | visible only via `--plugin-jar` | visible via `install/lib/*` scan, same identity set as runtime |
| User `--plugin-jar`                      | Always added           | Always added (deduped against bundled set by `(baseName, version)`) |
| `domainJar()` addition                    | inconsistent across callsites | consistent across callsites (in helper), kept explicit |
| Conflict detection (bundled)              | none                   | fail-closed with diagnostic, exit=2                 |
| CLI command `/validate` uses same cp as `/run` | accidental           | explicit, single source of truth                    |
| Step Constitution compliance              | would have regressed to step-key branching had we hardcoded `scm-git`/`junit` | preserved: data-driven resolver over META-INF/services |

---

## 5. Steps not in scope (deliberately deferred)

A classpath composition is now correct, but a few orthogonal
hardenings were intentionally not done:

- A first-class failure when `--plugin-jar /nonexistent.jar` is
  provided (today the import simply fails to resolve; a
  pre-flight JAR-existence check could replace that with a
  clearer diagnostic).
- Plugin identity hashing (SHA-256 instead of baseName+version)
  for absolute collision detection across copy-pasted JARs.
- A centralised `PluginClasspathPlan` ADT surfaced beyond
  `Main` (e.g. consumed by tests that exercise installDist
  directly).
- Removing the explicit `domainJar + dslJar` adds inside
  `computeScriptClasspath` (today the test precedent
  `WithCredentialsCompileIntegrationTest` keeps them; an ADR
  could collapse them into a single SDK bundle).

These are noted so the WU does not over-promise. They are not
regressions and do not block closure.

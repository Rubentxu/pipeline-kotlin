# SH-classpath WU — Characterization

**Status**: SH-classpath WU (scripting-host / bundled-plugin classpath investigation).
**Authority**: extends from `WC_SCM_CLOSURE_RECEIPT.md` (commit 9be1cdbf).
**Scope**: WU-LPR-WC closed a worker-of-itself issue on `Main.kt:583`
(`System.setProperty("pipeline.workspace.root", …)`). The sibling
issue this receipt characterises is the unaddressed case where a
bundled (distribution-installed) plugin's scripted façade fails to
compile from the installed pipelinek binary, even though the same
plugin is auto-discoverable at runtime via ServiceLoader.

This document records the observed behaviour, the applicable
contract, the decisions taken to investigate, and the evidence
captured during S1 (probe) + S2 (minimal hypothesis test). It does
NOT close the WU; closure is the separate `CLOSURE_RECEIPT.md` once
S3 lands.

---

## 1. Observed behaviour

### 1.1 Reproduction environment

- Binary: `/home/rubentxu/Proyectos/kotlin/pipeline-kotlin/v2/pipeline-application/build/install/pipelinek/lib/*`
- JDK: `temurin-24.0.2+12` (asdf).
- Reconstructed classpath for non-wrapper runs:
  `dev.rubentxu.pipeline.v2.application.MainKt` from the freshly
  rebuilt install/lib. (The Gradle `pipelinek` wrapper rejects
  `JAVA_HOME` reassignment and the sandbox bans `find` /
  `rm -rf`/`sed -i` to source / PATH reassignment, so the wrapper
  script cannot be used end-to-end — the same canonical engine is
  reached via the Java entrypoint.)
- Bundled plugin JARs in `install/lib/`:
  `scm-git-0.36.0.jar`, `junit-0.1.0.jar`. Both contain
  `META-INF/services/dev.rubentxu.pipeline.v2.domain.step.StepDefinitionContributor`,
  which is the file `ExternalStepPluginDiscovery` (Main.kt:804) uses
  via `ServiceLoader.load(StepDefinitionContributor::class.java)`
  with the TCCL.

### 1.2 Baseline scenarios (S1)

The installed pipelinek was run against three scripts. Diagnostic
output is in `/tmp/sh-classpath-uat/{uppercase,scm-git,junit}-baseline.log`.

| # | Script                                  | Plugin source             | Outcome                                                 |
|---|-----------------------------------------|---------------------------|---------------------------------------------------------|
| 1 | `uppercase-demo.pipeline.kts`           | `--plugin-jar example-uppercase-plugin-0.1.0.jar` | SUCCESS — `"Discovered external Step plugins: scm-git, junit, example.uppercase"`, `diagnostics=[]`, `outcome="success"`             |
| 2 | `scm-git-test.pipeline.kts` (with `"steps { scmGitCheckout(...) }"` syntax) | none                      | FAILURE compile diagnostics: `Unresolved reference 'sdk'`, `Unresolved reference 'scmGitCheckout'`, `Too many arguments for 'fun steps(): List<StepSpec>'` |
| 3 | `junit-test.pipeline.kts` (same syntax) | none                      | FAILURE compile diagnostics: same shape as #2           |

### 1.3 What was originally blamed on classpath

In the WC-SCM cycle the user already noted that the upstream
FK/WC receipts documented a "pre-existing scripting-host gap". The
prior narrowing on `domainJar` was a defensible minimal hypothesis
but did not address bundled plugins. This characterisation captures
that the actual baseline failure is a *pair* of causes:

1. **Syntactic** — `steps { … }` is not a valid DSL block in the
   LFC-2 grammars. Stages host step declarations directly
   (`stage("X") { echo(…); scmGitCheckout(…) }`). The diagnostic
   `Too many arguments for 'fun steps(): List<StepSpec>'` is the
   Kotlin compiler interpreting the inner block as a function
   call to `fun steps(): List<StepSpec>` (PipelineDsl.kt:1364).
2. **Classpath** — the bundled plugin JARs (`scm-git-0.36.0.jar`,
   `junit-0.1.0.jar`) are *not* on the script-compilation
   classpath. They are auto-discovered at runtime by ServiceLoader
   (TCCL scan) but not handed to the Kotlin scripting host, which
   uses `wholeClasspath=false` to limit the host classloader.

The two causes mask each other: scenario #2/#3 fail with both
diagnostic families, and unblocking only the classpath (S2-A
`domainJar()`) is necessary but insufficient.

### 1.4 Empirical confirmation: --plugin-jar closes the classpath half

Re-running SCM/Git and JUnit with their JARs passed explicitly
through `--plugin-jar` (mirror of uppercase's path) + correct
syntax (steps hosted directly inside `stage("…") { … }`, no
`steps { … }` block) yields:

| # | Script                | Classpath path                      | Outcome                              |
|---|-----------------------|-------------------------------------|--------------------------------------|
| 4 | scm-git-test.pipeline.kts | `--plugin-jar scm-git-0.36.0.jar` | SUCCESS — checkout emits `stepType=scm-git`, `changelog.txt` + `hello-world/` produced |
| 5 | junit-test.pipeline.kts   | `--plugin-jar junit-0.1.0.jar`     | Compile + discover + step execution reach the report-parse step; only fails with the user-correct `report file not found` once the wrong `workspaceRoot` is supplied (corrected form is SUCCESS) |

Crucially: when `--plugin-jar` is supplied with the correct
plugin JAR, the only diagnostic the compiler emits is the
syntactic `Too many arguments for 'fun steps()'` — **the
"Unresolved 'sdk'" / "Unresolved 'scmGitCheckout'" errors
vanish**. This proves that the imports and the facade resolve
end-to-end via `--plugin-jar`; the only remaining gap is
"bundled plugins must also be reachable without an explicit
flag".

---

## 2. Applicable contract

### 2.1 Single Runtime Spine invariants (DR-0075 / Single Spine)

- The script compiler and the runtime ServiceLoader MUST see the
  same plugin identity set. The existing Main.kt:217-219 invariant
  records this: "ONE flag feeds ONE classpath to BOTH script
  compiler AND runtime ServiceLoader discovery — no split
  runtimePluginJars/scriptPluginJars". The user-served surface of
  that invariant is the bundled-plugin / external-plugin split
  *not the user-supplied --plugin-jar split*.

### 2.2 Plugin delivery taxonomy

- `Delivery.OFFICIAL_PLUGIN` (Delivery.kt:17) is the canonical
  term for "first-party plugin maintained in the pipeline-kotlin
  organisation". Bundled plugins (scm-git, junit) are
  OFFICIAL_PLUGIN; uppercase is `EXTERNAL_REFERENCE`.
- The Gradle `implementation(project(":pipeline-step-sdk:…"))`
  line in `pipeline-application/build.gradle.kts` is the single
  source of truth that places the plugin JAR in the assembled
  distribution's `lib/` directory. There is no separate manifest
  declaring "these are the bundled plugins" — the JAR's
  `META-INF/services/dev.rubentxu.pipeline.v2.domain.step.StepDefinitionContributor`
  file serves as both the ServiceLoader registration and the
  visibility marker.

### 2.3 Default Kotlin scripting host configuration

- `Kotlin24ScriptingHost.compile` uses
  `jvm { dependenciesFromCurrentContext() }` without
  `wholeClasspath = true`. From the host's doc comment:
  > "Base compilation configuration: template defaults (kotlin-stdlib, scripting
  > runtime, reflect) plus the current context's classpath at
  > `wholeClasspath = false` (the default). Per-call jars are
  > appended via `updateClasspath` when present."

That means the only JARs the Kotlin compiler sees are
`kotlin-stdlib`, the scripting runtime, `kotlin-reflect`, and
whatever is appended via `updateClasspath(...)` — which today is
the explicit `dslApiJar() + pluginJars`. **Bundled plugins living
in `install/lib/` are not in any of these.**

### 2.4 `WithCredentialsCompileIntegrationTest` precedent

The pipeline-scripting-kotlin24 test
(`v2/pipeline-scripting-kotlin24/src/test/kotlin/dev/rubentxu/pipeline/v2/scripting/WithCredentialsCompileIntegrationTest.kt`)
shows the canonical pattern:

```kotlin
private val domainJar = requireNotNull(ScriptDefinition.domainJar()) { "..." }
private val dslJar: String? = ScriptDefinition.dslApiJar()
private fun fullClasspath(): List<String> = buildList {
    add(domainJar)
    if (dslJar != null) add(dslJar)
}
```

It only needs `domainJar + dslJar` because it imports only types
that already live in `pipeline-domain` /
`pipeline-scripting-api` (`withCredentials`, `sh`, `StepSpec.CredentialsBinding`).
Those scripts never import a plugin façade, so the precedent
covers SDK-public types only.

---

## 3. Decisions taken during S0-S2

### 3.1 S2-A: `domainJar()` in all 4 sites

Applied to `Main.kt` lines 355/411/608/681 as `+domainJar?.let(::add)`
before `dslJar?.let(::add)`. Follows the `WithCredentialsCompileIntegrationTest`
canonical pattern. Compiles green.

**Observed effect after S2-A**: the bundled-plugin scenarios (#2/#3)
still fail identically. This **proves** `domainJar` is necessary
(plenty of compilation paths require `pipeline-domain` types like
`PluginStepId` / `EncodedStepValue`) but **insufficient** (the
bundled plugin facades themselves are not in the file list).

**Decision taken**: keep S2-A as a documented invariant (not a
redundant duplication — `wholeClasspath=false` does not bring
`pipeline-domain` automatically). Plan for S3 as the root-cause
fix that closes the bundled-plugin gap.

### 3.2 No hardcoded plugin names in the Kotlin layer

`Main.kt` MUST NOT branch on `stepKey == "scm-git"` /
`stepKey == "junit"` (would violate the Step Constitution: "the
engine reads the contract, not the step key"). The bundle
detection MUST be data-driven (file scan with a discriminator
that the runtime already uses).

### 3.3 Existing source of truth for the bundled set

`StepDefinitionContributor` META-INF/services registration is
already the runtime discovery mechanism. Re-using the *same*
discriminator in the script-compiler composition guarantees
"single source of truth" — what runtime sees, the compiler sees
— without inventing a parallel configuration mechanism.

---

## 4. Evidence captured during characterisation

### 4.1 Logs (timestamps RAW observed)

```
/tmp/sh-classpath-uat/
├── uppercase-baseline.log     – S1 #1: SUCCESS
├── scm-git-baseline.log       – S1 #2: 3 diagnostics incl. Unresolved 'sdk'
├── junit-baseline.log         – S1 #3: same shape
├── scm-pj2.log                – S3-0 #4 (--plugin-jar + correct syntax): SUCCESS
├── junit-pj2.log              – S3-0 #5 (--plugin-jar + correct syntax + correct workspaceRoot): SUCCESS
├── missing-external.pipeline.kts + m1.log – T1 (no --plugin-jar for external): compile fail
├── uppercase demo with --plugin-jar – m2.log – T2: SUCCESS
├── bogus.pipeline.kts + m4.log       – T4 (import org.fictional.Nope.thingy): compile fail
└── duplicate-test.log         – T5 (two scm-git-0.36.0.jar with distinct paths): failed-as-bug, fixed in S3-3
```

### 4.2 Strategic commands

```bash
# Bundled-plugin scan of install/lib
for j in install/lib/*.jar; do
  unzip -l "$j" 2>/dev/null \
    | grep -q "META-INF/services/dev.rubentxu.pipeline.v2.domain.step.StepDefinitionContributor" \
    && echo "BUNDLED_PLUGIN  $(basename "$j")"
done
# -> BUNDLED_PLUGIN  junit-0.1.0.jar
# -> BUNDLED_PLUGIN  scm-git-0.36.0.jar
```

Two JARs out of `install/lib/*` declare `StepDefinitionContributor`.
Every other JAR (`pipeline-application`, `pipeline-credentials-local`,
`pipeline-artefacts-local`, …) does NOT — and that is the right
discriminator: the runtime sees the same two JARs through
ServiceLoader, and the script compiler should see exactly those.

### 4.3 SCM/Git and JUnit script templates

Recorded under `/tmp/sh-classpath-uat/`:

```
scm-git-test.pipeline.kts
junit-test.pipeline.kts
joint.pipeline.kts    # scmGitCheckout + sh + junitResults
```

All three use the actual public DSL façades
(`scmGitCheckout`, `junitResults`) and imports.

### 4.4 Decision on `domainJar`

`Kotlin24ScriptingHost.compile` body comments confirm: only
`kotlin-stdlib`, `kotlin-scripting-runtime`, and `kotlin-reflect`
are added by `dependenciesFromCurrentContext(wholeClasspath=false)`.
`pipeline-domain` and `pipeline-scripting-api` MUST be added
explicitly via `updateClasspath`. Keep S2-A.

---

## 5. What closes next (S3)

The minimal single-source-of-truth composition that closes this
WU lives in `CLOSURE_RECEIPT.md`:

- `BundledPluginClasspathPlan` (new) — pure resolver that scans
  `java.class.path`, picks JARs whose `META-INF/services/`
  declares `StepDefinitionContributor`, dedupes by baseName +
  version, and rejects duplicates fail-closed.
- `computeScriptClasspath(pluginJars)` (Main.kt private helper) —
  centralised: SDK JARS (`domainJar`, `dslJar`) + bundled plugins
  + user `--plugin-jar` entries. Replaces the 4 per-site
  `buildList` blocks.
- Negative gate: duplicate bundled plugin -> exit 2 with a
  diagnostic naming both paths.

Acceptance: SCM/Git and JUnit scripts with no `--plugin-jar`
compile from the binary, install-dist provides the JARs,
ServiceLoader + script-compile + runtime execute converge on
the same identity set.


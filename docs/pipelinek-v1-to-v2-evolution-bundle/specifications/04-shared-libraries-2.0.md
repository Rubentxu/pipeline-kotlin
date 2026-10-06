# Specification — Shared Libraries 2.0

**Status:** Proposed  
**Priority:** High

## 1. Definition

A PipelineK Shared Library is an immutable, versioned authoring dependency made available to Kotlin pipeline compilation/evaluation. It exists to reuse code **using capabilities that already exist**.

It is not a runtime extension mechanism.

## 2. Normative separation from plugins

A Shared Library SHALL NOT:

- implement `StepDefinitionContributor`;
- register Steps, directives, event definitions, reactors or other canonical runtime contributor families;
- provide runtime capability contributors;
- own replay/recovery semantics;
- perform hidden effects during library resolution;
- introduce an independent execution engine.

A Plugin MAY:

- register typed Steps/directives/events through published extension seams;
- declare required/provided runtime capabilities;
- own handlers/codecs/replay/recovery metadata;
- provide a typed Kotlin façade visible to script compilation.

## 3. Authoring rule

Shared Library code SHOULD be pure composition or normal Kotlin helper code. Any I/O that is part of pipeline semantics MUST occur through registered PipelineK operations.

Example allowed:

```kotlin
fun StageScope.standardJvmBuild() {
    sh("./gradlew clean check")
    junit("build/test-results/**/*.xml")
}
```

Example disallowed by the authoring contract (static manifest inspection alone does not prove all such effects absent):

```kotlin
fun publishDirectly(request: java.net.http.HttpRequest) {
    java.net.http.HttpClient.newHttpClient()
        .send(request, java.net.http.HttpResponse.BodyHandlers.discarding())
}
```

The first composes registered operations. The second bypasses capabilities, events and replay.

## 4. Identity model

```kotlin
@JvmInline value class LibraryId(val value: String)

data class LibraryReleaseRef(
    val library: LibraryRef,
    val version: SemVer,
    val digest: Digest,
)
```

`LibraryRef` and `LibraryReleaseRef` SHALL follow the same logical-vs-immutable identity distinction already used by plugins, without pretending a library is a plugin.

If extending the common `ResourceKind` enum is chosen, add distinct `LIBRARY` / `LIBRARY_RELEASE`; do not reuse `PLUGIN`.

## 5. Static manifest

Every distributable library SHALL publish:

`META-INF/pipeline/library-manifest.json`

Minimum content:

```json
{
  "schema": "pipeline.dev/library-manifest/v1",
  "id": "company-ci",
  "version": "2.4.1",
  "publisher": "com.example",
  "apiRange": ">=0.42 <0.50",
  "requiresPlugins": [
    {"id": "pipeline-plugin-junit", "range": ">=1.2 <2"}
  ]
}
```

The artifact digest is computed from the actual JAR bytes by the resolver/admission pipeline; a self-declared digest inside the same artifact is not authoritative.

## 6. Resolution

Initial supported resolution order:

1. explicit local JAR;
2. Maven coordinate;
3. configured Maven repositories.

Normal runtime resolution SHALL NOT compile a Git repository. Git remains a development/publishing transport, not the execution-time library build system.

## 7. Configuration

Default catalogue/config path:

```text
${XDG_CONFIG_HOME:-~/.config}/pipelinek/libraries/
```

Cache:

```text
${XDG_CACHE_HOME:-~/.cache}/pipelinek/libraries/
```

Run evidence/state:

```text
${XDG_STATE_HOME:-~/.local/state}/pipelinek/runs/<run-id>/
```

No persistent config or lockfile is written to the project repository unless explicitly requested by the user in a future feature.

## 8. CLI surface

Initial additive surface:

```text
pipelinek run --library company-ci@2.4.1 pipeline.kts
pipelinek validate --library company-ci@2.4.1 pipeline.kts
pipelinek api libraries --json
pipelinek api library company-ci --json
```

The exact parsing syntax can be adjusted during CLI UX spike, but semantics are fixed: library resolution completes before scripting compilation.

## 9. Script dependency plan

Replace the loss of category information caused by a raw classpath list with:

```kotlin
data class ScriptDependencyPlan(
    val sdkEntries: List<ResolvedClasspathEntry>,
    val plugins: List<ResolvedPluginArtifact>,
    val libraries: List<ResolvedLibraryArtifact>,
)
```

Derived views:

- `compilationClasspath`: approved SDK API + plugin façades + libraries and admitted transitive support dependencies in effective order;
- `evaluationClasspath`: coherent approved API identities from the same plan;
- `pluginDiscoveryClasspath`: typed plugin artifacts only, using all canonical S6 contributor families.

Compiler implementation artifacts belong to the adapter distribution profile, not an implicitly exported whole-process script classpath.

## 10. Ambiguity rejection

A declared Shared Library or attached support artifact contributing any canonical SDK runtime family SHALL be rejected as `AMBIGUOUS_ARTIFACT_KIND`. This includes current `META-INF/services/<StepDefinitionContributor>` compatibility and canonical S6 metadata; use the same family authority rather than a Step-only scanner.

This prevents a library from quietly becoming a plugin.

## 11. Compatibility requirements

- Existing runs with no libraries produce byte-equivalent dependency ordering where practical.
- Existing `--plugin-jar` semantics remain supported during migration.
- Shared Libraries cannot alter the runtime Step registry without an explicit plugin.
- Library artifact content participates in `CacheKey.v2`.

## 12. Exit criteria

The feature is DONE only when installed-distribution UAT proves:

- typed library classes compile in `.pipeline.kts`;
- local JAR and Maven resolution work;
- byte tamper changes digest/cache identity;
- missing plugin requirements fail before script execution;
- a library or its support nodes cannot register any canonical runtime contributor;
- no cache/config is written into the checkout by default;
- fresh and repeat runs are deterministic.

## 13. Precompiled authoring and graph closure — GR-015

Publish reusable authoring code as real precompiled JARs. PipelineK resolves/adopts their bytes before source compilation; it does not build a library Git repository during run or run a library initialization DSL as a dependency authority.

Resolve/freeze the complete Maven graph, not only the root coordinate. Record effective precedence, exact selected versions, content digests, requirements and source facts in user-owned state outside the checkout. Ranges/dynamic versions are requests, not immutable release identities; repeat/offline uses the admitted snapshot until an explicit resolution refresh is requested.

A library entry artifact needs its library manifest; ordinary supporting JVM dependencies need not pretend to be PipelineK libraries. They are typed support nodes attached to the same plan and undergo applicable digest/API/artifact-kind checks. A support node cannot covertly register a canonical Step/directive/event/reactor/capability contributor. Plugin requirements select explicitly admitted plugins; they are not silently promoted from transitive JARs.

Scan canonical SDK contributor metadata across the relevant graph. Do not reject unrelated JVM SPIs by a blanket ban on every META-INF/services file. Published purity rules are authoring contracts; structural admission is not a proof against reflective/arbitrary I/O.

M4/M5 tests cover root+transitive same-path changes, effective-order conflicts and offline exact-graph reuse. Reuse installed SDK/plugin APIs without duplicating their classes in the library JAR.

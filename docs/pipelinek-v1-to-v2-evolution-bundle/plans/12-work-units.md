# Work units

Status: planning proposals. No unit is opened, registered, implemented or certified by this document. Reuse the existing SDDK/roadmap authorities.

Each work unit follows: characterize → decide → implement → surgical tests → architecture tests → installed UAT → receipt → atomic Conventional Commit(s).

## WU-M0-01 — V1 dependency inventory

Generate machine-readable inventories:

- Gradle project/module includes;
- imports/references from V2 to root V1 packages;
- scripts invoking legacy binaries/modules;
- CI/release jobs referencing V1;
- docs links to V1 paths;
- disabled/backup legacy sources.

**Exit:** inventory checked into evidence/receipt, not hand-maintained as authority.

## WU-M0-02 — Current composition characterization

Freeze current behaviour of:

- `computeScriptClasspath`;
- `BundledPluginClasspathPlan`;
- external `--plugin-jar`;
- `ExternalStepPluginDiscovery`;
- `LspMetadataLoader`;
- `RegistryExecutionPreparation`.

## WU-M0-03 — Compiler and JVM characterization

Inventory canonical `SCRIPTING_COMPILER_SPEC.md` ports, actual `ScriptingHost.compile` consumers and replay identities. Record real compiler/mapper configuration, original diagnostics/severities, public compile/evaluate behaviour and both frontend corpora. Exercise installed JDK 21 baseline, JDK 24+ Unsafe deny probe and the canonical additional JDK 25 gate with honest blocked/NOT_RUN status.

A focused reader-policy repair may use the existing authorized compiler train with UAT-070/071; broader port/cache work waits for M7. Do not register another engine or alter the active S6 train. **Exit:** reproducible baseline and discrepancy inventory, not a hypothetical migration.

## WU-M1-01 — Typed registry preparation decision

Extract pure decision, preserve current diagnostics through an adapter, mutation-test each branch.

## WU-M1-02 — Introspection snapshot

Compose immutable snapshot from registry/provider/LSP metadata. No CLI yet.

## WU-M1-03 — Catalog digest

Canonical serialization + sorted identity set + digest. Prove deterministic order.

## WU-M2-01 — Affordance root/schema

Implement versioned root representation and JSON codec.

## WU-M2-02 — Step/plugin resources

Expose descriptors, schemas, provider identity and actions.

## WU-M2-03 — Human explain/list projections

Render from the same DTO model.

## WU-M2-04 — Plan/why-not

Expose typed preparation decision without effects.

## WU-M3-01 — Static plugin manifest

Reuse the accepted S6 manifest and its versioning/generation authority. Package official/reference fixtures for every canonical contributor family; parse without class initialization. If S6 already provides a part, characterize/reuse it rather than rebuild it.

## WU-M3-02 — Artifact resolver/admission seam

Introduce ports and local-JAR implementation; preserve current runtime behaviour for admitted artifacts.

## WU-M3-03 — Runtime manifest cross-check

Verify the canonical static declaration equals the runtime composition across all SDK contributor families. Preserve current Step-only compatibility until parity permits retirement; one authority remains.

## WU-M3-04 — Bundled classpath migration

Replace path-only identity incrementally; do not delete old resolver until parity tests pass.

## WU-M4-01 — Library domain and manifest

Add identity/release/requirements without plugin inheritance.

## WU-M4-02 — ScriptDependencyPlan

Introduce typed categories and derive compiler, evaluation and plugin-discovery views. Preserve semantic classpath order; a sorted catalogue identity set is a different projection.

## WU-M4-03 — Local JAR library resolver

Explicit path, manifest/digest, no hidden repo writes.

## WU-M4-04 — Library compilation UAT

Real installed binary + external library JAR + typed imports/functions.

## WU-M4-05 — Immutable plan and visibility contract

Freeze admitted content snapshots before compilation/loading. Include generated façade bytes and support dependency identities. Expose only declared DSL/library/plugin API, preserving SDK type identity and excluding compiler internals. UAT-036/052/064 and AAT-009/010/023/028 cover closure/order/TOCTOU; classloader visibility is not an I/O sandbox.

## WU-M5-01 — Maven resolver

Coordinate/repository adapter with deterministic XDG cache path and complete selected transitive graph. Root library manifests and ordinary JVM support JARs have distinct roles; neither transitive bytes nor canonical runtime contributors may disappear from admission/identity.

## WU-M5-02 — Requirements/API compatibility

Validate PipelineK API range and required plugins before compile.

## WU-M5-03 — Offline/cache UAT

Warm online, disconnect/offline repeat, corrupt cache rejection.

## WU-M5-04 — Dependency snapshot and offline reproducibility

Record exact selected versions, content digests, repository provenance and effective order. Resolve all transitives before body compilation; reuse immutable bytes offline. Same-path replacement must not change bytes after admission. Reject hidden canonical runtime contributor families even in transitives; do not reject unrelated ordinary Java services. UAT-031/033/036/050/064.

## WU-M6-01 — Diagnostic model/enricher

Stable codes + catalogue-derived suggestions/actions. Preserve raw severity/location and project origin/phase using the existing model. Cached compile warnings remain visible; evaluation diagnostics are fresh. UAT-062/071 and AAT-026.

## WU-M6-02 — LSP transport/server

Implement V2 server adapter against introspection + scripting source map.

## WU-M6-03 — Plugin-dynamic IDE UAT

External plugin install/remove changes completion/hover.

## WU-M7-01 — Effective profile and CacheKey.v2

One actual adapter-owned profile drives language/API/target/JDK roots/options/reader policy/template and identity. Canonically frame complete source, ordered admitted graph, generated code and compatibility versions. Preserve v1 decoding and distinguish replay identity. UAT-050..053/072, AAT-014/023. No cache implementation is implied.

## WU-M7-02 — Compilation benchmark and separate decisions

After WU-M7-01/04, apply [the benchmark protocol](../quality/gradle-benchmark-protocol.md) under existing WU-RP-022 authority. Count actual compilation and evaluation, report every sample and resource retention, approve SLOs from a baseline. Record separate memory/persistence/session GO or NO-GO receipts. Do not infer a hit from elapsed time.

## WU-M7-03 — Conditional bounded memory artifact cache

Only after memory GO and a real representation equivalence gate. Cache compiled code/metadata/compile diagnostics, never evaluated context or results. Lease-safe bounded eviction, same-key compile sharing and fresh evaluation per caller. UAT-056/057/058/062/063/064, applicable UAT-066 cases and AAT-021/023/025/028. A single-process cache dies with the one-shot CLI.

## WU-M7-04 — Phase separation and representation equivalence

Internally separate compile/load/evaluate through canonical adapter/ports while preserving the current public contract and both frontends. Keep DSL/return-value gates, replay identity and diagnostics. Prove script-body canaries remain untouched by compile-only work; fresh evaluation preserves behaviour. The pure typed Step preparation boundary stays separate from `validate` construction evaluation. UAT-054/055/070..073 and AAT-015/021/022/026.

Characterize actual compiled objects and any proposed save/load representation with real scripts, template/entry metadata and source maps. Unsupported reuse is NO-GO, not a stub; keep the functional path. Any public port change needs ABI/consumer migration before compatibility removal.

## WU-M7-05 — Conditional persistent artifact cache

Only after accepted ADR-EVO-012, compatible real representation round trip and separate persistence GO. Implement owner-scoped XDG storage, atomic publication, process-safe cancelable coordination, integrity/profile checks before load, interrupted-writer recovery and bounded leases/retention. Fresh JVM must hit with zero actual compilations and fresh evaluation. UAT-059/063/064/065/066 and AAT-024/028. Shared/remote executable cache is out of scope.

## WU-M7-06 — Conditional existing-owner compiler sessions

Only after accepted ADR-EVO-013 and session GO. Reuse an existing owned adapter/worker scope, with profile partitioning, bounded queue, fresh invocation contexts, actual cancellation semantics and safe resource disposal. UAT-080/081/082 and AAT-025/027. No new daemon/controller/protocol or pipeline scheduler. One-shot CLI parity remains blocking.

## WU-M8-01 — ASX introspection migration

Wire `steps list/describe`, profiles and agent output to common tooling model.

## WU-M8-02 — Generic inline Step invocation

Same registry/capability/durable spine.

## WU-M8-03 — MCP/skills adapter

Project tools from introspection/invocation ports; no duplicate semantics.

## WU-M9-01 — Whole-program compatibility suite

Current no-library/no-new-CLI workflows must remain equivalent, including public scripting results, both frontends, replay fingerprints and warning/error/source-map semantics. Declare disabled optional slices; unrun tests are not PASS.

## WU-M9-02 — Installed-distribution certification

Clean install, local projects, external plugin, complete shared-library graph, agent surfaces and LSP. Certify mandatory compiler/JDK gates and every enabled cache/session mode on the same SHA/artifact; attach GO/NO-GO and installed cross-process evidence where applicable.

## WU-M9-03 — V1 migration disposition

Every V1 feature/file classed as `MIGRATED`, `SUPERSEDED`, `REJECTED` or `DELETE` with evidence.

## WU-M10-01 — External archaeology snapshot

Create immutable archive outside repo containing any legacy material worth historical preservation, plus SHA256 manifest and source commit. Git history remains primary.

## WU-M10-02 — Delete legacy code/modules/build wiring

Delete only after M9 gates prove zero dependency.

## WU-M10-03 — Delete legacy docs/scripts/examples

Remove obsolete historical docs, scripts, backups, disabled fixtures and stale examples selected by the M0/M9 inventories.

## WU-M10-04 — Repository closure gate

Fresh clone; build; architecture tests; UAT smoke; grep/reference scan; distribution; dogfood. Produce final receipt with exact deletion manifest.

# Roadmap — value blocks and milestones

## 1. Placement in the current roadmap

This programme is integrated after/within the existing RP7 semantic/plugin work; it is not a parallel roadmap.

**Do not interrupt an active semantic train merely to start these features.** Plugin SDK/manifest/certification work is foundational and should converge first.

## 2. Milestone sequence

| Milestone | Value | Prerequisite |
| --- | --- | --- |
| M0 | Baseline, migration inventory and compiler characterization | Existing authorized train/candidate |
| M1 | Introspection kernel and typed preparation | M0 |
| M2 | Affordance API, explain/plan/why-not | M1 |
| M3 | Canonical S6 manifest and pre-loading admission | M2 and accepted SDK contracts |
| M4 | Shared Libraries local-JAR vertical and frozen dependency plan | M3 |
| M5 | Complete Maven graph and reproducible offline resolution | M4 |
| M6 | Diagnostics and LSP v2 | M5 |
| M7 | Compiler phase/profile correctness, CacheKey.v2 and measured reuse decisions | M6; characterization from M0 |
| M8 | ASX/MCP convergence | M7 mandatory correctness and explicit optimization dispositions |
| M9 | Programme certification and compatibility freeze | M8 |
| M10 | V1 retirement and repository cleanup | M9 zero-dependency proof |

Milestone identities/order remain unchanged. These planning labels are not a new SDDK cycle or registered execution state. A focused standard-JAR-reader correction may attach to the existing authorized compiler work; it does not open the entire programme or interrupt S6 certification.

M10 is intentionally last and mandatory.

## M0 — Baseline and migration fortress

### Value
Freeze what cannot break and identify every V1 dependency before adding new features.

### Deliverables

- exact SHA/SDDK cycle;
- inventories of V1 modules, Gradle includes, production references, tests, scripts and docs;
- installed-distribution baseline;
- characterization of current `computeScriptClasspath`, plugin discovery, CLI commands, LSP metadata loading, registry preparation;
- compatibility fixtures for current plugin JAR path;
- characterize the actual public compile-and-evaluate adapter, both frontends, compiler/mapper reader configuration and installed JDK diagnostics;
- reconcile canonical compiler ports and S6 manifests against implemented consumers before proposing types;
- architecture tests preventing new V2→V1 dependencies.

### Exit

All inventories are machine-generated where possible and the full known gate is green on the baseline candidate.

## M1 — Introspection kernel and admission decision

### Value
Create reusable read/decision surfaces without changing execution semantics.

### Deliverables

- typed `RegistryPreparationDecision`;
- current runtime preparation adapted to it;
- `IntrospectionService` over existing registries/metadata;
- stable DTO schema draft;
- catalog snapshot/digest prototype.

### Exit

Mutation tests prove runtime admission and projected plan cannot diverge.

## M2 — Affordance API / explain / plan / why-not

### Value
PipelineK becomes self-describing and agent-discoverable.

### Deliverables

- `pipelinek api --json`;
- steps/plugins/capabilities resources;
- `steps list/describe` and `explain` human projections;
- `step --plan` / `why-not`;
- format-version compatibility fixture;
- stable machine error codes.

### Exit

An external plugin appears automatically and blocked actions match runtime admission.

## M3 — Static manifests and artifact admission

### Value
Know what code is before loading it.

### Deliverables

- reuse/version the canonical S6 `PipelineKPluginManifest` across all SDK contributor families; do not create a competing Step-only schema;
- artifact resolver/admission ports;
- content-derived digest identity and immutable admitted-byte snapshots;
- runtime contributor cross-check;
- rejected-class-initializer canary;
- incremental adaptation of bundled/external plugin classpath logic.

### Exit

Tampered/rejected plugin is proven not to initialize contributor code.

## M4 — Shared Libraries local-JAR vertical

### Value
Recover V1's most valuable missing product capability with V2 semantics.

### Deliverables

- library identity/release types;
- library static manifest;
- `ScriptDependencyPlan` with explicit compiler/evaluation/discovery views and effective order;
- explicit local JAR resolver;
- CLI `--library` initial syntax;
- compile/eval classpath integration from immutable admitted bytes, including approved generated façades;
- plugin/library ambiguity gate.

### Exit

Installed binary compiles and runs a pipeline using typed library classes; library cannot contribute any canonical runtime contributor family. This is not a proof that arbitrary Kotlin/JVM code cannot perform I/O.

## M5 — Shared Libraries Maven resolution

### Value
Make libraries usable/reproducible in real teams.

### Deliverables

- Maven coordinate resolver for the complete transitive graph, with exact selected versions/digests;
- configured repositories;
- XDG cache;
- API-range and plugin-requirement validation;
- offline-cache behaviour;
- provenance/source facts;
- deterministic ordered classpaths and dependency snapshots outside the checkout; root libraries and ordinary support JARs retain distinct roles.

### Exit

Fresh online resolution then offline repeat succeeds from cache; wrong digest/incompatible requirement fails closed.

## M6 — Diagnostics and LSP v2

### Value
Make the ecosystem discoverable from the editor and failures actionable.

### Deliverables

- diagnostic enricher preserving compiler severity/location and projecting phase/origin;
- catalogue-derived candidate suggestions;
- V2 LSP adapter;
- completion/signature/hover/diagnostic/code actions;
- plugin/library awareness;
- source mapping and real warnings on cold and warm paths; known configuration notices have renderer policy, not blanket filtering.

### Exit

Install/remove a plugin and LSP behaviour changes without LSP code edits.

## M7 — Compiler correctness, CacheKey.v2 and performance gate

### Value
Complete the existing compiler contract and make separate measured decisions about memory artifacts, persistence and sessions.

### Deliverables

- one effective profile driving real adapter configuration and identity;
- canonical CacheKey.v2 including ordered immutable dependency bytes, options, template, generated code and compatibility dimensions;
- internal resolve/admit/compile/load/evaluate phases with a compatible public adapter and fresh evaluation;
- standard JAR-reader policy and real JDK deny/warning/DSL regressions;
- real compiled-representation characterization; no assumption that saving Kotlin classes reconstructs all PipelineK metadata;
- benchmark protocol under existing WU-RP-022 authority;
- bounded memory artifact reuse only after its GO;
- persistence and existing-owner session reuse each need their own accepted decision, implementation and measured GO; ADR-EVO-012/013 in this bundle remain Proposed.

### Work order and exits

WU-M7-01 → WU-M7-04 → WU-M7-02 → optional WU-M7-03. WU-M7-05 and WU-M7-06 require their respective additional approvals/GO within the existing governance; their identifiers do not imply delivery.

Phase/profile/identity/diagnostic correctness is mandatory. If a real reusable representation is unavailable, retain the functional current path and record reuse NO-GO. A failed persistence round trip cannot block correctness work or silently become a fake cache implementation.

Record distinct `MEMORY`, `PERSISTENCE` and `SESSION` decisions, applicable tests and measured evidence. Memory in one CLI JVM cannot accelerate the next CLI process. Persistent cache is conditional, never inferred from a key or a faster second run. Disabled slices remain undelivered and unadvertised.

## M8 — ASX/MCP convergence

### Value
Make agent-first invocation consume the same discovery/decision system.

### Deliverables

- ASX `steps list/describe` backed by introspection;
- generic `pipelinek step` backed by canonical registry execution;
- `command`/`sh` preserve current ASX single-spine design;
- MCP/skills generated/projected from the same tooling model;
- no manually-maintained MCP Step list.

### Exit

A newly installed certified plugin is discoverable/invocable by CLI and MCP without MCP semantic code changes.

## M9 — Programme certification and compatibility freeze

### Value
Prove the whole new surface works as one system before legacy deletion.

### Deliverables

- all mandatory and enabled-slice UAT/AAT green on the same SHA/artifact;
- explicit NO-GO/disposition for disabled optimizations, with tests marked NOT_APPLICABLE by scope and never PASS;
- installed distribution from clean checkout;
- reproducibility evidence;
- migration guide;
- deprecation notices;
- no remaining production dependency on V1;
- decision register for every V1 feature: migrated / superseded / rejected;
- ABI/consumer migration receipt for any public scripting-port change and installed one-shot CLI parity;
- no old V1/cache fallback retained as an optimization escape hatch.

### Exit

`V1_DEPENDENCY_COUNT = 0` for production/build paths. Only historical/reference files remain pending M10.

## M10 — V1 retirement and repository cleanup

### Value
End dual architecture and leave one clean product.

### Deliverables

- external immutable archaeology archive if required;
- delete legacy source modules;
- delete legacy Gradle wiring/dependencies;
- delete legacy scripts/hooks/helpers not used by V2;
- delete stale V1 examples/integration fixtures;
- delete `docs/historico` and superseded legacy docs selected by inventory;
- remove backup/disabled legacy source files;
- remove stale CI/release instructions referring to V1;
- regenerate root README/docs navigation;
- repository search gates for legacy package/module references;
- clean-clone build, tests, distribution and dogfood.

### Exit

One implementation line remains: V2/current architecture. Any surviving historical document is explicitly justified as current public documentation, not legacy residue.

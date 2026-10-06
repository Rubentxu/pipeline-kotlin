# Executive proposal

## 1. Problem statement

V2 is architecturally stronger than V1, but the V1 codebase still exposes several product ideas worth recovering: Shared Libraries, richer IDE assistance, actionable diagnostics, compilation caching intent, plugin/artifact security intent and a more discoverable user-facing CLI.

The wrong strategy is to move V1 classes into V2. V1 implementations contain precisely the patterns V2 has spent significant effort removing: global/service-locator style context, hand-coded registries, partially simulated security, heuristic LSP metadata, incomplete caches and mixed responsibilities.

The correct strategy is to recover **capabilities** while preserving V2's structural laws.

## 2. Product outcomes

At the end of this programme a user should be able to:

- install or select reusable Kotlin pipeline libraries without converting them into runtime plugins;
- inspect all installed Steps, providers, plugins, libraries, profiles and capabilities through a stable JSON discovery surface;
- ask PipelineK what a Step requires and whether an invocation is admissible **without executing it**;
- obtain actionable diagnostics that point to real registered alternatives rather than hard-coded suggestions;
- have an editor/LSP discover newly installed plugins automatically;
- audit which immutable plugin and library artifacts were used for a run;
- receive deterministic compilation identity when dependency bytes change even if paths do not;
- use the same introspection and invocation model from CLI, agent tooling and MCP;
- run a repository that contains **no V1 implementation or legacy debris** after the migration train completes.

## 3. Product boundaries

### Included

- Shared Libraries 2.0.
- Static plugin/library manifests.
- Artifact identity, digest and provenance projection.
- Pre-classloading artifact admission.
- Affordance API for CLI discovery.
- `explain`, `plan` and `why-not` style read surfaces.
- Unified introspection service.
- Actionable diagnostics.
- LSP v2 built from the same metadata authorities.
- compiler profile/phase correctness, `CacheKey.v2` and phase-level performance measurement;
- conditional real artifact reuse with separate memory/persistence/session GO gates.
- XDG-based user state/config/cache conventions.
- Final V1 retirement and repository cleanup.

### Excluded

- Docker/Kubernetes agents or remote worker provisioning.
- New controller/control-plane responsibilities in `pipeline-kotlin`.
- A second execution engine for CLI/MCP.
- Dynamic arbitrary dependency resolution from inside `.pipeline.kts`.
- `@file:DependsOn` as a dependency authority.
- Compiling arbitrary Git repositories at `pipelinek run` time.
- Pretending JVM-level CPU/memory limits are enforceable per pipeline without OS substrate.
- Cedar runtime adoption as part of this programme; the data shape remains Cedar-ready.

## 4. Key refined proposals

### P1 — Shared Libraries are authoring dependencies, Plugins are runtime capabilities

A Shared Library can provide Kotlin types, extension functions, builders and reusable composition over already-existing Steps. It cannot register Steps, handlers, capabilities, directives, events or reactors.

A Plugin can register executable/runtime semantics through published V2 extension seams and is subject to capability admission, provider identity, replay/recovery contracts and certification.

This distinction is enforced structurally and by artifact admission tests.

### P2 — One dependency plan, explicit classpath roles

Replace the current implicit `List<String>` classpath composition with a richer `ScriptDependencyPlan` that keeps SDK, plugins and libraries distinct while still producing the current compile/evaluation classpath.

Plugins may participate in runtime discovery. Libraries never do.

### P3 — Admit plugin code before loading it

Today runtime provider metadata becomes useful after plugin code is discovered. The new static manifest lets PipelineK inspect identity, digest, publisher and declared surfaces before `ServiceLoader` initializes plugin classes.

The first trust state remains `Unverified` until real verification evidence exists.

### P4 — Affordance API rather than a second CLI model

Add a versioned machine-readable surface such as:

```text
pipelinek api --json
pipelinek api steps --json
pipelinek api step scm-git.checkout --json
pipelinek api plugins --json
pipelinek api libraries --json
```

Responses contain typed links/actions expressed as argv arrays. This is HATEOAS-inspired: clients discover available resources and operations instead of hard-coding the entire CLI grammar.

### P5 — Introspection is a read model, never an authority

A new `IntrospectionService` projects existing authorities:

- `StepRegistry` / `StepDescriptor`;
- `StepProviderMetadata`;
- `LspMetadata`;
- profile/catalog providers;
- run/event/output read services;
- library/plugin artifact catalogues.

CLI, LSP and MCP consume that service. None keeps its own Step catalogue.

### P6 — `plan` reuses real admission

Refactor registry preparation into a typed, side-effect-free decision algebra. Runtime preparation then adapts the decision into `ExecutionPreparation`, while `pipelinek step --plan` and `why-not` expose the same decision to users and agents.

There is still one admission implementation.

### P7 — Cache identity before cache implementation

Use the existing scripting contract to separate compilation, loading and evaluation. One effective compiler profile and frozen dependency plan drive actual configuration and `CacheKey.v2`. Certify exact semantic inputs/order first, then measure and prove artifact reconstruction/DSL equivalence. Memory, persistence and session reuse each need their own GO; caching evaluated run state is excluded. Specifications 09/19/20/21 define the concrete obligations.

### P8 — Legacy removal is a product milestone, not housekeeping

V1 removal is last because deleting it earlier destroys a useful behavioural oracle and migration reference. Once all adopted V1 ideas are either implemented in V2 or explicitly rejected, the repository is cleaned in one certified terminal train.

### P9 — Transfer Gradle mechanisms through existing PipelineK owners

Use owned compiler configuration, real generated artifacts, phase-specific diagnostics and compatible class-loading lifetimes. Keep the compiler backend inside `pipeline-scripting-kotlin24`; resolve/admit dependencies ahead of compilation using the current configuration plane. Gradle's daemon and compiler APIs are references, not a new dependency or controller to embed. Reconcile S6 manifests/registries and canonical scripting ports before implementation. See [the refinement review](22-gradle-refinement-review.md).

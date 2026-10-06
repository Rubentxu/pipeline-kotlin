# AAT — Architecture Acceptance Tests

AATs are executable laws. They reject structural regression even when feature tests are green.

**Execution status:** NOT_RUN for this refinement/candidate. A source scan alone is insufficient when a law has an observable behavioural oracle. Scope mandatory/conditional cases as in the [UAT plan](13-UAT-master-plan.md) and [traceability matrix](gradle-traceability.md). NO-GO for a disabled slice is not PASS.

## AAT-001 — V2 production does not depend on V1

During migration: no **new** V2→V1 dependency allowed.  
At M9/M10: dependency count must be zero.

## AAT-002 — No Step-key semantic dispatch

Production coordinator/application semantic code may not introduce plugin-specific `when(stepKey)` / string switches. Allow only explicitly reviewed identity/serialization cases that do not decide execution semantics.

## AAT-003 — One ServiceLoader site per contributor family

Plugin discovery remains isolated in runtime adapter(s); domain code never performs ServiceLoader/classpath scanning.

## AAT-004 — Library cannot implement runtime contributor interfaces

Build/test fixtures declaring a library root or transitive support JAR with any canonical S6 runtime contributor metadata must be rejected by artifact-kind validation. Ordinary unrelated Java services remain usable; classloader boundaries are not an arbitrary-I/O purity proof.

## AAT-005 — Static admission before class initialization

Rejected plugin fixture's class initializer canary is never observed.

## AAT-006 — Introspection is read-only

`pipeline-introspection` code cannot depend on handler execution APIs or mutate registry/state stores. Dependency/source rules enforce it.

## AAT-007 — CLI/LSP/MCP no local Step catalogues

Forbidden production patterns include hard-coded known Step ID/name lists except compatibility fixtures/generated metadata. Tooling must use introspection.

## AAT-008 — Plan and runtime share decision authority

Mutation of each preparation decision branch must affect both runtime preparation tests and plan/why-not tests; no duplicated independent switch.

## AAT-009 — Plugin/library categories remain distinct

`ScriptDependencyPlan` exposes typed plugin and library collections. Runtime plugin discovery accepts only plugin identities.

## AAT-010 — Artifact digest is content-derived

No production constructor accepts a user-supplied digest as verified truth without recomputing/comparing artifact bytes.

## AAT-011 — Delivery is not admission verdict

Search/architecture test ensures runtime allow/deny logic does not branch solely on `Delivery` enum.

## AAT-012 — Trust state must have verifier evidence

Any new `TrustMetadata` subtype requires a verifier/evidence producer test. A source scan fails if a new trust subtype appears without registered evidence mapping.

## AAT-013 — XDG/no repo litter boundary

Default PipelineK cache/config/state path resolvers must not derive from project workspace/current repo path. Only run workspace semantics may legitimately use the workspace.

## AAT-014 — CacheKey.v2 does not use path as artifact identity

Two dependency objects with same path and different digest produce distinct keys; different paths with same bytes/identity normalize according to the specified semantic rule.

## AAT-015 — Scripting host semantic gates preserved

Any change around compilation/cache must retain the current fail-closed DSL construction/return-value semantic tests. Cache work cannot bypass `Kotlin24ScriptingHost` policy behaviour.

## AAT-016 — Output and event planes remain separate

New CLI/introspection code cannot reintroduce console bytes as event projections or event sequence as output cursor.

## AAT-017 — No repo-local dependency lock/config by default

Search and integration tests reject automatic `.pipelinek`, library lock or plugin cache creation in the repository.

## AAT-018 — Final repository has no legacy build project

After M10, settings/build graph must not include V1 modules such as historical root `core`, `pipeline-backend`, `pipeline-config`, `pipeline-lsp-server` or `pipeline-steps-system`.

## AAT-019 — Final docs have no live legacy navigation

After M10, current docs index/README cannot link to deleted legacy docs. References in release notes may mention history textually but not depend on obsolete files.

## AAT-020 — Final source tree has no legacy package namespace used by product

Repository scan enforces the approved current namespace/module map and fails on reintroduction of deleted V1 packages outside explicit migration-test fixtures (which themselves are removed in M10).

## AAT-021 — Compiled artifacts contain code, not invocation state

Dependency/type rules keep experimental Kotlin representation/codec types inside the adapter. Real compile/evaluate tests prove entries contain usable code/metadata/compile diagnostics, with no evaluated receiver, run ID, sink, credentials, coroutine or cached Step result. Both frontends retain current semantics. Mandatory for the representation boundary; cache-retention cases apply to enabled reuse.

## AAT-022 — Canonical ports and phase authority

One adapter implements/reconciles the canonical compiler/engine ports and preserves the characterized public compatibility contract until ABI/consumer migration. CLI/LSP/MCP do not introduce another script engine. Compile-only canary proves the body is not evaluated; only existing IR/entry-point execution enters the durable runtime. No public experimental Kotlin leakage or duplicate dispatcher.

## AAT-023 — One effective profile and immutable ordered identity

Identity material is derived from the actual profile consumed by compiler/mapper, admitted immutable bytes and the effective ordered classpath. Behavioural/property fixtures vary options, transitive bytes, order and relocation; canonical framing handles null/empty/ambiguous values. Sorting unordered catalogue metadata must not sort semantic classpath order. A deterministic barrier catches digest-then-mutable-reopen. Do not pass with a disconnected key-builder unit test.

## AAT-024 — Persistent publication/integrity boundary (PERSISTENCE)

Fault injection/multiprocess tests prove atomic complete publication, cancelable coordination, bounds/path safety, compatible metadata and integrity before class loading. Killed writers/corruption yield safe miss/recompile. Invalid artifact admission remains rejection. Cache checksums are not authenticity against a party able to rewrite both code and metadata; shared/remote executable caches are not enabled by this ADR.

## AAT-025 — Owned resource leases and fresh invocation

An existing adapter/worker owns loaded/compiler resources; callers borrow scoped leases and supply fresh contexts. Eviction/profile change/close retires only owned resources after active borrowers, including error/cancellation. Bound retained artifacts/queue where enabled, observe global memory separately and characterize static-state leakage. MEMORY/SESSION retention cases are conditional; ordinary invocation/resource correctness remains mandatory.

## AAT-026 — Diagnostics and reader policy are not blanket suppression

Both productive host and mapper consume the one reader/profile policy. Real deny/warning/error tests preserve raw severity, mapping and DSL checks. No productive global Unsafe `allow`, blanket stderr/INFO/warning filter or synthetic-location deletion is introduced as the fix. Narrow human configuration-notice presentation retains structured evidence. Existing event/output schemas and contributor registration remain authoritative.

## AAT-027 — Session reuse introduces no second execution product (SESSION)

Module/dependency and installed-behaviour checks reject a new daemon/controller/protocol, provisioner or pipeline scheduler used to deliver this slice. Reuse belongs to the existing compiler owner; actual backend cancellation and global environment disposal have characterized scope. Standalone CLI still runs through canonical ports/runtime.

## AAT-028 — Cache hit cannot become admission authority (enabled reuse)

Hit and miss paths use the same current artifact/admission/frozen composition and typed Step preparation authorities. Revocation and capability mutation tests reject before code/handler use as appropriate. Cache entries/catalogue digests never store an allow/deny verdict, and no cache-local Step registry or event journal appears. All canonical SDK contributor families participate.

# UAT Master Plan

UAT validates user-observable product behaviour using the installed distribution whenever applicable.

**Execution status:** NOT_RUN for this refinement/candidate. These are acceptance plans, not implementation results. Existing certification evidence must be re-associated with an exact future candidate before it counts.

Mandatory cases cover compatibility, phases/profile/identity, admission and diagnostics. `MEMORY`, `PERSISTENCE` and `SESSION` cases apply only to enabled slices with a recorded GO. A disabled slice may be NOT_APPLICABLE only with an explicit NO-GO, no advertised surface and a traceability disposition; it is never PASS. Conditional tests still run for any equivalent existing mode being changed. Use real compiler/evaluation/handler counters; timings or mocked successful artifacts do not prove reuse.

## UAT-001 — Existing pipeline compatibility

**Given** a certified current V2 pipeline with no new libraries  
**When** validated and run after each milestone  
**Then** outcome, Step semantics, replay/recovery and relevant event/output contracts remain compatible.

## UAT-010 — Affordance root discovery

- run `pipelinek api --json`;
- assert supported schema/format version;
- follow every advertised collection link;
- verify no advertised command is syntactically invalid.

## UAT-011 — Plugin automatic discovery

Install a reference external plugin; `api steps` and `steps list` show it without modifying CLI code. Remove it; catalogue/digest change.

## UAT-012 — Plan is effect-free

Use a Step handler fixture that increments a durable canary if executed. `--plan` must report READY/BLOCKED while the canary remains untouched and no Step execution lifecycle appears.

## UAT-013 — Why-not missing capability

Attempt `http.request` without network egress. `why-not` and real execution rejection must name the same missing capability and real execution must not reach transport.

## UAT-014 — Compiler facts are current projections

Where advertised by the versioned tooling model, compiler/profile/dependency and enabled reuse facts reflect the actual adapter/admitted snapshot. Change effective profile or library bytes; projections and identity update coherently. Disabled persistence/session modes do not appear as available commands/capabilities. CLI/LSP/MCP consume the same read model; no transport-specific hard-coded profile or independent catalogue is introduced.

## UAT-020 — Plugin rejected before class initialization

Use a plugin with a class-initializer canary and invalid/tampered admitted identity. Verify rejection and absence of canary effect.

## UAT-021 — Plugin runtime manifest cross-check

Static manifest says Step set A; contributor exposes divergent set B. Composition must fail closed with stable mismatch code.

## UAT-030 — Local Shared Library

Build an external library JAR exposing typed Kotlin class + `StageScope` extension. Run installed PipelineK with `--library <jar>`; script imports and uses both successfully.

## UAT-031 — Library cannot become plugin

Parameterize a library root and transitive support JAR with each canonical SDK runtime contributor family (including current Step SPI compatibility). Hidden contributor declaration/metadata fails `AMBIGUOUS_ARTIFACT_KIND` before contribution; frozen registries remain unchanged. Ordinary unrelated Java services are not rejected solely for having `META-INF/services`. This gate does not prove arbitrary JVM code free of I/O.

## UAT-032 — Maven Shared Library

Resolve `group:artifact:version`, compile pipeline, record version/digest/provenance in evidence, then repeat from cache.

## UAT-033 — Offline library repeat

Warm cache online; execute in offline/no-network mode; exact library release resolves from cache. Missing uncached release fails with actionable diagnostic instead of network fallback.

## UAT-034 — Unsatisfied plugin requirement

Library declares requirement on plugin/version not installed. Validation fails before pipeline execution and provides describe/install guidance where available.

## UAT-035 — No repository litter

After validate/run/library resolution/agent invocation, compare project tree. No PipelineK-owned persistent config/cache/state is added to the repository.

## UAT-036 — Complete transitive graph and declared visibility

External library A imports class from B; B imports C. Resolve the complete graph outside the checkout, record exact selected versions/digests/effective order and run installed PipelineK online then offline. Root manifests and ordinary support JARs are handled according to their distinct roles. Mutating C changes compilation identity; conflicting canonical runtime contributors are rejected even in C. Declared API/SDK types retain identity; unexported compiler internals are not made available as authoring API. Repeat from a different install root with byte-identical semantic inputs.

## UAT-040 — Actionable unknown Step

Misspell a Step. Diagnostic contains stable `UNKNOWN_STEP`, real nearest registered candidates and a `describe` affordance. No hard-coded candidate appears if not installed.

## UAT-041 — LSP external plugin

Start V2 LSP with plugin absent, capture completion; add certified plugin and restart/reload composition; completion/signature/hover include plugin Step without LSP source changes.

## UAT-050 — CacheKey content invalidation

Prepare library/plugin JAR A, replace bytes at the same path with B, prepare a new admitted plan. `CacheKey.v2` must differ; root and transitive dependency changes are covered. If reuse is enabled, the new plan misses/recompiles rather than executing A. This describes a required v2 gate, not evidence of an existing stale disk cache.

## UAT-051 — Cache key reproducibility

Two clean installs with byte-identical admitted inputs and the same logical source identity produce identical semantic cache key despite different installation root paths. Logical source renaming may intentionally change identity where it affects compilation/debug behaviour. Warnings remap to the current location; a cache path is not a source location.

## UAT-060 — Inline Step parity

Invoke a Step through `.pipeline.kts` and generic `pipelinek step` with equivalent input/capabilities. Verify same handler, capability admission and compatible terminal semantics.

## UAT-061 — Agent discovery

Agent/MCP client starts only from root introspection, follows resources to discover a newly installed plugin and invokes it without a precompiled per-Step tool list.

## UAT-090 — Pre-retirement V1 zero-dependency proof

Before deletion, automated inventory reports zero production/build/test runtime dependency from V2/current distribution to V1 modules.

## UAT-100 — Post-retirement clean clone

After V1 deletion:

1. clone exact candidate;
2. no cached Gradle state;
3. run full required gate;
4. build distribution;
5. install distribution;
6. run representative V2 pipeline;
7. run Shared Library scenario;
8. run external plugin scenario;
9. run `pipelinek api` discovery;
10. run LSP smoke;
11. scan repository for forbidden legacy module/package references.

All must pass on the same SHA.

## UAT-052 — Effective classpath order (mandatory)

Use two JARs exposing the same symbolic class with different behaviour. Reverse their effective order while retaining the same bytes/identities. A supported shadowing policy must change key and observed binding consistently; a forbidden conflict must fail deterministically before use. Do not sort a semantically ordered classpath into an unchanged key. Permuting an unordered catalogue input with unchanged effective classpath must keep identity stable.

## UAT-053 — Complete effective profile/source identity (mandatory)

Change actual language/API, JVM target, compilation JDK/API roots, compilation-affecting option, default import, template, lowering/façade bytes/schema or DSL/adapter compatibility revision. Assert each applicable semantic change updates identity and reaches the real compiler, not only a key builder. Frame ambiguous/null/empty values without collisions. Byte-identical install relocation stays stable; source identity changes follow the documented rule.

## UAT-054 — Compilation does not evaluate the body (mandatory)

Through the canonical internal compile boundary, compile a real script with an observable top-level canary and valid declarations. Compilation succeeds with zero body/evaluation/handler invocations and yields a usable representation. Evaluate it with the supported frontend: expected body/evaluation and handler counts occur then. The current public compatibility adapter still returns its characterized evaluated result. Reconcile an API migration before removing that adapter.

## UAT-055 — Fresh evaluation and semantic equivalence (mandatory; warm branch for enabled reuse)

Run the same compiled source with invocation A then B using distinct params, receiver, workspace, credentials sentinel and event/output sink. Both declarative IR construction and scripted dynamic entry points see their own context and current admissions. No old Step output, closure, run ID or sink survives. Cover expression/null/Unit/no-value, construction failure, returnStdout/returnStatus, discarded carriers and dynamic control flow. Match the actual baseline severity and DSL-honesty corpus; do not turn an existing INFO into a claimed error.

## UAT-056 — Real memory hit (MEMORY)

Two identical requests inside the same supported owner perform one actual compile, two fresh evaluations and the expected independent executions. Compile warnings remain observable. Changing source/profile/dependency bytes produces a miss. A new one-shot CLI process does not claim this in-memory entry.

## UAT-057 — Concurrent same-key subscribers (MEMORY)

Synchronize overlapping compatible requests deterministically. Exactly one actual compilation is shared; each surviving caller evaluates independently. Cancel one caller while another still needs the result: the survivor succeeds and the canceled caller does not evaluate. Last-subscriber cancellation follows actual backend stop/cleanup semantics. Distinct profiles never share incompatible objects.

## UAT-058 — Bounded lease-safe memory eviction (MEMORY)

Fill approved entry/byte limits with real artifacts. Hold an active borrower, force eviction and prove its execution stays valid; resources close once the last lease is released. Inactive entries are reclaimed. Observe loaded-resource/heap retention separately from counted cache bytes. No run context or credentials sentinel remains reachable from entries after release.

## UAT-059 — Persistent hit in a fresh JVM (PERSISTENCE)

Process A compiles and publishes a real artifact, then exits. Process B with compatible current admitted inputs performs zero actual compiles, loads the artifact and evaluates with B's fresh context. Repeat after install relocation; outputs, source locations and warnings remain correct. Count compiler entry calls and forbid network as appropriate; a faster JVM is not the oracle.

## UAT-062 — Diagnostic/source-map fidelity (mandatory; warm branch for enabled reuse)

A real warning, real type error and lowering/source-map fixture retain raw severity, code/message and current source location through CLI/LSP structured projection. Compiler diagnostics can accompany valid hits; evaluation failure from invocation A is not cached or replayed into B. Relocate the source/install according to identity rules and verify current paths. Human configuration-notice rendering does not erase structured diagnostic evidence.

## UAT-063 — Current admission on a hit (enabled reuse; mandatory admission law)

Warm a valid entry, then revoke/change plugin/library approval or preparation capability in the current context. Invalid artifact admission prevents executable cache loading; rejected-code initializer canary remains untouched. A valid artifact with a currently missing Step capability follows the same typed preparation rejection as a cold path. Recompilation cannot override revocation, and cached catalogue/readiness is not a verdict.

## UAT-064 — Immutable bytes at the admission/compile boundary (mandatory)

Use a deterministic barrier after digest/admission and before compiler/classloader consumption. Replace the original mutable path with different bytes. Consume the frozen admitted snapshot or re-admit before use; never execute the replacement under the old identity. Run for local root/transitive JARs and every enabled cache mode. A scheduling-dependent race test with no barrier is not evidence.

## UAT-065 — Interrupted writer and process coordination (PERSISTENCE)

Kill a writer at each temporary-metadata/payload/publication boundary. A concurrent/new reader sees absence or a complete integrity-checked entry, never executable partial content. Competing writers coordinate with bounded cancelable waits; canceled/killed owners leave recoverable state. Retention does not delete leased entries. Test the promised crash-durability contract or document absence/recompile as the permitted outcome.

## UAT-066 — Invalid cache is a safe miss (enabled reuse; disk cases PERSISTENCE)

Inject incompatible format/profile/entry metadata and, where persisted, corrupt/truncated/oversized metadata or payload and unsafe paths. Reject before class loading, evict/bypass and recompile from current admitted inputs; a resulting real compile error is preserved. Invalid plugin/library bytes remain admission failures. No exception-only fake pass or empty artifact substitute is allowed.

## UAT-070 — Standard reader under Unsafe deny (mandatory)

In a fresh installed JDK 24+ JVM with `--sun-misc-unsafe-memory-access=deny`, force real host compilation and mapper/compiler JAR reading with the relevant Kotlin distribution. Valid source succeeds; type-error source fails with its location; real source warning remains. Removing standard-reader policy in a mutation must expose the original failing reader path. Test both productive configuration sites; do not add blanket stderr filtering or production `allow` to pass.

## UAT-071 — Source warnings and configuration notice (mandatory)

Exercise genuine compiler warning/INFO/error fixtures and the Kotlin reader configuration notice. Preserve structured severity/location/raw message and existing return-value/DSL-checker semantics on cold and enabled warm paths. Default human rendering may omit the narrowly identified configuration notice; explicit diagnostic output retains it. Normal source warnings and real synthetic/lowered diagnostics remain visible/actionable. Output and event planes remain distinct.

## UAT-072 — Actual JDK/target matrix (mandatory)

Certify canonical JDK 21 baseline and the existing additional JDK 25 gate against actual distribution Kotlin/profile/target settings. Assert emitted class target and compilation API-root behaviour; JVM target alone does not restrict available JDK APIs. The JDK 24+ deny probe is additional focused evidence. A blocked JDK 25 run remains BLOCKED/NOT_RUN until completed; JDK 24 success cannot certify JDK 25. Upgrades run the same corpus before adoption.

## UAT-073 — Honest validate and pure Step plan contracts (mandatory)

Characterize `validate` compile-plus-DSL-construction evaluation with a top-level canary; retain/document actual behaviour. Separately `step --plan` takes typed invocation data and leaves handler/effect canaries untouched. Do not advertise arbitrary Kotlin evaluation as pure because no registered Step ran. An optional restricted compile-only mode requires an explicit compatibility decision and its own acceptance contract.

## UAT-080 — Warm existing owner and profile retirement (SESSION)

Reuse an actual existing long-lived owner with compatible requests. Measure retained compiler scope and startup amortization, with fresh invocation context and no old run/sink references. Switch incompatible profile/dependency composition: open a compatible scope, drain old borrowers and retire old resources. Draining/closed owners reject requests with typed diagnostics. No new daemon/protocol is installed; one-shot CLI remains equivalent.

## UAT-081 — Queue, cancellation and failure cleanup (SESSION; shared compilation cases MEMORY)

Bound the real compiler queue. Cancel queued work before body evaluation; distinguish queue deadline from compiler and Step deadlines. Exercise in-flight cancellation, compile failure and owner close. Report actual backend termination: cooperative thread/coroutine cancellation is not proof that CPU work stopped. If a supported isolated fallback is used, prove its owned process tree exits. Dispose owned resources without disposing another session's global Kotlin state.

## UAT-082 — Retention and state isolation (SESSION; loaded reuse cases MEMORY)

Run the approved repetition/profile-transition corpus including class/static-state canaries. Fresh receivers alone do not prove static-state isolation. Retain only behaviour compatible with the supported isolation contract; otherwise bypass loaded-class reuse/fresh-scope or record NO-GO. Report RSS/heap/native memory/file handles plus enforced queue/cache/lease limits under approved budgets, not invented universal thresholds. Release failures and cancellations too.

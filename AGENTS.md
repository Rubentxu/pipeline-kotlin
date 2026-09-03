# AGENTS.md

## V2 DEVELOPMENT PRIME DIRECTIVE

1. Authority: docs/v2/ (ROADMAP, ADRs, MIGRATION_PLAN, FITNESS, CURRENT_STATE).
2. Scope firewall: no implementation change without
   Milestone → Backlog → Exit criterion → Gate/UAT traceability.
3. No V1 repair on the V2 critical path; classify + quarantine instead.
4. No V2 dependency on :pipeline-steps-system:compiler-plugin.

### Exceptions (require explicit human approval + new Milestone)

A. Critical security fix on V1 with no V2 equivalent.
B. INC reclassification promoting a QUARANTINED component.
C. Compatibility shim required by an in-flight UAT.
D. Backlog item with documented Exit criterion + Gate owner.

## V2 TESTING RULES

### Execution economics ( Gradle )

1. Inner loop: targeted runs only (`--tests 'UatLocal004*'`), warm daemon,
   NO `--rerun-tasks` while iterating.
 2. Full round gate = `./gradlew -p v2 check` (incremental) runs ONCE per
    apply/verify round, as the final gate. Never per-iteration. Gradle's
    content-hash up-to-date checks are the freshness oracle: a no-op
    `check` returning BUILD SUCCESSFUL with all tasks UP-TO-DATE is a
    VALID green — it proves nothing changed since the last green
    (measured 2026-08-30: forced gate 977s vs 1s incremental no-op).
    Escalate to `check --rerun-tasks` ONLY after (a) a run killed
    mid-flight, (b) suspected stale green, or (c) hidden-state suspicion;
    then reconcile ONCE with the rule-4 budget before trusting
    incremental again (reconciliation measured 948s).
3. Never `--no-daemon` for repeated runs; the daemon JVM stays warm.
4. Wrap every Gradle invocation in `timeout` — silent hangs are defects
   of the harness, not the code under test. Two regimes:
   - Targeted / inner-loop runs: `timeout 600` (fixed).
   - Full round gate (`check` incremental, or escalated
     `check --rerun-tasks`): DERIVED budget =
     last green round-gate duration × 1.3, floor 600, ceiling 1800.
     Compute and record the budget in the round plan BEFORE the run;
     put the observed duration in the round receipt. An over-budget
     kill is a signal: either the suite legitimately grew (re-derive
     the baseline explicitly and document why) or something degraded
     (diagnose per rules 28-31). NEVER raise a timeout mid-run.
5. Base-SHA evidence is immutable: never recompile a base worktree to
   "re-prove" a result already captured (cite the prior XML/SHA).
6. `v2/gradle.properties` MUST keep enabled: `org.gradle.caching=true`,
   `org.gradle.parallel=true` (module-level parallelism only).

Canonical inner loop (TDD red-green, seconds — measured 2s no-op / 22-40s
with incremental compile):

```bash
timeout 600 ./gradlew -p v2 :pipeline-application:test --tests 'UatLocal004*'
timeout 600 ./gradlew -p v2 :pipeline-step-sdk:runtime:test --tests 'DurableShellExecutorAdversarialTest'
```

Round gate (once per apply/verify round, not per iteration). Incremental
by default — the escalated budget (rule 4) applies to the escalation form
only; current escalated baseline 977s → budget 1270:

```bash
./gradlew -p v2 check                              # incremental (default)
timeout 1270 ./gradlew -p v2 check --rerun-tasks   # escalation only
```

Quick interface: `just gate` / `just gate-escalate` / `just t '<pattern>'` /
`just corpus <n>` / `just changed [base]` (see justfile test-efficiency
group).

### Test design ( hangs and processes )

7. Every UAT / integration test class MUST declare JUnit `@Timeout`
   (class-level or per-test). A hung test must FAIL in seconds, never block
   the test JVM (lesson: 47-min hang from an unwired watchdog).
8. Teardown hygiene: `destroyForcibly()` in `finally`; kill the whole
   process group (`setsid` children survive parent kill); `@AfterEach`
   must guarantee zero living children.
9. Env assertions use `printenv VAR` as the oracle (emits exact value,
   quoting-safe with special chars), not `echo`.
10. `Thread.sleep` is allowed ONLY for state positioning (e.g. ensuring a
    process is mid-execution before killing it). Never sleep to wait for a
    condition — poll with a deadline instead.
11. Do NOT add `maxParallelForks` to UAT modules: these tests verify
    timing semantics (heartbeat staleness, backoff, LOST classification)
    that degrade under CPU contention and turn deterministic suites flaky.
    EXCEPTION PATH: functional suites without timing semantics (e.g. the
    compatibility corpus, one method per fixture since 2026-08-30) MAY be
    parallelized — but only as a measured, explicit decision with a
    recorded before/after baseline, never as a default.
12. Prefer SDK-level unit tests when semantics do not depend on real
    processes. Real-process UATs are reserved for kill/resume/durability
    semantics that cannot be mocked faithfully.
13. Kotlin string interpolation: shell `$VAR` inside Kotlin strings needs
    `${'$'}VAR`.

### Integrity

14. Zero-fabrication: every reported test result comes from a fresh run of
    an existing file; record argv, exit code, and output digest.
15. Never weaken assertions, skip, or ignore a test to make a gate pass.
16. Never classify a failure as "pre-existing" without fresh base-vs-head
    evidence (worktree method) — the cycle base SHA is the comparison
    point, not a mid-cycle commit.

### Validation ladder ( iteration protocol )

17. Always validate at the MINIMUM sufficient level; escalate ONLY on
    green. L0 compile (`:pipeline-application:compileTestKotlin`, ~10 s)
    → L1 single test (`--tests "...UatLocal005EnvSpecialCharsTest.WS-S-008*"`)
    → L2 full class (`--tests 'UatLocal005*'`) → L3 related set
    (package/feature filter) → L4 module suite → L5 full `check`
    (the round gate). Never jump L1→L4; exception: cross-cutting changes
    (build files, engine core, base DSL, event model) → L4 directly.
     Derive the level from `git diff --name-only <base>` or `just changed
     [base]`; that output is advisory and MUST NOT be copied as an unfiltered
     module-suite command.
     - Docs-only: documentation/link validation; no Gradle.
     - Test-only in module M: `:M:compileTestKotlin`, then the edited test
       methods; run the full class only at batch end.
     - Production in M: `:M:compileTestKotlin` → owning test methods → owning
       class; add direct consumer tests only where the changed public boundary
       is exercised. Do not run bare `:M:test` before method/class evidence is
       green.
     - `v2/compatibility/<NN>-*.pipeline.kts`: run `just corpus <NN>`; run
       `CompatibilityCorpusTest.allCorpusFixturesAreDiscoverable` only when
       fixture inventory changes.
     - Shared domain/event/runtime/scripting API contracts, Gradle build files,
       or architecture rules: L4 is affected module suites plus relevant
       fitness tests. Add application/UAT tests only for impacted consumers.
     - L5 `just gate` is justified only as the final apply/verify or release
       gate, once lower levels are green.

     `--tests` is mandatory at L1/L2. A bare `:M:test`,
     `:pipeline-application:test`, or `check` is never a discovery mechanism.
     Rules 4, 23-31 continue to govern timeouts, log capture, XML canaries,
     escalation, and hang diagnosis.
18. One behavior per iteration. Batch the edits, then validate once — no
    validation between micro-edits. L0 after every batch: a 10 s compile
    error beats a 60 s test failure.
19. Test-only edits: L0+L1 while iterating, L2 at batch end. Production
    edits: L1→L2→L3 after each change batch.
20. A green test is an asset — do NOT re-run it unless (a) the production
    code it covers changed, (b) its test class changed, or (c) an L4/L5
    milestone was reached. Track the last green run per (test, class, module).
21. TDD discipline: RED must fail for the EXPECTED reason (read the
    assertion message, not just the failure). A timeout or compile error
    is NOT a valid RED. GREEN = minimal implementation, validated at L1.
22. Use `--fail-fast` when running more than one test in iteration.

### Output capture and result truth

23. NEVER pipe test output through `| tail` under `timeout`: the output is
    lost when the process is killed. Safe pattern (`<budget>` per rule 4:
    600 targeted, derived for the round gate):
    `timeout <budget> ./gradlew ... > /tmp/gradle-run.log 2>&1; tail -n 30 /tmp/gradle-run.log`
24. Long builds run backgrounded with polling:
    `nohup timeout <budget> ./gradlew ... > /tmp/gradle-run.log 2>&1 &` then
    poll `tail -n 20 /tmp/gradle-run.log` — keep editing while it runs.
25. Result truth is the JUnit XML in `build/test-results/test/`, NOT the
    console or the Gradle exit code. When a run MUST have executed, use
    the canary: delete `TEST-<Class>.xml` first, run, verify it regenerated.
26. XML `timestamp` is UTC while `ls` shows local time (10:27Z == 12:27
    local). Convert before concluding a result is stale.
27. After a build killed by timeout, distrust `BUILD SUCCESSFUL` /
    `UP-TO-DATE`; confirm with the canary (rule 25) before interpreting.

### Hang protocol ( a test that never ends )

28. Do NOT retry blindly or raise the timeout. Isolate first: run the
    class's tests one by one (`--tests "...Test.method*"`) to identify
    which one hangs.
29. With the process alive: `jcmd <pid> Thread.print` on the Gradle worker
    JVM; `ps aux | grep sh` for orphaned shell processes.
30. If a test passes isolated but hangs in the class: suspect shared state
    or inter-test interference within the same JVM. Report it, do not
    ignore it.
31. Shell hygiene: never `pkill -f <pattern>` where the pattern matches
    the pkill command line itself. Use `pkill -f GradleDaemon` or kill by
    PID via `jps`.

### End-of-round checklist

- [ ] Exactly one L4/L5 full run actually executed (canary verified, fresh XML).
- [ ] Fresh XMLs show `failures="0" errors="0"` covering everything touched.
- [ ] No green test modified without documented reason.
- [ ] No background builds or orphan processes left alive.

---

# Intelligent Change-Scoped Testing

## Purpose

Coding agents MUST use a **change-scoped, progressive and evidence-driven testing strategy**.

The objective is to obtain the smallest sufficient verification evidence for the active code change while preserving confidence in correctness.

During normal implementation work, agents MUST NOT repeatedly execute the complete repository test suite.

Full-project verification is reserved for explicit verification boundaries such as:

* final verification;
* pre-merge validation;
* release preparation;
* CI gates;
* repository-wide changes;
* changes whose impact cannot be bounded safely;
* explicit user requests.

The testing strategy defined here is currently performed by the agent using repository information, Git state and the persistent testing state file.

It is an informed and conservative approximation, not authoritative static or dynamic impact analysis.

---

# Persistent Testing State

Before selecting or discovering tests, read:

`.agent/TESTING-STATE.md`

If the file does not exist, create it using the project's testing topology discovered during the task.

This file is persistent working knowledge shared across agents and sessions.

It exists to avoid repeatedly rediscovering:

* project structure;
* components and modules;
* test frameworks;
* test commands;
* test selectors;
* component → test relationships;
* dependency and contract relationships;
* expensive test suites;
* previous verification evidence;
* unresolved testing gaps.

The state file is **advisory, not authoritative**.

The following sources have higher authority:

1. current Git state;
2. source code;
3. build/project manifests;
4. dependency declarations;
5. executable tests;
6. actual test results;
7. CI/build configuration;
8. `.agent/TESTING-STATE.md`.

If the state file conflicts with current repository evidence, update the state file.

Never blindly trust stale entries.

---

# Core Testing Principle

Always begin with the narrowest defensible test scope.

Use progressive widening:

```text
Active Change
    ↓
Affected behavior / SUT
    ↓
Direct tests
    ↓
Owning component tests
    ↓
Dependency / contract tests
    ↓
Risk-specific checks
    ↓
Full verification only when justified
```

Testing scope MUST be determined before selecting a runner command.

Commands are execution mechanisms, not testing strategy.

---

# 1. Determine the Active Change

Before running tests, inspect the current repository change.

Use Git and repository state to identify relevant:

* modified files;
* added files;
* deleted files;
* renamed files;
* changed tests;
* changed configuration;
* manifests;
* dependency declarations;
* schemas;
* migrations;
* generated-code inputs;
* build files;
* public interfaces;
* protocols;
* infrastructure definitions.

Reason about the current active change, not the whole repository.

Do not begin normal implementation work by running every test.

---

# 2. Identify the System Under Test

For the active change, determine the smallest meaningful System Under Test (SUT).

A SUT may be:

* function;
* class;
* module;
* namespace;
* package;
* library;
* crate;
* component;
* service;
* application feature;
* frontend component;
* API;
* database boundary;
* schema;
* build target;
* plugin;
* infrastructure component.

Use repository topology, imports, manifests, dependency declarations and existing test organisation.

Classify impact confidence when useful:

```text
KNOWN
LIKELY
UNKNOWN
```

Do not silently convert `UNKNOWN` into `NOT AFFECTED`.

---

# 3. Read Existing Testing Knowledge Before Discovering It Again

Consult `.agent/TESTING-STATE.md` before probing build/test tooling.

Reuse previously established information such as:

* project test commands;
* unit-test selectors;
* module/package selectors;
* integration commands;
* contract-test commands;
* full verification command;
* environment requirements;
* known expensive suites.

Do not repeatedly invoke:

* `--help`;
* runner discovery commands;
* broad repository searches;
* build-system inspection;

when that information has already been established and remains valid.

If existing testing knowledge is stale, update only the affected knowledge.

---

# 4. Build a Lightweight Impact Model

For each changed artifact, reason approximately through:

```text
change
  → changed artifact
  → owning SUT/component
  → dependencies/contracts
  → possible consumers
  → relevant verification
```

Consider at least:

* direct ownership;
* compile dependencies;
* reverse dependencies;
* runtime dependencies;
* public interfaces;
* shared libraries;
* schemas;
* serialization contracts;
* database contracts;
* generated artifacts;
* configuration consumers;
* external interfaces.

The impact model may cross languages and toolchains.

Example:

```text
OpenAPI schema
   ↓
backend service
   ↓
generated TypeScript client
   ↓
frontend component
```

Relevant verification can therefore include tests from multiple languages.

Never restrict impact analysis to file extensions or same-language tests.

---

# 5. Progressive Verification Levels

## Level 0 — Cheap deterministic checks

When relevant to the changed surface, first use inexpensive checks such as:

* compile;
* type-check;
* syntax validation;
* lint;
* formatting validation;
* schema validation;
* local static analysis.

Run scoped versions when available.

Do not automatically execute unrelated repository-wide checks.

---

## Level 1 — Direct behavioral tests

Run the tests most closely associated with the modified behavior.

Prefer:

* individual test;
* test function;
* test class;
* test file;
* test target;
* package/module subset;
* tag/filter;
* affected feature tests.

If implementing new behavior using TDD:

```text
focused failing test
    ↓
minimal implementation
    ↓
same focused test
    ↓
nearby affected tests
```

Do NOT execute the entire repository test suite during each Red/Green/Refactor iteration.

---

## Level 2 — Owning component verification

If direct tests are insufficient to establish confidence, widen to the owning:

* module;
* package;
* library;
* service;
* application component;
* build target.

Only widen when there is a reason.

Do not include unrelated components.

---

## Level 3 — Dependency and contract closure

Widen when the change crosses an important boundary.

Examples:

* public API;
* public library interface;
* OpenAPI;
* GraphQL;
* Protobuf;
* database schema;
* event schema;
* serialization format;
* shared configuration;
* generated client;
* plugin interface;
* runtime protocol.

Identify likely consumers.

Run relevant:

* consumer tests;
* provider tests;
* contract tests;
* integration tests;
* reverse-dependent component tests;
* generated-code verification.

Document the reason for widening.

---

## Level 4 — Risk-specific verification

Run specialised verification when justified by the active change.

Examples:

* security tests;
* architecture tests;
* migration tests;
* compatibility tests;
* performance tests;
* concurrency tests;
* resilience tests;
* mutation testing;
* E2E;
* UAT.

Do not run these merely because they exist.

---

## Level 5 — Full verification

Execute the complete project verification profile only when justified.

Typical reasons:

* explicit user request;
* final `verify` phase;
* pre-merge gate;
* release gate;
* repository-wide change;
* global build-system change;
* global test infrastructure change;
* core dependency with broad unknown reach;
* impact cannot be bounded confidently;
* targeted failures reveal wider impact;
* repository policy explicitly requires it.

Full verification MUST NOT be used merely because it is easier than reasoning about test impact.

---

# 6. Prefer Evidence over Test Quantity

For each candidate test or check, ask:

> What plausible regression caused by the active change could this verification detect?

If there is no meaningful answer, it is probably not part of the normal implementation feedback loop.

Running more tests is not automatically better testing.

The objective is:

> sufficient relevant evidence with minimum unnecessary execution.

Not:

> minimum number of tests at any cost.

Correctness always takes precedence over optimisation.

---

# 7. Evidence Reuse

Do not rerun successful verification unnecessarily.

A previous result may be considered reusable when relevant inputs have not changed.

Consider evidence stale when any relevant input changes, including:

* production code under test;
* dependencies of the SUT;
* corresponding test code;
* shared schemas;
* configuration;
* build configuration;
* dependency versions;
* generated-code inputs;
* test environment;
* test runner/toolchain.

Prefer selective invalidation.

Example:

```text
component A tests PASS

later:
component B changes
and B is unrelated to A

→ keep A evidence
→ test B
```

Do not rerun A merely because another edit occurred elsewhere.

---

# 8. Failure Handling

When a selected test fails:

1. inspect the failed test;
2. determine whether the failure is plausibly caused by the active change;
3. fix the smallest relevant cause;
4. rerun the failed test first;
5. rerun its local affected batch;
6. widen only if the failure suggests broader impact.

Do not react to every failure by immediately running the complete repository suite.

Never weaken a meaningful test merely to obtain a green result.

---

# 9. Unknown Impact

Uncertainty must be visible.

Use explicit descriptions such as:

```text
KNOWN:
- payments component changed
- payments unit tests affected

LIKELY:
- checkout integration may depend on modified API

UNKNOWN:
- could not establish whether reporting consumes this schema
```

If the uncertainty could hide a meaningful regression:

1. inspect the relationship;
2. update the testing state if resolved;
3. widen verification when necessary.

Do not claim successful scoped verification when material impact remains unknown.

---

# 10. Persistent Learning

When useful testing knowledge is discovered, update:

`.agent/TESTING-STATE.md`

Useful persistent knowledge includes:

* component topology;
* component dependency;
* SUT → test mapping;
* contract → consumer mapping;
* stable test command;
* stable test selector;
* expensive suite;
* required environment;
* common impact rule;
* testing gap;
* misleading or obsolete command.

Do not put raw terminal output or long logs into the state file.

Store concise, reusable knowledge.

---

# 11. Active Verification State

During a meaningful implementation slice, maintain the `Active Change` section of `.agent/TESTING-STATE.md`.

Record:

* changed surfaces;
* likely SUTs;
* known impact;
* likely impact;
* unknown impact;
* planned verification;
* completed verification;
* invalidated evidence.

This allows another agent or a later session to continue without reconstructing all testing context from scratch.

---

# 12. End-of-Slice Testing Report

At the end of a meaningful coding slice, provide a concise testing report:

```text
Changed:
- ...

Affected SUT:
- ...

Verification executed:
- <test/check> — <reason>

Evidence reused:
- <evidence> — <reason still valid>

Verification deliberately not executed:
- <suite/component> — <reason outside impact closure>

Unknown impact:
- ...

Result:
PASS | FAIL | BLOCKED | BROAD VERIFY REQUIRED

Full verification required now:
YES | NO

Reason:
...
```

The explanation should describe semantic reasons, not merely commands.

Prefer:

```text
Selected checkout contract tests because the modified
payments schema is consumed by checkout.
```

instead of:

```text
Ran ./gradlew test --tests CheckoutTest.
```

---

# 13. Forbidden Behaviors

Coding agents MUST NOT routinely:

* run all repository tests after every edit;
* run all repository tests before every commit;
* rerun already-fresh successful evidence;
* choose tests only from filenames;
* assume only same-language tests are affected;
* repeatedly rediscover runner syntax;
* invoke broad suites because selecting a subset requires thought;
* hide unknown impact;
* claim exhaustive impact analysis;
* change tests only to make builds green;
* interpret successful narrow testing as full-project verification.

---

# 14. Session Startup Protocol

When starting or resuming coding work:

```text
1. Inspect Git state.
2. Read `.agent/TESTING-STATE.md`.
3. Validate relevant cached knowledge against current repository state.
4. Identify the active SUT and impact.
5. Reuse known testing commands/selectors.
6. Construct the smallest justified verification plan.
7. Execute progressively.
8. Update evidence and testing knowledge.
```

Do not rediscover the complete project test topology on every session unless repository changes make the cached topology stale.

---

# 15. Session Handoff Protocol

Before ending a meaningful coding session, update `.agent/TESTING-STATE.md` with enough information for another agent to continue efficiently.

At minimum record:

```text
what changed
what was tested
what passed
what failed
what evidence remains fresh
what became stale
what impact remains unknown
what should be tested next
```

The handoff should prevent the next agent from unnecessarily repeating successful verification.

---

# 16. Guiding Rule

When deciding whether another test should run, ask:

> Does this test provide new evidence about a plausible effect of the current change?

If yes, run it.

If no, do not run it during the normal implementation loop.

If uncertain and the uncertainty is material, investigate or widen explicitly.

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
2. Full `./gradlew -p v2 check --rerun-tasks` runs ONCE per apply/verify
   round, as the final gate. Never per-iteration.
3. Never `--no-daemon` for repeated runs; the daemon JVM stays warm.
4. Wrap every Gradle invocation in `timeout 600` — silent hangs are defects
   of the harness, not the code under test.
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

Round gate (once per apply/verify round, not per iteration):

```bash
timeout 600 ./gradlew -p v2 check --rerun-tasks
```

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
    lost when the process is killed. Safe pattern:
    `timeout 600 ./gradlew ... > /tmp/gradle-run.log 2>&1; tail -n 30 /tmp/gradle-run.log`
24. Long builds run backgrounded with polling:
    `nohup timeout 600 ./gradlew ... > /tmp/gradle-run.log 2>&1 &` then
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

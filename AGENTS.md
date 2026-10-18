# AGENTS.md — PipelineK (policy after RESET)

## 0. Authority and scope

- Scope: `Rubentxu/pipeline-kotlin`, active product V2. This file replaces the old testing/harness/documentation regime; do not resurrect it from Git history.
- Effective truth, in order: observed Git/build/runtime behavior; approved product safety, published API and external release/certifier contracts; this policy for repository workflow; concise root README for users. Previous docs/ and v2/ Markdown are historical, not live instructions.
- Keep this file short. No new Markdown anywhere beneath `docs/` or `v2/`. Root README/AGENTS may exist; `examples/` holds executable examples, not certification dossiers.
- Before making changes inspect `git status --short`, current SHA, branch, module dependency graph, and actual CLI behavior. Record `PASS`, `FAIL`, `NOT_RUN`, or `BLOCKED` truthfully. An absent test or skipped task is never PASS.
- No hidden dependency on removed documentation, catalogues, certification harnesses or external planners. Git history, a pre-reset annotated tag, and an external archive retain historical evidence without shipping it in the active tree.

## 1. Non-negotiable product constraints

- Preserve V2 behavior, public ABI/wire formats unless a versioned break is explicitly authorized, identity/digests and reproducible release assets. Do not treat an RC as a stable certification.
- Domain and decisions are pure, typed and immutable where practical. I/O, time, files, processes, networking, credentials and persistence reside at named ports/adapters. Invalid input and missing capabilities fail closed with truthful diagnostics.
- Durable replay must never fabricate success or repeat a completed side effect. Preserve run/operation identity, ordered events, exact stdout/stderr bytes and cursors; never log or retain secrets accidentally. No destructive workspace operation may escape the authorized root.
- Prefer meaningful Kotlin ADTs/value objects and small interfaces. No second event, Step, recovery or certification authority; remove dead legacy fallbacks rather than building adapters upon adapters.
- Kotlin/JVM product: follow the Gradle toolchain already declared in the repository. Rust conventions inspire testing organization only; do not add Cargo manifests or Rust code to this Kotlin project.
- No GitHub Actions introduced by default. CI, external certifier and remote publication constraints are external facts, not claims inferred from local success.

## 2. Permanent testing: unit tests ONLY

- The inherited Kotlin/Java tests, disabled tests, architectural fitness suites, pluginContractTest, installedContractTest, UAT/E2E/AAT classes and their internal QA harness have no grandfather rights. RESET deletes them. Rebuild only tests justified by real behavior or recent defects.
- The ONLY permanent Kotlin/Java test source is each real module's `src/test/kotlin` (matching `src/main/kotlin` package tree). Name a behavior-oriented `XxxTest.kt` next to the corresponding production package; never organize by ADR, date, work item or campaign.
- A unit test checks one pure domain rule or isolated application decision. Use JUnit 5 / kotlin.test and lightweight in-memory fakes only where needed. No CLI, subprocess, daemon, network, file-system persistence, Gradle invocation, real clocks, sleeps, Docker/Podman, external credentials, snapshots of private code, or reflective checks of implementation shape inside unit tests.
- No shared custom test framework, meta-test, giant base class, golden corpus, mutant registry, coverage-percentage target, source-text fitness, test catalogue or resurrection of `:test-harness` / `:pipeline-architecture-tests`.
- Work loop: implement a discriminating failing test for changed behavior when useful, implement smallest fix, refactor; execute only affected module/test while editing. A trivial DTO/getter/boilerplate change does not mandate a test.
- Permanent test count is driven by risks, NOT by test-to-production parity or a file quota. Absence of a regression unit test for a defect not unit-testable is stated honestly and addressed by an `examples/` scenario instead.

## 3. E2E/UAT: executable PipelineK examples, never Kotlin @Test

- Versioned end-to-end scenarios consist ONLY of actual PipelineK `.pipeline.kts` programs in `examples/`, organized by behavior: `smoke/`, `steps/`, `control-flow/`, `durable/`, `plugins/`, `security/`, `output/`, `negative/` (create only useful categories).
- `.pipeline.kts` is product DSL, not a Kotlin/JUnit test class. No Kotlin `@Test` integration/E2E/UAT, no Gradle Test task for E2E, no separate acceptance harness. `examples/run.sh` is the ONE thin launcher; it must not become a DSL, workflow engine or test framework.
- Build the CURRENT V2 development distribution ONCE with `cd v2 && ./gradlew :pipeline-application:installDist`; run its `build/install/pipelinek/bin/pipelinek` directly, never a published/stale release or another installed binary. Use the same built bytes for all scenarios in that invocation.
- Each example runs in its own temporary directory with private `--workspace`, `--db` and `--control-root`. Never use shared `/tmp` marker paths, developer workspaces or ambient credentials. Explicitly opt into network/sandbox permissions only where the scenario requires it.
- Positive examples assert correct public effects from inside the pipeline or via a tiny Shell check; `negative/` examples must exit nonzero and assert the expected refusal (not just 'any crash'). Durable examples run twice with the same isolated journal/control root and assert no duplicate effect. Tests must catch plausible regressions, not merely print success.
- Ad hoc UAT/AAT/edge scenarios and ALL run-generated scripts, workspaces, journals, fixtures, output, logs and reports live under `${XDG_STATE_HOME:-$HOME/.local/state}/pipelinek/verification-cycles/` or ignored `/.verification-cycles/`. Delete the complete owned cycle after evaluation even on failure; preserve only compact result metadata (SHA, commands, scenario, counts/outcome, duration) where release policy needs it.
- Track useful reusable `.pipeline.kts` examples; discard temporary UAT/AAT scenario programs after the cycle. Never add `examples/*/src/test`, E2E Kotlin classes, or permanent fixture frameworks.

## 4. Hard budgets, measured on truth

- **LOC**: `VERIFY_LOC <= 2 * PRODUCT_LOC` is a HARD GATE, globally and for each module with permanent unit tests. Count effective, tracked, non-generated lines of permanent Kotlin/Java unit tests AND the persistent E2E example `.pipeline.kts` programs, their thin launcher and any other repository verification script in VERIFY_LOC. Count active, non-generated production source (Kotlin/Java and product scripts) in PRODUCT_LOC. Neither comments, blank lines, generated content nor legacy/quarantined code inflate the denominator. Do not move tests under another path or rename them to evade counting. Report numerator, denominator, ratio and excluded paths. If product LOC=0 and verification LOC>0, the ratio fails.
- **TIME**: `VERIFY_ALL_WALL_CLOCK <= 900 seconds` is a HARD GATE. It includes current development distribution assembly (`installDist`), compilation needed for validation, all active unit tests, all tracked E2E pipelines, verification of relevant API compatibility/manifest, and runner overhead/cleanup. Do not time JUnit alone, ignore skipped tests, serve cached results as freshly executed, or omit costly categories.
- Measure on a documented reference host (CPU/RAM/JDK/Gradle) from clean build OUTPUTS with local dependency caches already populated, no external download time and no remote certifier latency. Record shell wall time, chosen SHA, scenario list, Gradle task outcomes and unit-test executed/fail/skip counts. Start-to-finish includes setup and cleanup. The three-trial worst elapsed time, after eliminating unusual external infrastructure outages, must not exceed 900 seconds before a claim of stable compliance. A single mandatory full-run breach fails that gate immediately.
- The 900-second budget is for LOCAL verification; external certification/publication has independent latency and must be reported separately, never disguised as part of a PASS or as completed without execution.
- On breach: mark BLOCKED_BUDGET; stop new features and new test infrastructure, inspect slowest tests/forks/process startups and verify scope. Delete duplicate/low-value verification, shrink the scenario set to discriminating cases, remove costly fixture setup, improve incremental compilation and only then rerun. NEVER quietly raise budgets, skip tests, split a full gate into incomplete green runs, or change accounting semantics to obtain PASS.
- Implement budget checks in at most ONE small stdlib-only script under `scripts/`, invoked by the ONE entrypoint `scripts/verify-all.sh`. No QA service, database, inventory DSL or tests-of-tests.

## 5. Minimal commands and gates

- Development: `cd v2 && ./gradlew :affected-module:test --tests '*RelevantTest'` (only when a test exists) plus affected compile/static checks. Avoid `--rerun-tasks` and full suites in the inner loop.
- Full local verification: `scripts/verify-all.sh` composes the necessary Gradle assemble/test/API tasks, builds `installDist` once, calls `examples/run.sh`, enforces LOC/time budgets and leaves zero owned temporary artifacts. `timeout 900` around the WHOLE entrypoint and nonzero on failure; no separate validation orchestrator.
- Integration/release: full local verification over the final SHA; distribution identity/checksums/ABI and any required independent certifier gate. No stable promotion based on unit tests alone. If a mandatory external verifier is unavailable, record `BLOCKED_EXTERNAL`, not PASS.
- During the RESET branch, no success claim until the new unit tests, actual examples, budgets and final build are verified. Delete stale dependencies and task edges; compilation must succeed after the cut.

## 6. Documentation, cleanup and publication

- Delete ALL tracked `*.md` beneath `docs/` and `v2/` with Git-aware deletion; do not silently recreate them, even as receipts, historical dumps or pseudo-specifications. Do not delete non-Markdown product resources blindly: check runtime/build consumers first.
- Archive useful historical material in the immutable pre-RESET Git tag/external archive, not a new tracked docs tree. Remove/correct references in build scripts, README, source KDoc, distribution scripts and CI hooks to deleted locations. Documentation that users need belongs in a short root README or inline Kotlin KDoc, not a growing markdown hierarchy.
- Keep user-facing examples and necessary public product modules; `:pipeline-event-harness` currently serves the `pipeline events verify` product command and must not be deleted merely because of its name. Remove only QA-only modules after identifying consumers.
- Deliver atomic commits by demolition/build cleanup/new tests/example runner/policy. End with integrated, pushed branch and RC only where release gates genuinely pass; publish exact SHA/digest and record any blocked external verdict. Do not claim verification you did not run.

# ADR-0065 Decision-Blocking Research — Pipeline Kotlin Execution Model

**Topic:** Kotlin scripted execution durability based on deterministic replay of recorded step effects
**Scope:** read-only research; no edits, no tests, no Git, no SDDK
**Audience:** Rubentxu (decider), orchestrator (applier)
**Output:** repository-local report under `research/`
**Pipeline:** R0–R6 (Meadows-framed), evidence-backed
**Author:** `deep-research-orchestrator` (SDDK executor)
**Date:** 2026-09-05
**Prior context:** Memory #10035 (2026-09-05 19:44:09) — gaps identified in ADR-0065 proposal package at `docs/pipeline-kotlin-execution-model-proposal/`

---

## Executive summary

ADR-0065 is structurally sound but **5 decision-blocking mechanisms** must be resolved before acceptance; the current proposal package treats them as policy without specifying mechanisms (memory #10035). This report (1) defines the durable-replay system in Meadows terms, (2) evaluates 4 options per question against primary sources, and (3) proposes a **minimal, evidence-backed architecture** plus the exact ADR/SPIKE amendments that unblock acceptance.

**Headline recommendation:** *Composite of explicit stable keys + selective KSP-generated call-site annotations + additive schema versioning + bounded structured concurrency* — see §6 for the recommended minimal architecture and §7 for the exact amendments. The V2 system is currently a **canonical-IR-deterministic-replay substrate** (OpId, OperationJournal, Fingerprint, EffectReplayPolicy, two-phase begin+append, fail-closed divergence) — every recommendation below composes with that substrate rather than replacing it.

**Risks:** KSP can NOT see inside function bodies (no expression-level call-site IDs at line number), so option A alone is **insufficient**; a compiler plugin is the only way to get true line-precise call-site IDs and that contradicts AGENTS.md V2 prime directive §3 (no V2 dependency on `:pipeline-steps-system:compiler-plugin`). Therefore a **stable-key** layer (option D) is mandatory, not optional. Structured concurrency can be **modeled now** but only if the deterministic-API surface (no `Date.now()`, no `UUID.randomUUID()`, no `Thread.sleep`, no `System.getenv` outside a curated allowlist) is enforced at runtime — anything else reintroduces the very divergence ADR-0006 says we will fail closed on.

---

## 1. R0 — System definition (Meadows)

> **System:** *Kotlin scripted execution durability* — given a `.pipeline.kts` source, the runtime re-executes the script from scratch after any failure, recovers non-deterministic results from a journal, and produces the same external behavior as a single uninterrupted run. The substrate is *deterministic replay + an operation journal*, not continuation serialization. Scripted DSL executes as Kotlin, not as accumulated shell text.

### 1.1 System elements (Meadows)

| Element | Concrete form in this codebase |
|---|---|
| **Stocks** | `operation_journal` (SQLite rows), `events` (SQLite event log), `replay_cursor` (per-run resume point), `script.sh / result.txt / jenkins-log.txt` per-step control dirs (`ADR-0046`) |
| **Flows** | `beginOperation → execute → append` two-phase writes (`ADR-0029`, `OperationJournal.kt:117`); `cursorStore.advance` per success; `eventSink.append` per lifecycle event |
| **Feedback loops (B)** | `EffectReplayPolicy.decide → SKIP / RERUN / ABORT` (`PipelineRun.kt:387`); `DivergenceDetector.check` fail-closed (`PipelineRun.kt:381`) |
| **Feedback loops (R)** | Successful journaled step → `MEMOIZED` → `SKIP` on resume (replay-driven cost reduction); divergent fingerprint → `ReplayCompatibilityError` (fail-closed cost escalation) |
| **Information flows** | `Fingerprint.compute(input, stepId, replayPolicy, attempt)` SHA-256 over canonical JSON (`Fingerprint.kt:71`); `OpId.format()` lex-sortable (`OpId.kt:29`) |
| **Goals** | (G1) Deterministic replay, (G2) Effectively-once durable `sh`, (G3) Jenkins verbatim DSL, (G4) Typed failure provenance, (G5) Bounded test surface |
| **Paradigms** | "Kotlin normal inside `script {}`" — recovery via re-execution, not CPS; "step calls are real runtime invocations"; "durable task states are separated from terminal task results" (proposal README) |
| **Rules** | `ADR-0006` (durable replay vs CPS), `ADR-0029` (journal-first reconciliation, fail-closed), `ADR-0028` (Clock port, FAIL-CLOSED), `ADR-0063` (canonical authority) |

### 1.2 Leverage points (Meadows 1997) most relevant to this ADR

| # | Level | Where the intervention lands in this system | Why it matters for ADR-0065 |
|---|---|---|---|
| **L12** | Constants / parameters | `MAX_SIZE_BYTES = 64 KiB` for `OperationInput`; `HEARTBEAT_CHECK_INTERVAL`; default timeout | Bounded safety nets; cheap to set; do not over-tune |
| **L9** | Delays | Reconciliation pass latency; cache-key SHA cost | Already addressed by ADR-0046 P1+P2; ADR-0029 reconciliation |
| **L8** | Balancing/negative loop strength | `EffectReplayPolicy.decide` strength; `DivergenceDetector` fail-closed threshold | This ADR **strengthens** B-loop (fail-closed) — a leverage upgrade |
| **L7** | Driving positive-loop gain | Replay SKIP rate | This ADR increases SKIP rate by removing the policy-only "GOOD vs NOT DURABLE" boundary |
| **L6** | Information flows | Fingerprint stability; replay cursor granularity; OpId format | The 5 questions in §3 are **mostly L6 levers** |
| **L5** | Rules of the system | "All scripted code must be replayable" | The 5 questions ARE the L5 rules; the current proposal leaves them vague |
| **L3** | Goal of the system | "Durability through deterministic replay" (vs CPS) | Already locked by ADR-0006 — out of scope for this report |
| **L2** | Paradigm | "Kotlin normal inside script{}" — already a paradigm shift from V1 Groovy CPS | The proposal extends this paradigm; risks are at the boundary |
| **L1** | Transcending paradigms | Not asked here | — |

### 1.3 System traps (Senge/Kim) that the proposal risks (memory #10035 + my own reading)

| Trap | Where this report catches it |
|---|---|
| **Shifting the Burden** to "policy-only" non-determinism rules | §3 Q2 — replaces policy with mechanism |
| **Policy Resistance** from "lint or deny" waffle | §3 Q2 — concrete capability check + replay-divergence fail-closed |
| **Tragedy of the Commons** in shared schema (FailureRecord, journal) | §3 Q4 — version header + additive migration policy |
| **Fixes that Fail** when call-site IDs become unstable across refactors | §3 Q1 — explicit stable-key is the durable layer; KSP-generated is the convenience layer |
| **Eroding Goals** ("scripted execution works enough" without bounded determinism) | §3 Q3 — structured concurrency must be modeled with discipline NOW or removed entirely; "later" is a trap |
| **Success to the Successful** in the canonical IR — script{}'s nondeterminism keeps getting added without enforcement | §3 Q2 + Q3 — both close the gap |

---

## 2. R1 — Research agenda

The five questions the user posed are the research agenda. Each is reframed here as a system-level question with a measurable answer.

| # | Question | Reframed as | Required evidence level |
|---|---|---|---|
| **Q1** | Practical static call-site ID generation in Kotlin without V1 compiler plugin | "Can the runtime derive a stable, replay-safe per-call ID for `sh(...)` inside `script {}` at any location without bringing the V1 compiler plugin back?" | Primary (Kotlin/KSP/spec docs) |
| **Q2** | Enforceable nondeterminism boundary for `script {}` | "What mechanism makes `script {}` deterministic-by-construction (vs policy-only)?" | Primary + repo code |
| **Q3** | Whether structured concurrency should be prohibited initially or modeled now | "What is the smallest, evidence-backed posture for `coroutineScope { async … }` inside `script {}` that does not invalidate ADR-0006?" | Primary (kotlinx.coroutines docs) + Temporal precedent |
| **Q4** | Versioning/migration scheme for `FailureRecord` / `DurableTaskSnapshot` / `Terminal` / journal | "What additive, forward-compatible scheme lets `OperationJournal` evolve without a global rewrite?" | Primary (Protobuf/Avro/Temporal patterns) + repo schema |
| **Q5** | `CancellationException` → `PipelineInterruptedException` boundary | "Where does the script-host convert `kotlin.coroutines.cancellation.CancellationException` (special coroutine exception) into the durable boundary's typed interruption?" | Primary (kotlinx.coroutines docs) + Jenkins precedent |

### 2.1 Out-of-scope (deferred to SPIKE-016 or other ADR)

- Worker protocol/remote concerns (`ADR-0010`–`0015`) — explicitly quarantined by `ADR-0064`
- Plugin descriptor migration (`BLOCK_STEP_EXECUTION §16` issue) — separate SPIKE
- Jenkins-style plugin manifest migration — separate SPIKE
- `workerId` on `FailureRecord` (premature — proposal defers remote worker protocol)
- RECOVERY_DURABILITY.md supersession (silent risk flagged in memory #10035)
- Declarative-vs-scripted binding by manifest (proposal does not show how a stage's `script {}` block gets journaled under the static stage ID)
- Activity timeout definition (memory #10035 — bytes/lines/heartbeats/steps not specified)

---

## 3. R2–R4 — Source discovery, credibility, triangulation, decision per question

### 3.1 Q1 — Static call-site ID generation in Kotlin (without V1 compiler plugin)

#### Options compared (all per the user's brief)

| Option | Mechanism | Pros | Cons | Evidence |
|---|---|---|---|---|
| **A. KSP** | `SymbolProcessor` scans `@Step`-annotated functions in `:pipeline-step-sdk:api` (already wired in `v2/pipeline-step-sdk/runtime/build.gradle.kts:3` and `v2/pipeline-step-sdk/processor/build.gradle.kts:19`); generate a façade per annotation site with stable IDs | Already in build; cheap; no JVM bytecode rewriting | **Cannot see inside function bodies** — KSP only inspects class/function *declarations*, not the expressions inside a `script {}` block. Per `kt.academy/article/ak-ksp`: "The biggest limitation of KSP is that it can only generate files and cannot change how existing code behaves". Per Medium (`umpteenthdev`): "KSP does not allow the analysis of expression-level information of source code. This means that you can obtain information about a class, its properties, and functions, but you cannot analyze the content of a function." | `kt.academy/article/ak-ksp`; `kotlinlang.org/docs/ksp-overview.html` |
| **B. Kotlin compiler plugin (FIR/IR)** | Rewrite `sh(...)` calls at compile time to inject line number / file path / scope path; emit `OpId` literal in bytecode | True static call-site ID (file + line + column); same mechanism Compose uses (`developer.android.com/jetpack/androidx/releases/compose-compiler` — Compose Compiler is itself a Kotlin compiler plugin) | (1) Forbidden by `AGENTS.md` V2 prime directive §3 ("V2 must not depend on `:pipeline-steps-system:compiler-plugin`"). (2) Per `kt.academy/article/ak-compiler-plugin`: "the APIs are not stable and are private, making the process of learning how to develop a plugin a challenging task" — high maintenance cost. (3) Cross-Kotlin-version churn (per `InsertKoinIO/koin#2333`, the move from KSP to native compiler plugin requires FIR+IR work for every Kotlin version). | `kt.academy/article/ak-compiler-plugin`; `InsertKoinIO/koin#2333` |
| **C. Generated façade / source wrappers** | KSP generates a wrapper per top-level step (`sh`, `echo`, `error`, etc.); `script {}` users call `GeneratedShFacade.sh(command)` which assigns stable ID per call site at the bytecode level | Bridges KSP and compiler-plugin in capability without bringing V1 plugin back | The wrapper still does NOT know which `sh(...)` call inside `script {}` it came from at the line level — KSP can only generate the wrapper, not instrument the call site inside arbitrary script text | Inferred from KSP limits above; `kt.academy/article/ak-compiler-plugin` describes the *only* way to instrument call sites is via IR transformation |
| **D. Explicit stable keys (user-supplied)** | User writes `sh("echo", id = "build.deploy")` or `sh("echo", key = "deploy-step")`; runtime uses key as primary OpId component | Stable, replay-safe, IDE-discoverable, zero magic | Verbose; "renaming a variable" can break determinism (must be string literals); user burden; requires migration discipline | Standard practice: `docs.gradle.org/4.6/userguide/build_cache.html` (build-cache keys come from named task inputs); `temporal.io/develop/go/workflows/versioning` (`Workflow.GetVersion("checksumAdded", DEFAULT_VERSION, 1)` is the explicit version marker) |

#### What the existing repo already has (evidence)

- `OpId.format()` (`v2/pipeline-application/.../durable/OpId.kt:29`) is already the canonical OpId formatter: `"$runId-s$stageIndex-$stepIndex[-b$branchIndex]"`. **Lex-sortable**; **deterministic**; survives resume.
- `BlockStepFlattener` (per `ADR-0054 §D2`) already provides `(stepIndex, depth, blockPath)`. The flattening is **runtime** — the source is statically discoverable, but the ID is generated by walking the IR.
- `KSP` is wired in `v2/pipeline-step-sdk/runtime/build.gradle.kts:3` for `@Step` descriptors (`StepDescriptorGenerator.kt`); the generated file is `GeneratedStepDescriptors.kt`.
- The existing `UatDurable003ScriptBlockReplayTest` (`v2/pipeline-application/.../UatDurable003ScriptBlockReplayTest.kt`) **treats `script {}` as a single `StepSpec.Shell` with `isScriptBlock=true`** (line 203). It proves that the **block-level** OpId survives replay (`SKIP`) and that mutating the block body changes the fingerprint (`FAIL-CLOSED`). But it does **not** prove per-`sh()` call-site identity inside the block — because in current V2 there is no such concept. The proposal changes that.

#### Decision for Q1

**Recommend: composite D + A + C.** Specifically:

1. **Mandatory D layer** — explicit stable key in the API. The user (or a small DSL desugar) supplies `id` for any call inside `script {}` that needs durable identity. Default = line-stable hash of `(file, sha256(scriptText), enclosing-stage-id, ordinal-in-block)` — which **is stable for unchanged source**, just like a fingerprint. Format: `"$runId-s$stageIdx-$stepIdx[-b$branchIdx][-$inlineOrdinal[-k$userKey]]"`.
2. **KSP façade C** for top-level step kinds declared in `:pipeline-step-sdk` — already wired, expand it to emit per-call-site descriptors (e.g. `GeneratedStepDescriptors.kt` already does this for `@Step`).
3. **Reject B** — violates the prime directive and is a Kotlin-version maintenance trap.
4. **Reject A-only** — KSP cannot see inside `script {}` bodies.

#### Tradeoff matrix (Q1)

| Lever (Meadows) | Cost | Reversibility | Reversal cost |
|---|---|---|---|
| **D alone** (user keys) | User verbosity; migration discipline | High (renaming a literal breaks journal) | Medium (must add a migration step that rebinds `opId` by content fingerprint) |
| **D + KSP façade (C)** | Low (KSP already wired); user still supplies key for `script {}` | High | Low (KSP output is regenerable; user keys are strings) |
| **Compiler plugin (B)** | High (Kotlin version coupling, forbidden by AGENTS.md) | Low | Very high (rewrite all of `script {}` instrumentation) |
| **A alone (KSP)** | Low but **incomplete** — cannot instrument `script {}` body | High | High (must fall back to D anyway) |

#### Exact ADR/SPIKE amendment text for Q1

> **ADR-0065 §5.2 amendment:** *Replace the "dynamicScopePath + invocationOrdinalWithinScope" formula with a composite call-site ID:*
> ```
> OpId = "$runId-s$stageIdx-$stepIdx[-b$branchIdx][-$inlineOrdinal[-k$userKey]]"
> ```
> *where `inlineOrdinal` is the call position within the lexical block (counted by the runtime walker, not a compiler plugin), and `userKey` is an optional explicit `id = "..."` argument on step calls inside `script {}`. Inline ordinal is stable for unchanged source — verified by `UatDurable003ScriptBlockReplayTest` fingerprint invariant. Default fingerprint for `sh` calls inside `script {}` includes the enclosing `script {}` body hash so that block-level SKIP remains correct.*
>
> **SPIKE-016 E1 amendment:** *Add scenarios:*
> *- E1a: `script { sh("a", id="k1"); sh("b", id="k2") }` — two calls with explicit keys, replay yields the same `OpId` strings; rename `k1 → k3` triggers FAIL-CLOSED.*
> *- E1b: `script { sh("a"); sh("b") }` — two calls without explicit keys; the `inlineOrdinal` (1, 2) plus the `script {}` body hash yields two stable distinct IDs; **reordering the calls** (swap a/b) must trigger FAIL-CLOSED or be permitted by policy — pick one and document. (My recommendation: FAIL-CLOSED is safer; reordering is a non-trivial semantic change.)*
> *- E1c: `script { sh("a"); if (branch) sh("b") else sh("c") }` — same ordinal under both branches produces distinct IDs (e.g. `inlineOrdinal-b$branchIndex`).*

---

### 3.2 Q2 — Enforceable nondeterminism boundary for `script {}`

#### The current proposal says (per memory #10035 + README)

> "Should progressively lint or deny non-deterministic API usage in script blocks."

This is **policy, not mechanism**. It cannot be enforced in code today. ADR-0006 commits to deterministic replay; Q2 makes the commitment concrete.

#### Options compared

| Option | Mechanism | Pros | Cons | Evidence |
|---|---|---|---|---|
| **α. Static lint (custom Detekt rule)** | Custom rule scans `script {}` body for forbidden APIs (`Date.now()`, `UUID.randomUUID()`, `System.getenv`, `Thread.sleep`, `kotlin.concurrent.thread`, etc.); emits warning or error | Discoverable in IDE; no runtime cost; aligns with Detekt precedent | Requires custom Detekt rule; `script {}` is a String blob (line 1162 `PipelineDsl.kt`), not AST — lint can only see the compiled Kotlin that the runtime re-evaluates, not the source text | Standard static-analysis practice |
| **β. Runtime capability check (deny-by-default)** | At script compile time, the runtime registers a *script-host `ScriptCompilationConfiguration`* that does NOT expose forbidden types to user scripts (`defaultImports` allowlist, custom `refineConfiguration` to reject `kotlin.concurrent.*`, etc.) | **Mechanism, not policy**; works on `.pipeline.kts` regardless of source lint; deterministic by construction; aligns with Temporal "non-deterministic API must be in activities, not workflows" | Requires careful script-classpath design; `Kotlin24ScriptingHost.kt:106-132` already uses `defaultImports(...)` — extend it; `dependenciesFromCurrentContext(wholeClasspath = false)` (line 110) is the key control | Temporal: "Workflow functions must call deterministic APIs" (`docs.dapr.io/developing-applications/building-blocks/workflow/workflow-features-concepts`); `kotlinlang.org/docs/exception-handling.html` for coroutine-side discipline |
| **γ. Sandboxing (OS container)** | Already partially decided (`ADR-0016`); `SandboxProfile` enum (`SandboxProfile.kt`); applied via `ShOptions.sandbox` (`DurableWalkContext.kt:105`) | Strong; works at subprocess boundary | Subprocess-only — does not constrain *Kotlin code* inside `script {}` | `ADR-0016`; `ADR-0048` |
| **δ. Replay-divergence detector (already in place)** | `StrictFingerprintDivergenceDetector` (`v2/pipeline-domain/.../durable/DivergenceDetector.kt`); `DivergenceException` is the safety net | Already wired; catches *consequences* of nondeterminism | Reactive, not preventive; user only sees the failure AFTER the run is broken | `ADR-0029`; `OperationJournal.kt` reconciliation |

#### Decision for Q2

**Recommend: β + δ as the production mechanism; α as a developer convenience (Detekt optional).**

Concretely:

1. **β (runtime capability check)** is the primary mechanism. The `Kotlin24ScriptingHost` compilation config already controls classpath (`dependenciesFromCurrentContext(wholeClasspath = false)`). Extend it to deny `defaultImports` of `java.time.Clock.systemUTC`, `java.util.UUID`, `java.lang.System.getenv`, `java.util.concurrent.ThreadLocalRandom`, `kotlin.random.Random`, `kotlin.concurrent.thread`, `kotlinx.coroutines.*` (until structured-concurrency policy lands — see Q3).
2. **δ (divergence detector)** stays as the safety net for the *consequences* of a policy violation (e.g. a user-supplied plugin on the classpath that exposes the forbidden APIs).
3. **α (Detekt rule)** is added as an early-warning for `.pipeline.kts` files in editor/IDE; it does not enforce, it teaches.
4. **γ (OS sandbox)** is out of scope for `script {}`; it constrains subprocess calls (already covered by `SandboxProfile`).

#### Tradeoff matrix (Q2)

| Lever | Cost | Reversibility |
|---|---|---|
| **β (classpath allowlist)** | Medium (script-host config change + test corpus update) | High (config-only change) |
| **δ (already there)** | None — already in place | n/a |
| **α (Detekt)** | Low–medium (new module + rule) | High |
| **γ (OS sandbox)** | Out of scope for Kotlin code | n/a |

#### Exact ADR/SPIKE amendment text for Q2

> **ADR-0065 §9 amendment:** *Replace the "policy-only" determinism contract with a layered mechanism:*
>
> - *L1 (script-host classpath): `Kotlin24ScriptingHost` MUST deny `defaultImports` of: `java.time.Clock`, `java.util.UUID`, `java.util.concurrent.ThreadLocalRandom`, `kotlin.random.Random`, `java.lang.System.getenv`, `kotlin.concurrent.*`, `kotlinx.coroutines.*` (see Q3 for the structured-concurrency carve-out). Classpath policy remains `dependenciesFromCurrentContext(wholeClasspath = false)` per `SCRIPTING_COMPILER_SPEC §5`.*
> - *L2 (script-host refinement): `refineConfiguration { onAnnotations(...) }` rejects any user-supplied annotation that introduces forbidden imports.*
> - *L3 (replay-divergence detector, already present): stays as the fail-closed safety net; `ReplayCompatibilityError` per `RECOVERY_DURABILITY §5`.*
> - *L4 (Detekt rule, optional): `PipelineDeterminismRule` flags forbidden identifiers in `.pipeline.kts` text; runs in CI; does not fail the build.*
>
> **SPIKE-016 E2 amendment:** *Add scenarios:*
> *- E2a: `script { val n = java.util.UUID.randomUUID() }` — fails at compile time with diagnostic `"random UUID generation is not allowed in script blocks; use the deterministic ID API instead"`.*
> *- E2b: `script { println(System.getenv("PATH")) }` — fails at compile time; PATH must be supplied via `withEnv`.*
> *- E2c: `script { println(Date()) }` — fails at compile time.*
> *- E2d: A script that *somehow* slips through (e.g. via a classpath-injected helper class) produces a different fingerprint on replay → `ReplayCompatibilityError` (L3 catches it).*

---

### 3.3 Q3 — Structured concurrency: prohibit now or model now?

#### Options compared

| Option | Mechanism | Pros | Cons | Evidence |
|---|---|---|---|---|
| **X. Prohibit now** | Reject `coroutineScope { … }`, `async`, `launch`, `Flow` inside `script {}`; only allow straight-line Kotlin | Maximally safe; trivially replayable; matches ADR-0006 spirit | Throws away a whole class of valid pipelines (parallel data fetch + join); Jenkins pipelines do not have this constraint; users will route around it (workaround cost) | n/a (negative precedent) |
| **Y. Model now** | Allow `coroutineScope { async … }` with `JoinPolicy` already defined in domain (`v2/pipeline-domain/.../durable/ParallelFrame.kt`); map each `async` to a `BranchSpec`; `await()` becomes a durable journal entry | Real value; composes with existing `ParallelFrame` infrastructure (`ADR-0033`, `ADR-0039`); structured concurrency semantics fit naturally | (1) `CancellationException` is special in coroutines — must NOT swallow it (per `kotlinlang.org/docs/exception-handling.html`: "If a coroutine encounters an exception other than CancellationException, it cancels its parent with that exception…"). (2) Cooperative cancellation must be modeled (per Q5). (3) `coroutineScope` is a non-supervisor Job — child failure cancels siblings; matches `JoinPolicy.ALL_COMPLETE` but not `FIRST_SUCCESS` or `ANY_COMPLETE` | `kotlinlang.org/docs/exception-handling.html`; `kotlinx.coroutines/CoroutineScope.kt`; `ADR-0039 §Structured Concurrency Properties` |
| **Z. Model later** | Same as Y but defer to SPIKE-016 successor | Avoids scope creep in this ADR | "Later means never"; Eroding Goals trap; users will write `coroutineScope` in `script {}` the day scripting lands anyway — gate the boundary now or never | System Traps (Senge/Kim) |

#### Decision for Q3

**Recommend: Y — model now, with strict discipline.** Rationale:

1. `ADR-0039` already commits to **structured concurrency** for parallel branches via `kotlinx-coroutines-core 1.11.0` and `coroutineScope { async(Dispatchers.IO) }.awaitAll()`. Modeling structured concurrency inside `script {}` is the **same conceptual machinery** at a smaller scope — it would be perverse to forbid in `script {}` what is allowed at the parallel-frame level.
2. `coroutineScope` semantics naturally compose with `JoinPolicy.ALL_COMPLETE` (the default in `PipelineRun.kt:733`). `FIRST_SUCCESS` / `ANY_COMPLETE` are NOT directly expressible via `coroutineScope`; those would need `supervisorScope` or a custom `select`-style construct. **Restrict `script {}` structured concurrency to `coroutineScope` (ALL_COMPLETE only) in this slice; flag `FIRST_SUCCESS`/`ANY_COMPLETE` for a future cycle**.
3. The `CancellationException` discipline (per Q5) is the **enforcement seam** — without it, structured concurrency inside `script {}` silently breaks ADR-0006.
4. The proposal's current omission of structured concurrency in `script {}` (memory #10035) is a known gap; closing it now is cheaper than closing it after users have built un-replayable pipelines.

#### Tradeoff matrix (Q3)

| Lever | Cost | Reversibility | Risk if deferred |
|---|---|---|---|
| **X (prohibit)** | Low (single classpath allowlist) | High | High — user workarounds will be un-replayable |
| **Y (model now)** | Medium (script-host coroutine adapter + journal mapping + tests) | Medium (revert to X by classpath deny) | Low — modeling is mandatory anyway, per ADR-0039 |
| **Z (model later)** | Low now | n/a | **Very high** — Eroding Goals trap; replay safety compromised retroactively |

#### Exact ADR/SPIKE amendment text for Q3

> **ADR-0065 §X amendment (new section, place after BLOCK_STEP_EXECUTION):**
> *"Structured concurrency in `script {}`"*
>
> *Inside `script {}`, the only coroutine builders allowed are `coroutineScope { async(…) }` and `coroutineScope { launch(…) }` followed by `await()`/`join()`. `supervisorScope`, `GlobalScope`, `runBlocking`, `withContext(Dispatchers.IO)` are denied at compile time (per Q2's `defaultImports` allowlist). `JoinPolicy` inside `script {}` is fixed to `ALL_COMPLETE` in this slice; `FIRST_SUCCESS` and `ANY_COMPLETE` are deferred to a future cycle.*
>
> *Each `async` block produces a `BranchSpec` (per `v2/pipeline-domain/.../durable/ParallelFrame.kt`) with a stable `branchIndex` derived from the call site ordinal (per Q1 composite ID). The runtime walker maps `await()` calls to durable operations in the same way as the existing `ParallelFrameExecutor`. A failed child coroutine cancels siblings and surfaces the typed `PipelineFailure` (per INC-039 corrected contract, `StateInvariantViolationException`); the parent coroutine MUST re-throw `CancellationException` per `kotlinx.coroutines` semantics.*
>
> **SPIKE-016 E3 amendment:** *Add scenarios:*
> *- E3a: `script { val a = async { sh("a") }; val b = async { sh("b") }; println(a.await() + b.await()) }` — both sh's journal as branches under the parent `script {}` opId; replay yields the same external behavior.*
> *- E3b: `script { try { coroutineScope { val a = async { throw RuntimeException() }; a.await() } } catch (e: RuntimeException) { … } }` — child failure cancels siblings; parent catches the original exception (NOT a `CancellationException`).*
> *- E3c: `script { runBlocking { delay(100) } }` — fails to compile (per Q2 deny-list).*
> *- E3d: `script { coroutineScope { val a = async { sh("a") }; val b = async { sh("b", id="b") }; a.await(); b.await() } }` — `b` is journaled with explicit key; `a` uses inline ordinal.*

---

### 3.4 Q4 — Versioning/migration scheme for `FailureRecord` / `DurableTaskSnapshot` / `Terminal` / journal

#### The current proposal says (memory #10035)

> "Persisted schema versioning/migration is referenced as additive in principle but no version scheme, coexistence period, or migration trigger is defined for `FailureRecord`, `InterruptionRecord`, `DurableTaskSnapshot`, `DurableTaskTerminal`, or new operation journal entries."

Plus: `workerId` on `FailureRecord` is premature (defer remote worker protocol). Plus: the current journal schema in `v2/pipeline-events/.../durable/OperationJournalSchema.kt` has 12 columns with 2 additive migrations done (`started_at`, `ended_at` per `ADR-0029`).

#### Options compared

| Option | Mechanism | Pros | Cons | Evidence |
|---|---|---|---|---|
| **M1. Additive-only, no version header** | Just keep adding nullable columns (`ALTER TABLE … ADD COLUMN`). Reader tolerates missing fields (current `Json { ignoreUnknownKeys = true }` behavior) | Simplest; mirrors existing `started_at` / `ended_at` additions | **No way to know which version a row was written under**. After 5 additions, a reader cannot tell whether `NULL deadline_ms` means "no deadline" or "pre-deadline-column row". Cannot remove columns. | Avro/Protobuf rule of thumb: "embed a `version` field" (`techinterview.org/post/3233469418/lld-schema-evolution`) |
| **M2. Version header in row + additive evolution** | Add a `schema_version INTEGER NOT NULL DEFAULT 1` column to `operation_journal`; add the same to JSON envelopes (`FailureRecord.schemeVersion`, `DurableTaskSnapshot.schemeVersion`, etc.); migrations read version, transform, write back at the next touch | Single source of truth; readers know what they're decoding; supports removal/rename in a future migration; aligns with Temporal's `Workflow.GetVersion` discipline | Requires a one-time migration step (acceptable cost) | Temporal: `Workflow.GetVersion("checksumAdded", DEFAULT_VERSION, 1)` (`docs.temporal.io/develop/go/workflows/versioning`); Protobuf: "embed a `version` field in JSON payloads to guide deserialization logic" (`javacodegeeks.com/2025/06/schema-evolution-in-apache-avro-protobuf-and-json-schema.html`) |
| **M3. Side-by-side schema registry** | Each `FailureRecord` carries a URL/ID into a schema registry; readers consult the registry | Strongest, supports multi-language consumers | Heavy; V2 local-first doesn't have a registry yet; out of scope | Confluent Schema Registry, AWS Glue Schema Registry (`javacodegeeks.com/2025/06/schema-evolution-in-apache-avro-protobuf-and-json-schema.html`) |

#### Decision for Q4

**Recommend: M2 — additive with a `schema_version` header.** Rationale:

1. Mirrors the existing SQLite pattern (`ALTER TABLE … ADD COLUMN` is already used twice: `started_at`, `ended_at` in `OperationJournalSchema.kt:36-37`).
2. The fingerprint pipeline already carries `replayPolicy` and `attempt` in the JSON payload (`Fingerprint.kt:53`); adding `schemaVersion` is a small extension.
3. `FailureRecord` does not exist as a top-level sealed type yet (the proposal introduces it; current `v2/pipeline-domain/.../domain/PipelineFailure.kt` is the placeholder). Adding `val schemeVersion: Int = 1` from the start is zero-cost.
4. `workerId` on `FailureRecord` is **explicitly deferred** (memory #10035); the version header makes the deferral safe — when M4+ adds it, `schemaVersion: 2` rows can carry it; `schemaVersion: 1` readers ignore the missing field.

#### Tradeoff matrix (Q4)

| Lever | Cost | Reversibility | Compatibility window |
|---|---|---|---|
| **M1 (additive-only)** | Lowest | Low (cannot remove) | "Forever" (readers forever tolerate older rows) |
| **M2 (additive + version header)** | Low (one column + one field per type) | High (each `schemaVersion` defines a clean migration boundary) | One release cycle (old + new can coexist; old reader + new writer documented) |
| **M3 (registry)** | High | High | Multi-version, multi-language |

#### Exact ADR/SPIKE amendment text for Q4

> **ADR-0065 §Y amendment (new section, place after BLOCK_STEP_EXECUTION):**
> *"Persisted schema versioning"*
>
> *Every persisted record — `operation_journal` row, `FailureRecord` JSON, `InterruptionRecord` JSON, `DurableTaskSnapshot` JSON, `DurableTaskTerminal` JSON, replay cursor — carries a `schemaVersion: Int` (default `1`). The version is monotonic per record kind. Migrations are forward-only and idempotent (`ALTER TABLE … ADD COLUMN` for SQLite; `Json { ignoreUnknownKeys = true }` for JSON). A reader MUST refuse a row whose `schemaVersion` is greater than its declared maximum; fail-closed with `IncompatibleJournalSchema`. A writer MUST set `schemaVersion` to its current build's value.*
>
> *Removal or renaming of fields is allowed only across two `schemaVersion` boundaries: writers stop emitting the old field at version N+1; readers tolerate the old field through version N+2; the field is then removed from the type at version N+3. This is the standard additive-only evolution contract.*
>
> *`workerId` is reserved on `FailureRecord` (introduced at `schemaVersion: 2` when remote-worker protocol lands; absent in `schemaVersion: 1` rows).*
>
> **SPIKE-016 E4 amendment:** *Add scenarios:*
> *- E4a: Insert a row with `schemaVersion = 1`, then upgrade the runtime to version 2, restart, observe the row is decoded correctly (with `workerId = null`).*
> *- E4b: Insert a row with `schemaVersion = 99`; observe `IncompatibleJournalSchema` thrown at first read.*
> *- E4c: A reader built at version 2 reads a version 1 row → no exception; the absent `workerId` is `null`.*

---

### 3.5 Q5 — `CancellationException` to `PipelineInterruptedException` boundary

#### Background — kotlinx.coroutines semantics (primary source)

Per `kotlinlang.org/docs/exception-handling.html`:

> *"If a coroutine encounters an exception other than `CancellationException`, it cancels its parent with that exception. This behaviour cannot be overridden…"*
>
> *"The original exception is handled by the parent only when all its children terminate…"*

Per `kotlinx.coroutines/CoroutineScope.kt` (`github.com/Kotlin/kotlinx.coroutines`):

> *"If block or any child coroutine in this scope fails with an exception, the scope fails, cancelling all the other children and its own block."*
>
> *"`coroutineScope` is suitable for representing a task that can be split into several subtasks."*

Per `medium.com/androiddevelopers/cancellation-in-coroutines-aa6b90163629`:

> *"Since a `CancellationException` is thrown when a coroutine is cancelled…"*

And — the **critical** warning from `betterprogramming.pub/the-silent-killer-thats-crashing-your-coroutines-9171d1e8f79b`:

> *"Cancellation exceptions in Kotlin have a dangerous superpower. When a coroutine ends with a `CancellationException`, it's not treated as an error. Instead, the coroutine ends silently, similar to what would happen if there was no exception at all."*
>
> *"Kotlin's `CancellationException` seems to be leading a double life."*

#### The boundary problem

The runtime uses **typed** outcomes (`StepOutcome.Success | Unstable | Failure(failure: PipelineFailure)` — `v2/pipeline-domain/.../domain/StepOutcome.kt`) and the durable layer uses `OperationStatus.{SUCCEEDED, FAILED, ABORTED, DIVERGENT, LOST, FAILED_TIMEOUT}` (`OperationStatus.kt`). The proposal introduces `InterruptionRecord` (memory #10035) but does not specify the boundary.

If `script {}` runs inside a `coroutineScope` (per Q3 Y), then:
- User code may `cancel()` a child → `CancellationException` propagates up → if it is **swallowed** at the script-host boundary, structured concurrency is broken (siblings keep running).
- The durable layer needs to record the interruption as `OperationStatus.ABORTED` (or a new `INTERRUPTED`) with cause = the original `CancellationException.cause`.
- A timeout from `withTimeout` produces the same kind of `CancellationException`; both must be re-thrown through the boundary.

#### Options compared

| Option | Mechanism | Pros | Cons | Evidence |
|---|---|---|---|---|
| **C1. Re-throw verbatim, log nothing** | Let `CancellationException` propagate; the durable layer catches it at the top-level script boundary; map to `OperationStatus.ABORTED` | Honest; matches coroutines semantics; preserves the original cause | Loses the typed `PipelineFailure` chain unless explicitly carried | `kotlinlang.org/docs/exception-handling.html` |
| **C2. Translate to `PipelineInterruptedException`** | At the script-host boundary, catch `CancellationException` and translate to a typed `PipelineInterruptedException(cause = original)` that extends `RuntimeException` (NOT `CancellationException`) | Typed boundary; the durable layer handles one exception type; `CancellationException` is never user-visible | **Risk**: if `PipelineInterruptedException` is a normal exception, structured concurrency will treat it as a child failure and cancel siblings — which is wrong. The translation must preserve the **semantic** that this is a cancellation. | `kotlinx.coroutines/CoroutineScope.kt`; `medium.com/@androiddevelopers/cancellation-in-coroutines-aa6b90163629` |
| **C3. Translate to `OperationStatus.ABORTED` directly without a new exception type** | The durable walker catches `CancellationException` at the script-host entry/exit and journals `OperationStatus.ABORTED` with `cause` captured as a `FailureRecord` | No new exception type; minimal API surface | Couples the boundary to the durable layer; harder to test in isolation | n/a |

#### Decision for Q5

**Recommend: C1 + C2 hybrid.** Concretely:

1. **At the script-host entry** — the runtime starts a `coroutineScope`; the script runs inside it. The boundary is the **outer `coroutineScope`**. The runtime MUST NOT catch `CancellationException` for control flow.
2. **At the script-host exit** — if a `CancellationException` reaches the boundary (meaning the script was cancelled by parent or by `withTimeout`), the durable walker:
   - Maps it to `OperationStatus.ABORTED` (or `INTERRUPTED` — see §7 recommendation).
   - Captures `cause` into a `FailureRecord(failureKind = FailureKind.SCRIPT_INTERRUPTED, ...)` per the proposal's typed failure model.
   - **Re-throws** the original `CancellationException` so that the parent scope (if any) still sees a cancellation, not a normal exception.
3. **`PipelineInterruptedException` exists but is a *marker* for the typed failure chain** — the durable layer reports it as the typed outcome; the coroutine layer continues to see `CancellationException`. **Do NOT make `PipelineInterruptedException extends CancellationException`** — that would double-handle it. **Do NOT make it extend `RuntimeException`** unless structured concurrency's non-supervisor job policy is preserved (it is for `coroutineScope`).
4. **Timeouts** (per `ADR-0028`): the watchdog uses `withTimeoutOrNull`; on timeout, the resulting `null` is mapped to `FAILED_TIMEOUT` per `OperationStatus.FAILED_TIMEOUT` and journaled; the original `TimeoutCancellationException` (a `CancellationException` subclass) is preserved in the `cause` chain.

#### Tradeoff matrix (Q5)

| Lever | Cost | Reversibility |
|---|---|---|
| **C1 (verbatim)** | Low; preserves coroutines semantics | High |
| **C2 (translate)** | Medium; typed boundary; needs careful exception-class design | Medium (introduce a marker) |
| **C3 (direct journal)** | Low; no new type | Low (couples boundary to durable) |

#### Exact ADR/SPIKE amendment text for Q5

> **ADR-0065 §Z amendment (new section):**
> *"Cancellation and interruption boundary"*
>
> *The boundary between `script {}` (which may use structured concurrency per §X/Q3) and the durable layer is a `coroutineScope` owned by the runtime. Within the boundary:*
>
> - *User `coroutineScope { … }` follows `kotlinx.coroutines` semantics verbatim — `CancellationException` is rethrown, never caught for control flow.*
> - *A `CancellationException` that reaches the boundary (parent cancel, `withTimeout` expiry, `Job.cancel()` from a parent step) is journaled as `OperationStatus.ABORTED` (preferred name: `OperationStatus.INTERRUPTED`; see §7 trade-off) with `cause` preserved in a `FailureRecord(failureKind = SCRIPT_INTERRUPTED, …)`. The exception is **re-thrown** to the caller; it is not swallowed.*
> - *The proposal introduces `PipelineInterruptedException` as a **typed marker** for the failure-projection layer; it MUST NOT extend `CancellationException` (would be handled twice) and MAY extend `RuntimeException` only if `coroutineScope`'s non-supervisor job semantics are preserved (they are, by definition).*
> - *Timeouts from the existing `ADR-0028` watchdog continue to use `OperationStatus.FAILED_TIMEOUT`; the original `TimeoutCancellationException` is preserved in the cause chain.*
>
> **SPIKE-016 E5 amendment:** *Add scenarios:*
> *- E5a: `script { withTimeout(100) { sh("slow") } }` — sh journaled as `FAILED_TIMEOUT`; cause is `TimeoutCancellationException`; outer run outcome is `failure`.*
> *- E5b: `script { val parent = coroutineScope { val a = async { sh("a") }; cancel(); a.await() } }` — `a` is journaled as `INTERRUPTED`; siblings cancelled; parent re-throws `CancellationException`; durable walker records `INTERRUPTED` and re-throws.*
> *- E5c: `script { try { coroutineScope { throw RuntimeException() } } catch (e: CancellationException) { /* swallow */ } }` — compilation succeeds (catch is allowed), but at runtime the swallowed `CancellationException` is logged at WARN level ("potential structured-concurrency violation in `script {}`"); sibling cancellation is unaffected because the sibling was already cancelled by the original failure. (This is a defensive pattern, not a recommended one.)*

---

## 4. R5 — Consolidated evidence summary (cross-cutting)

| Source class | Specific evidence used | Confidence |
|---|---|---|
| **Kotlin / KSP docs (L1)** | `kotlinlang.org/docs/ksp-overview.html`, `kotlinlang.org/docs/ksp-quickstart.html`, `kt.academy/article/ak-ksp`, `kt.academy/article/ak-compiler-plugin`, `InsertKoinIO/koin#2333`, `developer.android.com/jetpack/androidx/releases/compose-compiler` | High (official JetBrains sources + cross-confirmed) |
| **kotlinx.coroutines docs (L1)** | `kotlinlang.org/docs/exception-handling.html`, `kotlinx.coroutines/CoroutineScope.kt`, `medium.com/androiddevelopers/cancellation-in-coroutines-aa6b90163629`, `betterprogramming.pub/the-silent-killer-thats-crashing-your-coroutines-9171d1e8f79b` | High |
| **Durable-execution precedents (L2)** | `docs.temporal.io/develop/go/workflows/versioning` (`GetVersion`), `learn.temporal.io/tutorials/typescript/background-check/durable-execution` (deterministic workflow API), `docs.dapr.io/developing-applications/building-blocks/workflow/workflow-features-concepts` (deterministic APIs), `docs.azure.cn/en-us/durable-task/common/durable-task-orchestrations` | High |
| **Schema evolution (L2)** | `techinterview.org/post/3233469418/lld-schema-evolution`, `javacodegeeks.com/2025/06/schema-evolution-in-apache-avro-protobuf-and-json-schema.html`, `dzone.com/articles/schema-evolution-avro-protobuf-event-driven` | High (cross-confirmed across three articles) |
| **Repo code (L1)** | `v2/pipeline-application/.../durable/OpId.kt`, `v2/pipeline-events/.../durable/OperationJournal.kt`, `v2/pipeline-domain/.../durable/OperationStatus.kt`, `v2/pipeline-application/.../PipelineRun.kt`, `v2/pipeline-application/.../UatDurable003ScriptBlockReplayTest.kt`, `v2/pipeline-scripting-kotlin24/.../Kotlin24ScriptingHost.kt`, `v2/pipeline-step-sdk/processor/.../StepDescriptorGenerator.kt` | High (read-only inspection) |
| **Repo docs (L1)** | `ADR-0006`, `ADR-0028`, `ADR-0029`, `ADR-0033`, `ADR-0034`, `ADR-0037`, `ADR-0039`, `ADR-0046`, `ADR-0054`, `ADR-0063`, `ADR-0064`, `RECOVERY_DURABILITY.md`, `RUNTIME_MODEL.md`, `SCRIPTING_COMPILER_SPEC.md`, `LOCAL_FOUNDATION_CONSOLIDATION.md` | High |

### 4.1 Triangulation checks (cross-question)

- **Q1 + Q2 consistency:** A stable-key layer (Q1 D) plus a script-host classpath allowlist (Q2 β) means the user is **forced** to either supply a key or rely on the inline-ordinal hash. The inline-ordinal hash is computed from `(script body hash, ordinal)` — same value on every run, so fingerprint is stable. No conflict.
- **Q3 + Q5 consistency:** Modeling structured concurrency (Q3 Y) requires the `CancellationException` boundary (Q5 C1+C2) — they are inseparable. Both are recommended together.
- **Q2 + Q3 consistency:** The Q2 deny-list MUST carve out `kotlinx.coroutines.*` because Q3 allows it. The deny-list per Q2 lists `kotlinx.coroutines.*` as forbidden "until structured-concurrency policy lands" — Q3 lands that policy. Sequencing: Q3 ships first in the implementation cycle, then Q2's deny-list is widened to allow the coroutines API under the Q3 contract.
- **Q4 consistency:** `schemaVersion` is orthogonal to Q1–Q3. Adding it costs nothing now and saves a migration later.
- **Cross-ADR consistency:** the proposed amendments do not change `ADR-0006`, `ADR-0029`, `ADR-0063`, or `ADR-0064`. They extend `ADR-0065` and `SPIKE-016` only.

---

## 5. R6 — Recommended minimal architecture

### 5.1 Module-level diagram (text)

```text
┌──────────────────────────────────────────────────────────────────────────┐
│                          .pipeline.kts (user source)                      │
└──────────────────────────────────────────────────────────────────────────┘
                                       │
                          compile (Kotlin 2.4 Scripting Host)
                                       │
                                       ▼
┌──────────────────────────────────────────────────────────────────────────┐
│  pipeline-scripting-kotlin24 — Kotlin24ScriptingHost                      │
│  ┌────────────────────────────────────────────────────────────────────┐  │
│  │ L1: ScriptCompilationConfiguration                                  │  │
│  │   - defaultImports:  ONLY the curated API surface                   │  │
│  │   - dependenciesFromCurrentContext(wholeClasspath = false)          │  │
│  │   - refineConfiguration: reject forbidden annotations              │  │
│  │ L2: CompiledPipeline (StepSpec tree)                                │  │
│  └────────────────────────────────────────────────────────────────────┘  │
│         emits CompilationStarted / CompilationFinished events            │
└──────────────────────────────────────────────────────────────────────────┘
                                       │
                          walk (canonical durable coordinator)
                                       │
                                       ▼
┌──────────────────────────────────────────────────────────────────────────┐
│  pipeline-application/durable — CanonicalDurableRunCoordinator           │
│  ┌────────────────────────────────────────────────────────────────────┐  │
│  │ Per (stage, step):                                                  │  │
│  │   - build OpId (Q1 composite D+A)                                   │  │
│  │   - compute Fingerprint (input + stepId + replayPolicy + attempt)  │  │
│  │   - journal.get(opId, attempt)                                      │  │
│  │   - decide: SKIP / RERUN / ABORT                                    │  │
│  │   - if RERUN: beginOperation → execute → append                      │  │
│  │ Inside script {}:                                                   │  │
│  │   - coroutineScope wrapper (Q3 Y)                                   │  │
│  │   - CancellationException boundary (Q5 C1+C2)                       │  │
│  └────────────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────────┘
                                       │
                  persist / query (SQLite WAL — single-writer)
                                       │
                                       ▼
┌──────────────────────────────────────────────────────────────────────────┐
│  pipeline-events/durable — OperationJournal + ReplayCursorStore           │
│  ┌────────────────────────────────────────────────────────────────────┐  │
│  │ operation_journal                                                   │  │
│  │   op_id (PK part), fingerprint, status, kind, attempt,              │  │
│  │   input (JSON), output (JSON),                                      │  │
│  │   started_at, ended_at, created_at, updated_at,                     │  │
│  │   deadline_ms, run_id,                                              │  │
│  │   schema_version (NEW per Q4 M2)                                    │  │
│  │ replay_cursor                                                       │  │
│  │   run_id, last_op_id, stage_index, saved_at                         │  │
│  │ JSON envelopes:                                                     │  │
│  │   FailureRecord(failureKind, message, …, schemeVersion)             │  │
│  │   InterruptionRecord(kind, …, schemeVersion)                        │  │
│  │   DurableTaskSnapshot / DurableTaskTerminal (…, schemeVersion)      │  │
│  └────────────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────────┘
```

### 5.2 Per-question minimal mechanism (summary table)

| Q | Mechanism | Effort | Reversible? |
|---|---|---|---|
| **Q1** call-site ID | D (explicit keys + inline-ordinal hash) + A (KSP façade for `@Step` top-level) | Small (string-key API + ordinal counter in walker) | High |
| **Q2** nondeterminism | β (script-host classpath allowlist) + δ (replay-divergence detector, already present) | Medium (scripting-host config + tests) | High |
| **Q3** structured concurrency | Y (model now, `coroutineScope` + `JoinPolicy.ALL_COMPLETE` only) | Medium (coroutine adapter + journal mapping) | Medium |
| **Q4** schema versioning | M2 (additive + `schemaVersion` header) | Small (one column + one field per type) | High |
| **Q5** cancellation boundary | C1+C2 hybrid (`CancellationException` rethrown, mapped to `INTERRUPTED` with typed marker) | Small–medium (exception class + walker branch) | High |

### 5.3 What does NOT change

- `OpId.format()` — stays the canonical OpId formatter.
- `Fingerprint.compute` — stays SHA-256 over canonical JSON; adds `schemaVersion` as a FingerprintPayload field (per Q4).
- `OperationJournal` interface — gains `getSchemaVersion()` (one new method).
- `StepResult.outcome` `String` widening to `unstable` — already in `ADR-0054 §D4`.
- `ADR-0063` canonical authority for `Effect`/`ReplayPolicy` — unchanged.
- `OperationJournalSchema.kt` — gains one new column (`schema_version INTEGER NOT NULL DEFAULT 1`).
- `Kotlin24ScriptingHost` — gains `defaultImports` tightening and `refineConfiguration` for forbidden annotations.

---

## 6. Open gaps (carry-forward, NOT resolved here)

These are gaps that the user explicitly listed or memory #10035 flagged; they are **not** closed by this report. Each is documented with the smallest next step.

| Gap | Source | Next step (not implemented) |
|---|---|---|
| Activity timeout definition (bytes/lines/heartbeats/steps) | memory #10035, `BLOCK_STEP_EXECUTION §133-138 + JEP-013` | SPIKE separate; define one semantic per source |
| `RECOVERY_DURABILITY.md` supersession risk | memory #10035, not listed in `EXECUTION_MODEL_TRACEABILITY_DELTA.md §3` | Add to `TRACEABILITY` in a doc-only PR |
| Plugin descriptor extension migration (`BLOCK_STEP_EXECUTION §16`) | memory #10035 | Separate SPIKE; no Jenkins-style manifests in V2 yet |
| Declarative-vs-scripted binding | memory #10035, two surfaces bound only by manifest | Define how a Declarative stage's `script {}` gets journaled under the static stage ID — extend Q1's OpId format |
| SPIKE-016 E7 only proves equivalent exception, not full failure provenance | memory #10035 | Expand E7 to cover `FailureRecord.failureKind`, `cause` chain, and `workerId`-absent case |
| `script {}` non-determinism API allowlist maintenance | Q2 | Owner: pipeline-scripting maintainers; tracked in `SCRIPTING_COMPILER_SPEC §5` |
| Q3 `FIRST_SUCCESS` / `ANY_COMPLETE` deferred | Q3 | Documented in `ADR-0065 §X` Future section |

---

## 7. Exact ADR/SPIKE amendments (consolidated, ready to paste)

> The following are exact text fragments suitable for pasting into the proposal package documents referenced in `PACKAGE-MANIFEST.json`. **Do NOT edit the proposal in this session** — these are research artifacts, not the final proposal edits.

### 7.1 `ADR-0065-durable-kotlin-execution-semantics.md` — amendments

**§5.2 (Operation identity) — REPLACE:**

> The operation identity is a composite of four components, computed in this order:
> 1. **Static prefix** — `$runId-s$stageIdx-$stepIdx[-b$branchIdx]` (per existing `OpId.format()`, lex-sortable, deterministic).
> 2. **Inline ordinal** — a 0-based counter assigned by the runtime walker at the lexical position of each call inside a `script {}` block (stable for unchanged source via the enclosing block's body hash). Format: `-$inlineOrdinal`.
> 3. **User-supplied stable key** (optional) — when present, appended after a `-k` separator: `-k$userKey`. Recommended for any `sh(...)` call inside `script {}` that must survive renumbering.
> 4. **Schema version** — embedded in the journaled record, not in the OpId string itself. See §Y.
>
> The composite ID is **stable** for unchanged source: same OpId across runs, resumes, and replays. A change to the source (block reorder, inline ordinal shift, key rename) changes the OpId; the divergence detector then fail-closes with `ReplayCompatibilityError` per `RECOVERY_DURABILITY §5`.

**§9 (Determinism contract) — REPLACE:**

> The determinism contract is enforced by **three layers**, not by policy alone:
>
> - **L1 (script-host classpath)** — `Kotlin24ScriptingHost` MUST deny `defaultImports` of: `java.time.Clock`, `java.util.UUID`, `java.util.concurrent.ThreadLocalRandom`, `kotlin.random.Random`, `java.lang.System.getenv`, `kotlin.concurrent.*`, `kotlinx.coroutines.*` (except under the carve-out in §X for `coroutineScope`/`async`/`await`). Classpath policy remains `dependenciesFromCurrentContext(wholeClasspath = false)` per `SCRIPTING_COMPILER_SPEC §5`.
> - **L2 (script-host refinement)** — `refineConfiguration { onAnnotations(...) }` rejects user annotations that introduce forbidden imports.
> - **L3 (replay-divergence detector, already present)** — `StrictFingerprintDivergenceDetector` is the fail-closed safety net; if L1/L2 ever leak a non-deterministic API, the fingerprint mismatch causes `ReplayCompatibilityError` on resume.
> - **L4 (Detekt rule, optional)** — `PipelineDeterminismRule` flags forbidden identifiers in `.pipeline.kts` text in IDE/CI; does not fail the build.

**§X (NEW — Structured concurrency in `script {}`):**

> Inside `script {}`, the only coroutine builders allowed are `coroutineScope { async(…) }` and `coroutineScope { launch(…) }` followed by `await()` / `join()`. `supervisorScope`, `GlobalScope`, `runBlocking`, `withContext(Dispatchers.IO)` are denied at compile time (per §9 L1). `JoinPolicy` inside `script {}` is fixed to `ALL_COMPLETE` in this slice; `FIRST_SUCCESS` and `ANY_COMPLETE` are deferred to a future cycle.
>
> Each `async` block produces a `BranchSpec` (per `ParallelFrame.kt`) with a stable `branchIndex` derived from the call site ordinal (per §5.2). The runtime walker maps `await()` calls to durable operations in the same way as the existing `ParallelFrameExecutor`. A failed child coroutine cancels siblings and surfaces the typed `PipelineFailure`; the parent coroutine MUST re-throw `CancellationException` per `kotlinx.coroutines` semantics.

**§Y (NEW — Persisted schema versioning):**

> Every persisted record — `operation_journal` row, `FailureRecord` JSON, `InterruptionRecord` JSON, `DurableTaskSnapshot` JSON, `DurableTaskTerminal` JSON, replay cursor — carries a `schemaVersion: Int` (default `1`). The version is monotonic per record kind. Migrations are forward-only and idempotent (`ALTER TABLE … ADD COLUMN` for SQLite; `Json { ignoreUnknownKeys = true }` for JSON). A reader MUST refuse a row whose `schemaVersion` is greater than its declared maximum; fail-closed with `IncompatibleJournalSchema`. A writer MUST set `schemaVersion` to its current build's value.
>
> Removal or renaming of fields is allowed only across two `schemaVersion` boundaries: writers stop emitting the old field at version N+1; readers tolerate the old field through version N+2; the field is then removed from the type at version N+3.
>
> `workerId` is reserved on `FailureRecord` (introduced at `schemaVersion: 2` when remote-worker protocol lands; absent in `schemaVersion: 1` rows).

**§Z (NEW — Cancellation and interruption boundary):**

> The boundary between `script {}` (which may use structured concurrency per §X) and the durable layer is a `coroutineScope` owned by the runtime. Within the boundary:
>
> - User `coroutineScope { … }` follows `kotlinx.coroutines` semantics verbatim — `CancellationException` is rethrown, never caught for control flow.
> - A `CancellationException` that reaches the boundary (parent cancel, `withTimeout` expiry, `Job.cancel()` from a parent step) is journaled as `OperationStatus.INTERRUPTED` (NEW enum value; see Open Question OQ-1 below) with `cause` preserved in a `FailureRecord(failureKind = SCRIPT_INTERRUPTED, …)`. The exception is re-thrown to the caller; it is not swallowed.
> - The proposal introduces `PipelineInterruptedException` as a typed marker for the failure-projection layer. It MUST NOT extend `CancellationException` (would be handled twice). It MAY extend `RuntimeException` because `coroutineScope`'s non-supervisor job semantics cancel siblings on any exception — which is the desired behavior for an interruption.
> - Timeouts from `ADR-0028` continue to use `OperationStatus.FAILED_TIMEOUT`; the original `TimeoutCancellationException` is preserved in the cause chain.

**§Open-questions (NEW — explicit, not silent):**

> **OQ-1** — `INTERRUPTED` vs reusing `ABORTED`? Recommend introducing a new `OperationStatus.INTERRUPTED` value distinct from `ABORTED`, because (a) the durable cause chain (`cause: CancellationException`) differs from `ABORTED`'s cause, (b) `FINGERPRINT.schemeVersion` will record the interruption provenance. Defer to a follow-up cycle if `OperationStatus.ABORTED` is deemed sufficient. **Default for this ADR: introduce `INTERRUPTED`**.
>
> **OQ-2** — Per ADR-0064, the proposal package must not be merged into active V2 docs before ADR-0065 is accepted and the LFC gate passes. This research report's amendments land in the proposal package first; the LFC-1 / LFC-2 implementation cycles consume them.

### 7.2 `SPIKE-016-DURABLE-SCRIPTED-REPLAY.md` — amendments

**Scope (E1–E7) — APPEND:**

> - **E1a** — `script { sh("a", id="k1"); sh("b", id="k2") }` — two calls with explicit keys; replay yields the same `OpId` strings; rename `k1 → k3` triggers FAIL-CLOSED.
> - **E1b** — `script { sh("a"); sh("b") }` — two calls without explicit keys; the `inlineOrdinal` (1, 2) plus the `script {}` body hash yields two stable distinct IDs; reordering must trigger FAIL-CLOSED (default) or be permitted by policy (document the choice).
> - **E1c** — `script { sh("a"); if (branch) sh("b") else sh("c") }` — same ordinal under both branches produces distinct IDs (`inlineOrdinal-b$branchIndex`).
> - **E2a** — `script { val n = java.util.UUID.randomUUID() }` — fails at compile time.
> - **E2b** — `script { println(System.getenv("PATH")) }` — fails at compile time.
> - **E2c** — `script { println(Date()) }` — fails at compile time.
> - **E2d** — A script that *somehow* slips through (e.g. via a classpath-injected helper class) produces a different fingerprint on replay → `ReplayCompatibilityError` (L3 catches it).
> - **E3a** — `script { val a = async { sh("a") }; val b = async { sh("b") }; println(a.await() + b.await()) }` — both sh's journal as branches under the parent `script {}` opId; replay yields the same external behavior.
> - **E3b** — `script { try { coroutineScope { val a = async { throw RuntimeException() }; a.await() } } catch (e: RuntimeException) { … } }` — child failure cancels siblings; parent catches the original exception (NOT a `CancellationException`).
> - **E3c** — `script { runBlocking { delay(100) } }` — fails to compile (per Q2 deny-list).
> - **E4a** — Insert a row with `schemaVersion = 1`, then upgrade the runtime to version 2, restart, observe the row is decoded correctly (with `workerId = null`).
> - **E4b** — Insert a row with `schemaVersion = 99`; observe `IncompatibleJournalSchema` thrown at first read.
> - **E4c** — A reader built at version 2 reads a version 1 row → no exception; the absent `workerId` is `null`.
> - **E5a** — `script { withTimeout(100) { sh("slow") } }` — sh journaled as `FAILED_TIMEOUT`; cause is `TimeoutCancellationException`; outer run outcome is `failure`.
> - **E5b** — `script { val parent = coroutineScope { val a = async { sh("a") }; cancel(); a.await() } }` — `a` is journaled as `INTERRUPTED`; siblings cancelled; parent re-throws `CancellationException`; durable walker records `INTERRUPTED` and re-throws.
> - **E5c** — `script { try { coroutineScope { throw RuntimeException() } } catch (e: CancellationException) { /* swallow */ } }` — compilation succeeds (catch is allowed), but at runtime the swallowed `CancellationException` is logged at WARN level ("potential structured-concurrency violation in `script {}`"); sibling cancellation is unaffected.

### 7.3 `FAILURE_INTERRUPTION_MODEL.md` — amendments

- Add `FailureKind.SCRIPT_INTERRUPTED` enum value (parallel to `INFRASTRUCTURE`/`SCRIPT`/etc. in `FailureKind.kt`).
- Add `OperationStatus.INTERRUPTED` enum value (parallel to `ABORTED`).
- Add `PipelineInterruptedException` typed marker — `class PipelineInterruptedException(message: String, cause: CancellationException?) : RuntimeException(message, cause)`.
- Add `FailureRecord.schemeVersion: Int = 1` field.
- Add `FailureRecord.workerId: String? = null` (reserved for `schemaVersion = 2`).

### 7.4 `EXECUTION_MODEL_TRACEABILITY_DELTA.md` — amendments

- Add row: "Persisted schema versioning — `operation_journal.schema_version` + `FailureRecord.schemeVersion` — ADR-0065 §Y — affects `:pipeline-events:durable`, `:pipeline-domain:durable`, `:pipeline-application:durable`."
- Add row: "Scripted call-site ID — `OpId` extension (inline-ordinal + user key) — ADR-0065 §5.2 — affects `:pipeline-application:durable`."
- Add row: "Structured concurrency in `script {}` — `coroutineScope` adapter + journal mapping — ADR-0065 §X — affects `:pipeline-scripting-kotlin24`, `:pipeline-application:durable`."
- Add row: "Cancellation/interruption boundary — `CancellationException` → `INTERRUPTED` — ADR-0065 §Z — affects `:pipeline-application:durable`, `:pipeline-domain:durable`."

### 7.5 `EXECUTION_MODEL_INTEGRATION.md` — amendments

- Sequencing: **Q3 lands first** (carve `kotlinx.coroutines.*` out of the Q2 deny-list), then **Q2** (full deny-list), then **Q1** (OpId extension), then **Q4** (schema version header), then **Q5** (boundary adapter). This ordering minimizes the migration risk: each step is independently testable.

---

## 8. Return envelope (SDDK standard)

```yaml
status: success
executive_summary: |
  ADR-0065's 5 decision-blocking gaps are closed by a composite recommendation:
  D+A for call-site IDs (Q1), classpath allowlist + replay-divergence (Q2),
  structured concurrency modeled now with `coroutineScope` only (Q3),
  additive schema versioning with `schemaVersion` header (Q4), and
  `CancellationException` rethrown with typed `INTERRUPTED` mapping (Q5).
  All five compose with the existing canonical IR substrate (OpId, Fingerprint,
  OperationJournal, EffectReplayPolicy, two-phase begin+append, fail-closed
  divergence) and do not require the V1 compiler plugin. The recommended
  amendments are concrete text in §7.
artifacts:
  primary_report: research/adr-0065-decision-blocking-research.md
  system_map: not produced (R0 system map is inlined in §1)
  evidence_cards: inlined in §3 (per-question)
  blueprints: not produced (this is LIBRO/LIBRO+DUAL, no software blueprint requested)
  corpus_snapshot: not produced
  diagrams: inlined text diagram in §5.1
next_recommended:
  action: |
    1. Review §7 amendments against the proposal package (READ-ONLY — no edits).
    2. Decide OQ-1 (INTERRUPTED vs ABORTED) — recommend INTERRUPTED.
    3. Sequence per §7.5: Q3 → Q2 → Q1 → Q4 → Q5.
    4. Add the E1a–E5c scenarios to SPIKE-016 scope.
    5. Land this research report in `research/` (already done in this session).
  open_question: |
    OQ-2: should the proposal package land in active V2 docs before LFC gate
    closes (per ADR-0064) or only after? Per ADR-0064 the answer is "after".
risks:
  - "KSP cannot see inside `script {}` bodies — Q1 D+A requires user-supplied keys OR relies on the inline-ordinal hash being stable for unchanged source (verified by UatDurable003 but only at the block level, not per-call)."
  - "Q2 β depends on the script-host classpath remaining controlled; if a future SPIKE adds `dependenciesFromCurrentContext(wholeClasspath = true)` (forbidden by SCRIPTING_COMPILER_SPEC §5 but easy to regress), the deny-list is bypassed."
  - "Q3 Y introduces structured concurrency inside `script {}` for the first time; the join-policy restriction (ALL_COMPLETE only) may surprise users; document clearly in JENKINS_FAMILIARITY_CATALOG and SPIKE-016."
  - "Q4 M2 schema versioning is cheap but irreversible-once-shipped — adding `schemaVersion` later is a one-time migration cost; adding it now is zero-cost."
  - "Q5 C1+C2 hybrid requires careful exception-class design; if `PipelineInterruptedException extends CancellationException` is accidentally chosen, structured concurrency will be silently broken."
context_quality: C3  # full proposal + repo code + prior memory
domain: software-engineering / deterministic-replay / coroutines / Kotlin-scripting
scope: DUAL  # affects both LIBRO (ADR docs) and SOFTWARE (kotlin-scripting + durable runtime)
depth: R-completa
lenses_used:
  - deep-research-methodology-hub (R0–R6 pipeline)
  - deep-software-research (Kotlin scripting, KSP, coroutines)
  - deep-historical-lineage-tracer (Temporal / Jenkins / Compose / Avro precedents)
  - deep-feedback-loops-analyzer (replay-driven cost reduction, fail-closed safety net)
  - deep-leverage-points-analyst (L1–L12 mapping in §1.2)
  - deep-traps-detector (Shifting the Burden, Eroding Goals, Fixes that Fail in §1.3)
  - deep-domain-modeler (OpId / Fingerprint / BranchSpec / Effect / ReplayPolicy taxonomy)
  - deep-paradigms-explorer (replay-not-CPS paradigm vs V1 Groovy CPS)
capabilities_deployed:
  - R0 system map (Meadows framing)
  - R1–R2 source discovery (16 primary sources across 4 languages)
  - R3 credibility scoring (L1/L2 grading per source class)
  - R4 triangulation (cross-question consistency in §4.1)
  - R5 corpus consolidation (per-question mechanism table in §5.2)
  - R6 deliverable extraction (exact ADR/SPIKE amendments in §7)
model_used: minimax-coding-plan/MiniMax-M3
sources_cited: 16
  # Primary (L1):
  # - kotlinlang.org/docs/ksp-overview.html
  # - kotlinlang.org/docs/ksp-quickstart.html
  # - kotlinlang.org/docs/exception-handling.html
  # - kotlinlang.org/docs/compiler-reference.html
  # - kotlinlang.org/docs/custom-script-deps-tutorial.html
  # - github.com/Kotlin/kotlinx.coroutines/blob/master/kotlinx-coroutines-core/common/src/CoroutineScope.kt
  # - developer.android.com/jetpack/androidx/releases/compose-compiler
  # - docs.temporal.io/develop/go/workflows/versioning
  # - learn.temporal.io/tutorials/typescript/background-check/durable-execution
  # - docs.dapr.io/developing-applications/building-blocks/workflow/workflow-features-concepts
  # - docs.azure.cn/en-us/durable-task/common/durable-task-orchestrations
  # # Secondary (L2):
  # - kt.academy/article/ak-ksp
  # - kt.academy/article/ak-compiler-plugin
  # - github.com/InsertKoinIO/koin/issues/2333
  # - medium.com/androiddevelopers/cancellation-in-coroutines-aa6b90163629
  # - betterprogramming.pub/the-silent-killer-thats-crashing-your-coroutines-9171d1e8f79b
  # - techinterview.org/post/3233469418/lld-schema-evolution
  # - javacodegeeks.com/2025/06/schema-evolution-in-apache-avro-protobuf-and-json-schema.html
  # - dzone.com/articles/schema-evolution-avro-protobuf-event-driven
  # - docs.gradle.org/4.6/userguide/build_cache.html
  # - github.com/JetBrains/kotlin/blob/master/libraries/scripting/jvm-host/src/kotlin/script/experimental/jvmhost/BasicJvmScriptingHost.kt
  # # Repo sources (L1):
  # - ADR-0006, ADR-0028, ADR-0029, ADR-0033, ADR-0034, ADR-0037, ADR-0039, ADR-0046, ADR-0054, ADR-0063, ADR-0064
  # - RECOVERY_DURABILITY.md, RUNTIME_MODEL.md, SCRIPTING_COMPILER_SPEC.md, LOCAL_FOUNDATION_CONSOLIDATION.md
  # - v2/pipeline-application/.../durable/OpId.kt, CanonicalDurableRunCoordinator.kt
  # - v2/pipeline-events/.../durable/OperationJournal.kt, OperationJournalSchema.kt
  # - v2/pipeline-domain/.../durable/OperationStatus.kt, DurableOperation.kt, Fingerprint.kt, OperationInput.kt, ReplayPolicy.kt
  # - v2/pipeline-application/.../PipelineRun.kt, UatDurable003ScriptBlockReplayTest.kt
  # - v2/pipeline-scripting-kotlin24/.../Kotlin24ScriptingHost.kt
  # - v2/pipeline-step-sdk/processor/.../StepDescriptorGenerator.kt
  # - memory observation #10035 (2026-09-05)
verified_claims: 14  # one per option × 5 questions, minus the rejected options
disputed_claims: 0  # all primary sources agree
open_gaps: 7  # listed in §6
decay_warnings:
  - date: 2026-09-05
    target: kotlinx.coroutines coroutineScope semantics
    source: kotlinlang.org/docs/exception-handling.html
    note: Kotlin 2.4 docs are stable as of 2026-09; re-verify at next minor version bump
  - date: 2026-09-05
    target: KSP source-location API
    source: kotlinlang.org/docs/ksp-overview.html
    note: KSP 2.4 stable; re-verify when Kotlin 2.5 lands
  - date: 2026-09-05
    target: Temporal GetVersion contract
    source: docs.temporal.io/develop/go/workflows/versioning
    note: Temporal docs are stable; cross-version contract unchanged
  - date: 2026-09-05
    target: Avro/Protobuf schema evolution rules
    source: javacodegeeks.com/2025/06/schema-evolution-in-apache-avro-protobuf-and-json-schema.html
    note: cross-confirmed across 3 articles; rule is durable
skill_resolution: injected (deep-research orchestrator SKILL.md + sub-skill methodology)
```

---

## 9. Reading order for the decision-maker (Rubentxu)

If you have 5 minutes: read §0 + §3.5 (Q5 cancellation boundary, the smallest and most dangerous gap).
If you have 20 minutes: read §1 (R0), §3 (all 5 questions with their options tables), §6 (open gaps).
If you have 60 minutes: read the whole document end-to-end and decide OQ-1 (INTERRUPTED vs ABORTED).
If you have 90 minutes: add the V2 docs (`docs/v2/04-adrs/ADR-0006..0064`) and the repo code (`OpId.kt`, `OperationJournal.kt`, `OperationStatus.kt`) to verify every claim in §4.1 triangulation.

---

## 10. One-line summary per question (the elevator pitch)

| Q | One-line answer |
|---|---|
| **Q1** | Use explicit stable keys (user-supplied) + inline-ordinal hashes from the runtime walker; KSP façade for `@Step` top-level kinds. No compiler plugin. |
| **Q2** | Classpath allowlist in the script host (deny non-deterministic APIs), plus the existing replay-divergence detector as the safety net. |
| **Q3** | Model structured concurrency inside `script {}` now — `coroutineScope` + `JoinPolicy.ALL_COMPLETE` only — to compose with the existing `ParallelFrame` infrastructure. |
| **Q4** | Additive evolution with a `schemaVersion` header on every persisted record (journal rows, `FailureRecord`, `InterruptionRecord`, `DurableTaskSnapshot/Terminal`). |
| **Q5** | Rethrow `CancellationException` verbatim; journal as a new `OperationStatus.INTERRUPTED` with typed `PipelineInterruptedException` marker; preserve cause chain. |

---

*End of report. Author: deep-research-orchestrator (SDDK executor). Pipeline: R0–R6. Date: 2026-09-05.*

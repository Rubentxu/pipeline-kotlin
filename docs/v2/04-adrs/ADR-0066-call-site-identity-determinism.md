---
type: adr
id: ADR-0066
title: "Call-site identity, determinism limits, and the script-host classpath deny-list"
status: proposed
date: 2026-09-06
deciders: "Rubentxu (product owner)"
supersedes: null
superseded_by: null
related:
  - ADR-0065
  - docs/v2/03-specifications/DURABLE_KOTLIN_EXECUTION.md
  - docs/v2/08-spikes/SPIKE-016-DURABLE-SCRIPTED-REPLAY.md
---

# ADR-0066 — Call-site identity, determinism limits, and the script-host classpath deny-list

## Status

Proposed. Acceptance is gated on the widened SPIKE-016 evidence (cycle
`p-733fb505b5a6bd2d/em-0-execution-model-contract-freeze`).

## Context

ADR-0065 makes scripted Kotlin control flow replayable by deriving stable
operation identities instead of serializing continuations. SPIKE-016 proved
the replay model with harness-composed identities of the shape
`digest|entry|call|path|ordinal` and input fingerprints `script|stdout`.
Two gaps remain before production:

1. identity composition must be specified end-to-end (source of each component,
   overrides, attempts);
2. the scripted surface must not import nondeterminism that the identity
   algorithm cannot see.

SPIKE-016 N2 additionally demonstrated that unescaped path segments can alias
identities under adversarial scope names; input digests catch differing
semantics (fail closed), but equal-input aliasing must be excluded by
construction in production.

## Decision

### 1. Composite operation identity

Every durable scripted operation identity is composed, in order:

```text
operationId =
  definitionDigest            — SHA-256 of the compiled source text
  + executableEntryPointId    — stable entry point in the compiled artifact
  + staticCallSiteId          — sourceId:line:column:stepName (compiler-derived)
  + dynamicScopePath          — ordered enclosing scope segments
  + invocationOrdinalWithinScope
  + attemptId
```

Components are joined by a reserved separator; each component MUST be
length-prefixed or hashed — raw concatenation is forbidden (SPIKE-016 N2
finding: raw path joins alias under adversarial segment values).

### 2. Explicit user override

A scripted call MAY pass `id = "userKey"`. The override replaces the
`staticCallSiteId + dynamicScopePath + ordinal` triple with the literal
`-k$userKey` suffix; `definitionDigest` and `attemptId` still apply. The
override is the user's contract for stability across refactors and MUST be
validated for uniqueness at first execution (duplicate override + different
input digest fails closed).

### 3. Attempt identity

For retries, `attemptId = SHA-256(retryScopeId ‖ attemptOrdinal)` where
`retryScopeId` is the dynamic-scope segment of the retry block. Attempt 1..N
therefore yields distinct, reproducible identities without wall-clock input.

### 4. Script-host classpath deny-list (scoped)

`Kotlin24ScriptingHost` MUST deny the following from **direct user-scripted
imports** rendered into the script compilation classpath:

```text
java.time.Clock
java.util.UUID
java.util.concurrent.ThreadLocalRandom
kotlin.random.Random
java.lang.System.getenv
kotlin.concurrent.*
kotlinx.coroutines.*
```

Scope of the deny-list — binding clarification carried from the blocked
`lfc4-000` specification:

- the deny-list binds the `defaultImports(...)` / script-visible classpath
  surface of the script host ONLY;
- runtime internals (`:pipeline-application`, `:pipeline-step-sdk:runtime`,
  workers) MAY continue to use `kotlinx.coroutines` for their own suspension;
- user `async { }`, `launch { }`, `coroutineScope { }`, `runBlocking` inside
  `script {}` are denied at compile time (initial denial of arbitrary user
  concurrency). Runtime-owned deterministic parallelism
  (`walkBranchDurable` / `ParallelFrameExecutor`, `JoinPolicy.ALL_COMPLETE`)
  remains the only structured-concurrency authority inside `script {}`.
  `FIRST_SUCCESS` / `ANY_COMPLETE` inside `script {}` are deferred
  (OPEN_QUESTIONS Q-12, LFC-6.4).

Enforcement is a compile-time diagnostic naming the denied import
(spike: `CompiledScriptedEntryPointHostTest` pattern).

## Consequences

- Compiler must emit static call-site IDs (source-mapped) — EM-8 work.
- Identity algorithm and deny-list become compatibility-relevant public
  contract; changes require a schema/versioning review under ADR-0067.
- Determinism is enforced at the script boundary only; host-side clocks and
  RNGs remain available to the runtime (existing E4-* evidence).
